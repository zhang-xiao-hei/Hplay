package com.hpplay.transport

/**
 * 跨协议（CarPlay / CarLife / CarLink）共用的蓝牙链路守卫。
 *
 * 三个协议各自持有自己的蓝牙通道，但面对的是同一块系统蓝牙栈、同一批已配对手机。之前每个协议
 * 都按"我这一路失败就立刻重连"的思路写，结果是：一条链路刚被拆掉，另一路（或自己的重试）马上
 * 又去连同一台手机，系统蓝牙栈就对同一台手机反复建立/断开链路 —— 车机和手机屏幕上表现就是
 * 「蓝牙已连接」提示反复跳出，同时把真正需要的那次连接挤掉。
 *
 * 这个守卫只做三件事，全部按设备地址记账，与协议无关：
 *
 * 1. **并发去重**：同一地址同一时刻只允许一次连接尝试，第二个请求直接返回 [Begin.IN_FLIGHT]；
 * 2. **失败退避**：失败后按 1s → 2s → 4s → 8s 指数退避，退避期内 [Begin.COOLING_DOWN]，
 *    避免对着一个连不上的手机狂连（每次狂连都会在系统侧留下一次「已连接/已断开」）；
 * 3. **播报去重**：[markConnected] 返回 true 只在该地址"本次连接世代"的第一次调用，
 *    同一世代内的重复回调（有的 ROM 会连发两三次 STATE_CONNECTED）一律返回 false。
 *
 * 设计成纯逻辑对象：不碰 Android API，时间由调用方传入，方便写单元测试；现场排查时也能在日志里
 * 看到"这次尝试是被谁挡掉的"。
 */
object BluetoothLinkGuard {

    /** 同一地址的重复「已连接」回调在这个窗口内一律视为重复。 */
    const val ANNOUNCE_WINDOW_MILLIS = 5_000L

    /** 失败退避阶梯（毫秒），最后一档之后一直维持。 */
    private val BACKOFF_STEPS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)

    /** 一次连接尝试的准入结果。 */
    enum class Begin {
        /** 可以发起连接。 */
        STARTED,

        /** 同一地址已有一次尝试在飞，直接跳过。 */
        IN_FLIGHT,

        /** 上一次失败还在退避期，跳过（调用方可等待 [cooldownRemainingMs] 后重试）。 */
        COOLING_DOWN,
    }

    private class Entry {
        var inFlight = false
        var failures = 0
        var readyAt = 0L
        var connected = false
        var announcedAt = Long.MIN_VALUE
    }

    private val entries = HashMap<String, Entry>()

    fun beginConnect(address: String, nowMs: Long = now()): Begin = synchronized(entries) {
        val entry = entries.getOrPut(address) { Entry() }
        when {
            entry.inFlight -> Begin.IN_FLIGHT
            nowMs < entry.readyAt -> Begin.COOLDING_DOWN
            else -> {
                entry.inFlight = true
                Begin.STARTED
            }
        }
    }

    /**
     * 标记一次成功连接。返回 true 表示"这次可以对外播报/弹提示"，false 表示同一世代的重复回调，
     * 调用方应当把 UI 提示与通知吞掉（日志可以留一条 suppressed）。
     */
    fun markConnected(address: String, nowMs: Long = now()): Boolean = synchronized(entries) {
        val entry = entries.getOrPut(address) { Entry() }
        entry.inFlight = false
        entry.failures = 0
        entry.readyAt = 0L
        val duplicate = entry.connected && nowMs - entry.announcedAt < ANNOUNCE_WINDOW_MILLIS
        entry.connected = true
        if (!duplicate) entry.announcedAt = nowMs
        !duplicate
    }

    /** 标记一次连接尝试失败，进入退避；返回下一次允许尝试的时间点。 */
    fun markFailed(address: String, nowMs: Long = now()): Long = synchronized(entries) {
        val entry = entries.getOrPut(address) { Entry() }
        entry.inFlight = false
        entry.connected = false
        val step = BACKOFF_STEPS_MILLIS[minOf(entry.failures, BACKOFF_STEPS_MILLIS.lastIndex)]
        entry.failures++
        entry.readyAt = nowMs + step
        entry.readyAt
    }

    /** 链路真正断开（不是尝试失败）：清掉连接标记，让下一次连接可以正常播报。 */
    fun markDisconnected(address: String) = synchronized(entries) {
        entries[address]?.let {
            it.connected = false
            it.inFlight = false
            it.announcedAt = Long.MIN_VALUE
        }
        Unit
    }

    /** 连接尝试被主动放弃（超时/取消），不算失败，不进入退避，只解锁并发标记。 */
    fun markAbandoned(address: String) = synchronized(entries) {
        entries[address]?.inFlight = false
        Unit
    }

    fun isCoolingDown(address: String, nowMs: Long = now()): Boolean = synchronized(entries) {
        val entry = entries[address] ?: return false
        nowMs < entry.readyAt
    }

    fun cooldownRemainingMs(address: String, nowMs: Long = now()): Long = synchronized(entries) {
        val entry = entries[address] ?: return 0L
        (entry.readyAt - nowMs).coerceAtLeast(0L)
    }

    fun isConnected(address: String): Boolean = synchronized(entries) {
        entries[address]?.connected == true
    }

    /** 测试/断开整栈时清空记账。 */
    fun reset() = synchronized(entries) {
        entries.clear()
    }

    /** 默认时钟：单调时间，避免系统改时间或时区变化把退避算错。 */
    private fun now(): Long = System.nanoTime() / 1_000_000L
}
