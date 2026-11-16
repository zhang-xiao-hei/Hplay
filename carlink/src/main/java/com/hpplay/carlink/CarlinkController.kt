package com.hpplay.carlink

import android.content.Context
import android.util.Log
import android.view.Surface
import com.hpplay.carlink.channel.CarlinkControl
import com.hpplay.carlink.conn.CarlinkConnection
import com.hpplay.carlink.proto.CarlinkPorts
import com.hpplay.carlink.video.CarlinkVideoSink
import com.ucarsink.sink.natives.SinkNative

/**
 * CarLink 会话编排：连接层（TCP/AUTH/CONTROL）+ 视频层（libovmsink.so + MediaCodec）。
 *
 * 认证完成拿到手机 IP 后，调用 SinkNative.start(phoneIp, 7236, …) 让 .so 作为 RTSP 客户端连向
 * 手机的视频端口拉流，帧经回调交给 [CarlinkVideoSink] 解码上屏。
 */
class CarlinkController(
    private val context: Context,
    private val config: CarlinkControl.CarConfig,
    /**
     * 提供 AUTH 握手可用的**候选**口令（按优先级排列）。
     *
     * 手机与车机必须用同一个密钥算 authPkHmac，但 ICCOA 各家实现的口径不统一（手机下发的
     * 配对码 vs 双方共享的 WiFi 密码），所以这里给出一串候选，由 [CarlinkConnection] 轮换使用。
     */
    private val pinCandidates: () -> List<String> = { emptyList() },
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(state: String, detail: String)
    }

    private val tag = "CarlinkCtrl"
    val videoSink = CarlinkVideoSink()
    private var connection: CarlinkConnection? = null
    @Volatile private var phoneIp: String? = null
    @Volatile private var started = false
    private var surface: Surface? = null

    /**
     * 挂载画面用的 Surface（可以随时为 null）。
     *
     * 与 [start] 分开：连接层（TCP 端口监听）必须**独立于**画面存在 ——
     * 手机是先发现车机、再连 AUTH 端口完成握手，最后才有视频。如果把端口监听绑在
     * "Surface 就绪"上，那么 Surface 没回调（或重建期间）时车机就是一个死端口，
     * 手机连多少次都连不上。
     */
    fun attachSurface(s: Surface?) {
        surface = s
        videoSink.attachSurface(s)
    }

    /**
     * 启动连接层（监听 AUTH/CONTROL/... 六个端口）与视频 sink。
     *
     * 幂等：重复调用不会重复绑定端口。
     */
    fun start() {
        if (started) return
        started = true
        // Native sink 的初始化是"尽力而为"：它失败不应该连累 TCP 监听层，
        // 否则手机连不上、用户只看到"一直等待"，连日志都指向错误的方向。
        runCatching {
            SinkNative.registerListener(videoSink)
            videoSink.setLowLatency(config.supportLowLatency)
        }.onFailure { Log.e(tag, "video sink init failed", it) }
        val conn = CarlinkConnection(config, pinCandidates, object : CarlinkConnection.Listener {
            override fun onState(state: CarlinkConnection.State, detail: String) {
                Log.i(tag, "conn state=$state $detail")
                listener.onStatus(state.name, detail)
            }

            override fun onAuthDone(phoneIp: String, phoneModel: String, workModes: Int) {
                this@CarlinkController.phoneIp = phoneIp
                listener.onStatus("AUTHED", "认证完成 phone=$phoneModel ip=$phoneIp")
                startWfdSink(phoneIp)
            }

            override fun onDisconnected() {
                stopWfdSink()
                listener.onStatus("DISCONNECTED", "手机已断开")
            }
        })
        connection = conn
        conn.start()
    }

    private fun startWfdSink(host: String) {
        try {
            val ok = SinkNative.start(
                host,
                CarlinkPorts.RTSP,
                config.videoWidth,
                config.videoHeight,
                config.dpi,
                config.fps,
                /* uibcEnc */ false,
                config.supportLowLatency,
                config.supportHevc,
            )
            Log.i(tag, "SinkNative.start(host=$host) -> $ok")
            listener.onStatus("WFD", "视频拉流 start=$ok (host=$host:${CarlinkPorts.RTSP})")
        } catch (t: Throwable) {
            Log.e(tag, "SinkNative.start failed", t)
            listener.onStatus("ERROR", "视频拉流启动失败: ${t.message}")
        }
    }

    private fun stopWfdSink() {
        val host = phoneIp ?: return
        runCatching { SinkNative.stop(host) }
        phoneIp = null
    }

    fun stop() {
        started = false
        stopWfdSink()
        connection?.stop()
        connection = null
        SinkNative.unregisterListener()
        videoSink.release()
    }
}
