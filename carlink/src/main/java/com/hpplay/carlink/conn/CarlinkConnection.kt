package com.hpplay.carlink.conn

import android.util.Log
import com.hpplay.carlink.auth.CarlinkAuth
import com.hpplay.carlink.auth.CarlinkCrypto
import com.hpplay.carlink.channel.CarlinkChannel
import com.hpplay.carlink.channel.CarlinkControl
import com.hpplay.carlink.proto.CarlinkPorts
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * CarLink 车机端连接层：监听 AUTH/CONTROL/SENSOR/MEDIA/CERT/VIDEO/UIBC 七个端口，等待手机连入。
 *
 * 车机 = TCP 服务端（与 libovmsink.so 作为 RTSP 客户端相对）。手机连入 AUTH 完成握手后，
 * 拿到手机 IP（= 视频 RTSP 源 host）与 AES 会话密钥；随后 CONTROL/SENSOR/MEDIA/CERT/VIDEO
 * 通道用该密钥加密通信。
 */
class CarlinkConnection(
    private val config: CarlinkControl.CarConfig,
    /**
     * 提供 AUTH 握手可用的**候选**口令（按优先级排列）：手机经 BLE 下发的配对码、车机本地
     * 生成的配对码、与手机共享的 WiFi 密码。返回空列表表示暂不可用。
     */
    private val pinCandidates: () -> List<String> = { emptyList() },
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String)
        fun onAuthDone(phoneIp: String, phoneModel: String, workModes: Int)
        fun onDisconnected()
    }

    enum class State { IDLE, LISTENING, AUTHENTICATING, CONNECTED, ERROR }

    private val tag = "CarlinkConn"
    @Volatile private var running = false
    private var authServer: ServerSocket? = null
    private var controlServer: ServerSocket? = null
    private var sensorServer: ServerSocket? = null
    private var mediaServer: ServerSocket? = null
    private var certServer: ServerSocket? = null
    private var videoServer: ServerSocket? = null
    private var uibcServer: ServerSocket? = null

    private var auth: CarlinkAuth? = null
    private var control: CarlinkControl? = null
    private val extraChannels = mutableListOf<CarlinkChannel>()
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var uibcSessionKey: ByteArray? = null

    /**
     * AUTH 口令的候选轮换游标。
     *
     * 手机与车机必须用**同一个**密钥算 authPkHmac，但 ICCOA 各家实现的口径并不统一：
     *  - 有的把手机自己生成的配对码通过 BLE `ClientInfo.pinCodeOrAuthentication` 写给车机；
     *  - 有的直接用双方共享的 WiFi 密码（热点 PSK / 局域网 WiFi 密码）。
     * 车机无法预先知道手机选的是哪一个，与其猜，不如**轮着试**：每次 AUTH 握手换一个候选，
     * 一旦某个候选被手机接受（收到 AuthConfirm）就把它锁定，后续握手一直用它。
     */
    private val pinCursor = java.util.concurrent.atomic.AtomicInteger(0)

    /** 已经被手机接受过的口令；命中后不再轮换。 */
    @Volatile private var pinnedPin: String? = null

    /** 把关键节点同时写进 carlink 会话日志（现场没有 adb 时唯一能取回的线索）。 */
    private fun logSession(message: String) {
        runCatching { com.hpplay.SessionLogger.log("carlink", message) }
    }

    /** 取出本次 AUTH 握手要用的候选口令。 */
    private fun nextPin(): String? {
        pinnedPin?.let { return it }
        val list = pinCandidates()
            .map { it.trim() }
            .filter { it.length >= CarlinkAuth.MIN_PIN_LENGTH }
            .distinct()
        if (list.isEmpty()) return null
        val index = pinCursor.getAndIncrement()
        val chosen = list[index % list.size]
        Log.i(tag, "AUTH candidate #$index used (len=${chosen.length}, pool=${list.size})")
        // 现场最缺的就是"这次握手到底用了哪个口令"：只记长度和序号，不落明文。
        logSession("AUTH candidate #$index (len=${chosen.length}, pool=${list.size})")
        return chosen
    }

    /** 发送加密闭包：逐帧动态读取当前 sessionKey；key 未就绪时原样透传（握手前帧）。 */
    private fun encryptor(): (ByteArray) -> ByteArray = { body ->
        val key = sessionKey
        if (key != null) CarlinkCrypto.aesGcmEncrypt(key, body) else body
    }

    private fun decryptor(): (ByteArray) -> ByteArray = { body ->
        val key = sessionKey
        if (key != null) CarlinkCrypto.aesGcmDecrypt(key, body) else body
    }

    fun start() {
        if (running) return
        running = true
        authServer = bind(CarlinkPorts.AUTH)
        controlServer = bind(CarlinkPorts.CONTROL)
        sensorServer = bind(CarlinkPorts.SENSOR)
        mediaServer = bind(CarlinkPorts.MEDIA)
        certServer = bind(CarlinkPorts.CERT)
        videoServer = bind(CarlinkPorts.VIDEO)
        // UIBC(4321) 是 GetPortResponse 里明确下发给手机的端口，这里以前只声明常量、从不监听，
        // 手机去连就是 connection refused（表现为"投屏出来了但点不动/直接掉线"）。
        uibcServer = bind(CarlinkPorts.UIBC)
        if (authServer == null) {
            listener.onState(State.ERROR, "AUTH 端口 ${CarlinkPorts.AUTH} 绑定失败")
            return
        }
        logSession(
            "listen ports AUTH=${CarlinkPorts.AUTH} CONTROL=${CarlinkPorts.CONTROL} " +
                "SENSOR=${CarlinkPorts.SENSOR} MEDIA=${CarlinkPorts.MEDIA} " +
                "CERT=${CarlinkPorts.CERT} VIDEO=${CarlinkPorts.VIDEO} UIBC=${CarlinkPorts.UIBC}" +
                " (uibc bound=${uibcServer != null})",
        )
        listener.onState(
            State.LISTENING,
            "监听端口 AUTH=${CarlinkPorts.AUTH} CONTROL=${CarlinkPorts.CONTROL} " +
                "SENSOR=${CarlinkPorts.SENSOR} MEDIA=${CarlinkPorts.MEDIA} " +
                "CERT=${CarlinkPorts.CERT} VIDEO=${CarlinkPorts.VIDEO}",
        )
        acceptLoop("auth", authServer) { acceptAuth(it) }
        acceptLoop("control", controlServer) { acceptControl(it) }
        acceptLoop("sensor", sensorServer) { acceptSensor(it) }
        acceptLoop("media", mediaServer) { acceptMedia(it) }
        acceptLoop("cert", certServer) { acceptCert(it) }
        acceptLoop("video", videoServer) { acceptVideo(it) }
        acceptLoop("uibc", uibcServer) { acceptUibc(it) }
    }

    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    } catch (t: Throwable) {
        Log.e(tag, "bind $port failed", t)
        null
    }

    private fun acceptLoop(name: String, server: ServerSocket?, handler: (Socket) -> Unit) {
        if (server == null) {
            Log.w(tag, "[$name] server not bound, skip accept loop")
            return
        }
        Thread({
            while (running) {
                try {
                    val s = server.accept()
                    Log.i(tag, "[$name] phone connected from ${s.inetAddress?.hostAddress}")
                    handler(s)
                } catch (t: Throwable) {
                    if (running) Log.w(tag, "[$name] accept ended: ${t.message}")
                    break
                }
            }
        }, "carlink-accept-$name").apply { isDaemon = true; start() }
    }

    private fun acceptAuth(socket: Socket) {
        listener.onState(State.AUTHENTICATING, "手机已连入 AUTH，握手进行中…")
        val a = CarlinkAuth(
            socket,
            pinProvider = ::nextPin,
            onHandshakeDone = { result ->
                sessionKey = result.sessionKey
                uibcSessionKey = result.uibcSessionKey
                // 手机认了这个候选 → 锁定它，后续握手不再轮换。
                auth?.pinUsed?.let { hit ->
                    pinnedPin = hit
                    Log.i(tag, "AUTH credential accepted and pinned (len=${hit.length})")
                }
                listener.onState(State.CONNECTED, "认证完成，手机IP=${result.phoneIp}")
                listener.onAuthDone(result.phoneIp, result.phoneModel, result.workModes)
            },
            onError = { t ->
                listener.onState(State.ERROR, "AUTH 握手失败: ${t.message}")
            },
        )
        auth = a
        a.start()
    }

    private fun acceptControl(socket: Socket) {
        val c = CarlinkControl(
            socket,
            config,
            onDisconnect = {
                listener.onDisconnected()
                // 只关掉 CONTROL 这一条通道，**不**停掉整个监听层。
                //
                // 以前这里调 stop()，会把 AUTH/CONTROL/SENSOR/MEDIA/CERT/VIDEO 六个
                // ServerSocket 全部关掉。表现是「第一次连得上，之后手机再连就永远失败」——
                // 因为已经没有端口在监听了，而页面还停在等待状态，用户看不出发生了什么。
                // 手机断开后应当保持监听，等它下一次连进来。
            },
            encrypt = encryptor(),
            decrypt = decryptor(),
        )
        control = c
        c.start()
        listener.onState(State.CONNECTED, "CONTROL 通道已建立")
    }

    /** 建立一条带加密的通用通道并加入管理列表（SENSOR/MEDIA/CERT/VIDEO/UIBC 共用）。 */
    private fun startExtraChannel(name: String, socket: Socket) {
        val ch = CarlinkChannel(
            name,
            socket,
            { _, frame, _ -> Log.d(tag, "$name frame cat=${frame.category} method=${frame.method}") },
            encryptBody = encryptor(),
            decryptBody = decryptor(),
        )
        synchronized(extraChannels) { extraChannels.add(ch) }
        ch.start()
    }

    private fun acceptSensor(socket: Socket) {
        // 传感器下行（车机 → 手机）：GPS/灯光/档位等。建链并保活，具体上报按需扩展。
        startExtraChannel("sensor", socket)
    }

    private fun acceptMedia(socket: Socket) {
        // MEDIA 通道（音频/UIBC 控制流）。建链并保活，具体消息按需扩展。
        startExtraChannel("media", socket)
    }

    private fun acceptCert(socket: Socket) {
        // CERT 通道（证书/鉴权材料）。建链并保活，具体消息按需扩展。
        startExtraChannel("cert", socket)
    }

    private fun acceptVideo(socket: Socket) {
        // VIDEO 通道（视频控制/信令；媒体面 RTP 由 libovmsink.so 拉流）。建链并保活。
        startExtraChannel("video", socket)
    }

    /** UIBC（触摸/按键注入通道）。手机按下时的输入事件走这里，缺了它投屏画面点不动。 */
    private fun acceptUibc(socket: Socket) {
        startExtraChannel("uibc", socket)
    }

    /**
     * 通知手机日间/夜间（CONTROL 通道 15）。未建立 CONTROL 时返回 false，调用方稍后重发即可。
     *
     * 参考 APP 的行为是"车机说了算"：连接一建立就发一次当前主题，之后明暗变化即时补发。
     */
    fun sendDayNight(day: Boolean): Boolean = control?.sendDayNight(day) ?: false

    fun stop() {
        running = false
        runCatching { authServer?.close() }
        runCatching { controlServer?.close() }
        runCatching { sensorServer?.close() }
        runCatching { mediaServer?.close() }
        runCatching { certServer?.close() }
        runCatching { videoServer?.close() }
        runCatching { uibcServer?.close() }
        auth?.close()
        control?.close()
        synchronized(extraChannels) {
            extraChannels.forEach { runCatching { it.close() } }
            extraChannels.clear()
        }
        authServer = null; controlServer = null; sensorServer = null
        mediaServer = null; certServer = null; videoServer = null; uibcServer = null
        auth = null; control = null
        sessionKey = null; uibcSessionKey = null
        // 承载方式可能已经变了（热点 ↔ 局域网），旧的候选口令不再可靠，重新轮换。
        pinnedPin = null
        listener.onState(State.IDLE, "已停止监听")
    }
}
