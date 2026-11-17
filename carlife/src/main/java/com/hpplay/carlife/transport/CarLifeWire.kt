package com.hpplay.carlife.transport

import android.util.Log
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeFrame
import com.hpplay.carlife.protocol.CarLifeFrameCodec
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CarLife 无线承载（HU 视角，照引擎 HotspotProtocolTransport/WirlessConnector 复刻）：
 *
 *  发现：车机在 UDP 7999 监听；手机连上热点/局域网后发 UDP 广播，
 *        车机从包的源地址取得手机 IP（两种模式下都成立）。
 *  建链：车机主动连接手机 IP 的 7 个端口（CMD 7240 / VIDEO 8240 / AUDIO 9240 /
 *        TTS 9241 / VR 9242 / TOUCH 9340 / UPDATE 9440）。
 *  传输：每通道一条 TCP，线上 = 命令头 + 载荷；读线程解帧回调，写按通道分发。
 *
 * 断链自愈（2026-11 修复）：
 *  - 发现线程常驻，不再"建链成功就退出"，断链后下一条广播即可自动重连；
 *  - 断链时关闭全部 socket、清空通道表、停掉读线程，否则残留 socket 会让后续建链
 *    永远失败（openChannels 见到非空 sockets 直接返回 false）；
 *  - onChannelsBroken 只回调一次，避免多条通道同时断开时重复通知上层。
 *
 * 建链提速（2026-11 修复）：
 *  - 7 条通道改为**并发**建链（原来串行，每条 4s 超时，最坏 28s）；
 *  - 单条超时 4s → 1.2s；
 *  - 拿到一次广播后在该 IP 上连续重试 5 轮（300/500/800/1200ms 退避），覆盖手机启动
 *    CarLife 服务的窗口；以前只试一次，端口未 listen 就放弃，等手机不再广播 → 死等；
 *  - 建链放到独立执行器，UDP 发现线程始终在收包。
 */
class CarLifeWire(
    private val listener: Listener,
) {
    interface Listener {
        /** 7 条通道全部连上（对齐引擎 onConnectionAttached）。 */
        fun onChannelsAttached()

        /** 任一通道断开（连接随之失效）。 */
        fun onChannelsBroken(reason: String)

        /** 收到一帧（已按通道解帧）。 */
        fun onFrame(frame: CarLifeFrame)
    }

    /** 传输层生命周期（stop() 之后不可再用）。 */
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    /** 当前链路是否处于"已断开"状态（保证 onChannelsBroken 只回调一次）。 */
    private val linkBroken = AtomicBoolean(true)
    /** 链路代次：断链/重建链路时自增，旧读线程据此自行退出。 */
    private val generation = AtomicInteger(0)

    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarLifeWriter").apply { isDaemon = true }
    }
    /**
     * 建链执行器（单线程）。
     *
     * 建链（含重试）必须离开 UDP 发现线程：以前 `openChannels` 直接在 receive 循环里串行连
     * 7 个端口，每个 4s 超时，最坏 28s 收不了广播 —— 手机等不及就判定"找不到车机"放弃了。
     */
    private val connectExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarLifeConnect").apply { isDaemon = true }
    }
    /** 同一时刻只允许一轮建链，广播来得再密也不会互相踩。 */
    private val connecting = AtomicBoolean(false)
    /** 上一轮建链失败的原因（打在日志里，现场据此区分"手机没起服务"和"端口被拒")。 */
    @Volatile private var lastFailure: String = ""
    private val sockets = HashMap<Int, Socket>()
    private val readers = CopyOnWriteArrayList<Thread>()
    private var udpSocket: DatagramSocket? = null
    private var discoveryThread: Thread? = null
    /** 最近一次成功建链的手机 IP：断链后可以直接回连，不必等手机再广播。 */
    @Volatile private var lastPhoneIp: String? = null

    private val lock = Any()

    /**
     * 启动/恢复发现（UDP 7999 监听线程）。
     *
     * 之前这里是 `if (!running.compareAndSet(false, true)) return`：running 一旦置 true，
     * 运行期再调用就是空操作 —— 断链之后车机再也不监听 7999，只能重启 App 才能恢复，
     * 这正是"carlife 很难重连"的根因。现在发现线程常驻、可反复拉起。
     */
    fun startDiscovery() {
        if (stopped.get()) return
        running.set(true)
        ensureDiscoveryThread()
    }

    /** 直接指定手机 IP 建链（设置里手动填 IP / 诊断钩子）。 */
    fun connectTo(phoneIp: String): Boolean {
        if (stopped.get()) return false
        running.set(true)
        return try {
            openChannelsWithRetry(InetAddress.getByName(phoneIp))
        } catch (t: Throwable) {
            Log.w(TAG, "connectTo $phoneIp failed", t)
            false
        }
    }

    /**
     * 断链后主动回连最近一次的手机 IP。
     *
     * 手机端不一定每轮都重新广播，靠广播触发重连会有几秒空窗；这里先直接回连一次。
     */
    fun reconnectLast(): Boolean {
        val ip = lastPhoneIp
        if (ip == null) return false
        Log.i(TAG, "reconnectLast $ip")
        return connectTo(ip)
    }

    fun stop() {
        stopped.set(true)
        running.set(false)
        runCatching { writeExecutor.shutdownNow() }
        runCatching { connectExecutor.shutdownNow() }
        teardownLink()
        synchronized(lock) {
            runCatching { udpSocket?.close() }
            udpSocket = null
        }
    }

    /**
     * 按通道发送一帧。
     *
     * 必须离开主线程写 socket：触摸回传是从 UI 线程调的，debug 包的 StrictMode 会直接抛
     * NetworkOnMainThreadException（真实车机上表现为「触摸没反应」，还会连带把通道判死）。
     * 单线程执行器同时保证握手/触摸的发送顺序。
     */
    fun write(frame: CarLifeFrame) {
        runCatching { writeExecutor.execute { doWrite(frame) } }
    }

    private fun doWrite(frame: CarLifeFrame) {
        val socket = synchronized(lock) { sockets[frame.channel] } ?: return
        val wire = CarLifeFrameCodec.encode(frame)
        try {
            val output: OutputStream = socket.getOutputStream()
            var offset = 0
            while (offset < wire.size) {
                val chunk = minOf(wire.size - offset, MAX_PACKET)
                output.write(wire, offset, chunk)
                offset += chunk
            }
            output.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "write channel ${frame.channel} failed", t)
            handleLinkBroken("write error: ${t.message}")
        }
    }

    // ---- 发现 ----

    /**
     * 保证发现线程在跑。
     *
     * 线程内部退出（异常）时会把 discoveryThread 置空，这里可以重新拉起。
     */
    private fun ensureDiscoveryThread() {
        if (!running.get() || stopped.get()) return
        synchronized(lock) {
            val current = discoveryThread
            if (current != null && current.isAlive) return
            val thread = Thread({ listenUdpLoop() }, "CarLifeUdpDiscovery")
            discoveryThread = thread
            thread.start()
        }
    }

    /**
     * UDP 发现循环（常驻）。
     *
     * 关键改动：建链成功后**不退出**，只是忽略后续广播。这样断链时监听端口还在，
     * 手机下一条广播（通常 1s 内）就能自动重连；退出再重建的写法会留下
     * "端口没人监听 + 残留 socket 阻止建链"的死局。
     */
    private fun listenUdpLoop() {
        val socket = obtainUdpSocket() ?: return
        val buffer = ByteArray(1024)
        try {
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val phoneIp = packet.address?.hostAddress ?: continue
                val linked = synchronized(lock) { sockets.isNotEmpty() }
                if (linked) {
                    Log.d(TAG, "忽略 UDP 广播（链路已建立）: $phoneIp")
                    continue
                }
                Log.d(TAG, "收到 UDP 广播: $phoneIp")
                // 建链放到独立执行器：发现线程必须继续收包，否则重试期间来的广播全丢。
                scheduleOpen(InetAddress.getByName(phoneIp))
            }
        } catch (e: SocketException) {
            if (running.get()) Log.w(TAG, "udp socket closed: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "udp loop: ${t.message}")
        } finally {
            synchronized(lock) {
                if (discoveryThread === Thread.currentThread()) discoveryThread = null
            }
        }
    }

    /** UDP socket 只创建一次并复用（重复 bind 7999 会失败）。 */
    private fun obtainUdpSocket(): DatagramSocket? = synchronized(lock) {
        udpSocket?.let { return@synchronized it }
        try {
            DatagramSocket(CarLife.BROADCAST_WIFI_PORT).also { udpSocket = it }
        } catch (t: Throwable) {
            Log.e(TAG, "UDP ${CarLife.BROADCAST_WIFI_PORT} bind failed: ${t.message}")
            listener.onChannelsBroken("udp bind failed")
            null
        }
    }

    // ---- 建链 ----

    /** 把一轮建链排到执行器上（不阻塞 UDP 发现线程）。 */
    private fun scheduleOpen(host: InetAddress) {
        if (!connecting.compareAndSet(false, true)) {
            Log.d(TAG, "link attempt already in flight; ignore broadcast from ${host.hostAddress}")
            return
        }
        runCatching {
            connectExecutor.execute {
                try {
                    openChannelsWithRetry(host)
                } finally {
                    connecting.set(false)
                }
            }
        }.onFailure { connecting.set(false) }
    }

    /**
     * 带重试的建链。
     *
     * 为什么必须重试：手机收到 UDP 广播后才去起 CarLife 的 7 个监听端口，这中间有 1–3s 窗口。
     * 车机如果**只**在收到广播的那一瞬间试一次，端口还没 listen 就 connection refused，
     * 之后手机不再广播（它已经在等连），车机就一直停在"等待连接"——用户看到的是
     * 「手机显示找不到车机 / 车机一直转圈」，也就是"识别速度不够"。
     *
     * 现在：拿到一次广播后按 [LINK_RETRY_DELAYS_MS] 连续重试 [LINK_ATTEMPTS] 轮，
     * 覆盖手机起服务的整个窗口，端口一就绪立刻连上。
     */
    private fun openChannelsWithRetry(host: InetAddress): Boolean {
        for (attempt in 1..LINK_ATTEMPTS) {
            if (!running.get() || stopped.get()) return false
            synchronized(lock) { if (sockets.isNotEmpty()) return true }
            val startedAt = System.currentTimeMillis()
            val ok = openChannels(host)
            val elapsed = System.currentTimeMillis() - startedAt
            if (ok) {
                Log.i(TAG, "link up in ${elapsed}ms (attempt $attempt/$LINK_ATTEMPTS, ${host.hostAddress})")
                return true
            }
            Log.w(
                TAG,
                "link attempt $attempt/$LINK_ATTEMPTS failed in ${elapsed}ms: $lastFailure",
            )
            if (attempt < LINK_ATTEMPTS) {
                val delay = LINK_RETRY_DELAYS_MS[minOf(attempt - 1, LINK_RETRY_DELAYS_MS.size - 1)]
                runCatching { Thread.sleep(delay) }
            }
        }
        Log.w(TAG, "link gave up after $LINK_ATTEMPTS attempts: $lastFailure")
        return false
    }

    /**
     * 一轮完整建链：7 条通道**并发**连。
     *
     * 以前是串行 for 循环，任何一条失败就整体放弃；在端口没就绪时最坏要等 7×超时 才回到
     * 发现循环。并发之后一轮的耗时 ≈ 单条超时，重试才有意义（现场"识别慢"的直接原因）。
     */
    private fun openChannels(host: InetAddress): Boolean {
        synchronized(lock) { if (sockets.isNotEmpty()) return true }
        val opened = ConcurrentHashMap<Int, Socket>()
        val failures = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(CarLife.CHANNEL_PORTS.size)
        for ((channel, port) in CarLife.CHANNEL_PORTS) {
            Thread({
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    opened[channel] = socket
                } catch (t: Throwable) {
                    failures.add("$channel:$port ${t.javaClass.simpleName}(${t.message})")
                } finally {
                    latch.countDown()
                }
            }, "CarLifeConnect_$channel").apply { isDaemon = true; start() }
        }
        runCatching { latch.await(CONNECT_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS) }
        if (opened.size != CarLife.CHANNEL_PORTS.size) {
            lastFailure = if (failures.isEmpty()) {
                "only ${opened.size}/${CarLife.CHANNEL_PORTS.size} channels connected"
            } else {
                failures.joinToString("; ")
            }
            opened.values.forEach { runCatching { it.close() } }
            return false
        }
        lastFailure = ""
        val gen = generation.incrementAndGet()
        synchronized(lock) {
            if (!running.get()) {
                opened.values.forEach { runCatching { it.close() } }
                return false
            }
            sockets.putAll(opened)
        }
        linkBroken.set(false)
        lastPhoneIp = host.hostAddress
        Log.i(TAG, "7 channels attached to ${host.hostAddress}")
        for ((channel, socket) in opened) startReader(channel, socket, gen)
        listener.onChannelsAttached()
        return true
    }

    private fun startReader(channel: Int, socket: Socket, gen: Int) {
        val assembler = CarLifeFrameCodec.StreamAssembler(channel)
        val thread = Thread({
            try {
                val input: InputStream = socket.getInputStream()
                val buffer = ByteArray(READ_BUFFER)
                while (running.get() && gen == generation.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (gen != generation.get()) break
                    assembler.feed(buffer, 0, n).forEach { frame ->
                        // 旧链路的残留帧直接丢弃，不能喂给新会话
                        if (gen == generation.get()) listener.onFrame(frame)
                    }
                }
                if (running.get() && gen == generation.get()) {
                    handleLinkBroken("channel $channel closed by peer")
                }
            } catch (t: Throwable) {
                if (running.get() && gen == generation.get()) {
                    handleLinkBroken("channel $channel read: ${t.message}")
                }
            }
        }, "CarLifeReader_$channel")
        readers.add(thread)
        thread.start()
    }

    /**
     * 链路断开：关掉全部 socket、清空通道表、停掉读线程，并且只通知上层一次。
     *
     * 之前这里只回调 onChannelsBroken，socket 既没关也没清 —— 于是：
     *  - openChannels 见到非空 sockets 直接 return false，再也连不上；
     *  - 读线程继续用坏掉的 socket 读，重复回调断链。
     */
    private fun handleLinkBroken(reason: String) {
        val first = linkBroken.compareAndSet(false, true)
        teardownLink()
        if (first) {
            // teardownLink() 会中断读线程 —— 当前线程很可能就是那个读线程，
            // 于是中断标记会带着走进上层回调：上层任何可中断的阻塞调用都会被
            // InterruptedException 打断（表现就是"断链回调到了，但后续动作全丢了"）。
            Thread.interrupted()
            Log.w(TAG, "link broken: $reason")
            listener.onChannelsBroken(reason)
        }
    }

    /**
     * 丢弃当前链路（关 socket + 清通道表 + 停读线程），不回调 onChannelsBroken。
     *
     * 会话层主动终止（超时/鉴权失败/手机退出）时用：上层自己负责收尾与重新发现，
     * 避免和传输层的断链回调互相套娃。
     */
    fun dropLink(reason: String) {
        Log.i(TAG, "dropLink: $reason")
        linkBroken.set(true)
        teardownLink()
    }

    private fun teardownLink() {
        val deadSockets: List<Socket>
        val deadReaders: List<Thread>
        synchronized(lock) {
            deadSockets = sockets.values.toList()
            sockets.clear()
            deadReaders = readers.toList()
            readers.clear()
        }
        // 代次自增：正在阻塞读 socket 的旧线程会在下一次循环检查时退出
        generation.incrementAndGet()
        deadSockets.forEach { runCatching { it.close() } }
        deadReaders.forEach { reader ->
            // 不要中断当前线程：它可能正在执行断链回调，自中断会把中断标记带进上层
            if (reader !== Thread.currentThread()) runCatching { reader.interrupt() }
        }
    }

    companion object {
        private const val TAG = "CarLifeWire"
        private const val MAX_PACKET = 10240       // 对齐引擎 SocketCommunicator.MAX_PACKET_SIZE
        private const val READ_BUFFER = 64 * 1024

        /**
         * 单条通道的连接超时。
         *
         * 从 4s 降到 1.2s：同网段/热点内连接失败基本都是"端口没人 listen"（立刻 RST），
         * 拖到 4s 只会让一轮重试占掉整个手机等待窗口。
         */
        private const val CONNECT_TIMEOUT_MS = 1200

        /** 一轮广播后最多试几轮（覆盖手机启动 CarLife 服务的那几秒）。 */
        private const val LINK_ATTEMPTS = 5

        /** 每轮之间的退避（毫秒），短的在前，保证端口一就绪就能连上。 */
        private val LINK_RETRY_DELAYS_MS = longArrayOf(300, 500, 800, 1200)
    }
}
