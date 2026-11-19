package com.hpplay.carlink.channel

import android.util.Log
import com.hpplay.carlink.proto.CarlinkCodec
import com.hpplay.carlink.proto.CarlinkFrame
import com.hpplay.carlink.proto.CarlinkPorts
import com.hpplay.carlink.proto.ChannelCategory
import com.hpplay.carlink.proto.MsgType
import com.ucar.databus.proto.UCarProto
import java.net.Socket

/**
 * CONTROL 通道（57219）：心跳、端口查询、能力/配置下发、断开通知等。
 *
 * method 号即 ControlIndex 字段号：
 *  1=heartbeat 2=get_port_request 3=get_port_response 24=get_ucar_config_request
 *  25=get_ucar_config_response 23=disconnect 15=notify_switch_day_or_night
 */
class CarlinkControl(
    private val socket: Socket,
    private val config: CarConfig,
    private val onDisconnect: () -> Unit,
    private val encrypt: ((ByteArray) -> ByteArray)? = null,
    private val decrypt: ((ByteArray) -> ByteArray)? = null,
) {
    data class CarConfig(
        val screenWidth: Int = 1280,
        val screenHeight: Int = 720,
        val dpi: Int = 240,
        val videoWidth: Int = 1280,
        val videoHeight: Int = 720,
        val fps: Int = 30,
        val supportP2p: Boolean = true,
        val supportSoftAp: Boolean = true,
        val dataTransMode: Boolean = false,
        val default5gChannel: Int = 149,
        val supportCamera: Boolean = false,
        val supportMic: Boolean = true,
        val supportLowLatency: Boolean = true,
        val supportVoiceWaken: Boolean = true,
        val supportHevc: Boolean = true,
        val sdkVersion: String = "Hplay-CarLink-1.0",
        val workModes: Int = 0,
        val carBrMac: ByteArray = ByteArray(0),
        val vin: String? = null,
    )

    private val tag = "CarlinkControl"
    private var channel: CarlinkChannel? = null

    fun start() {
        val ch = CarlinkChannel("control", socket, ::onFrame, ::onClosed, encrypt, decrypt)
        channel = ch
        ch.start()
        Log.i(tag, "CONTROL channel started")
    }

    private fun onFrame(ch: CarlinkChannel, frame: CarlinkFrame, body: ByteArray) {
        try {
            when (frame.category) {
                ChannelCategory.CONTROL -> handleControl(ch, frame, body)
                else -> Log.w(tag, "unexpected category ${frame.category} on CONTROL")
            }
        } catch (t: Throwable) {
            Log.e(tag, "control frame failed", t)
        }
    }

    private fun handleControl(ch: CarlinkChannel, frame: CarlinkFrame, body: ByteArray) {
        val index = CarlinkCodec.decodeControl(body)
        when (frame.method) {
            1 -> { // heartbeat
                val ts = index.heartbeat.timestamp
                ch.send(ChannelCategory.CONTROL, 1, CarlinkCodec.encodeControl(CarlinkCodec.heartbeat(ts)), MsgType.RES)
            }
            2 -> { // get_port_request
                val type = index.getPortRequest.type
                val port = when (type) {
                    UCarProto.GetPortRequest.ServerType.SENSOR -> CarlinkPorts.SENSOR
                    UCarProto.GetPortRequest.ServerType.AUDIO -> CarlinkPorts.MEDIA
                    UCarProto.GetPortRequest.ServerType.UIBC -> CarlinkPorts.UIBC
                    else -> CarlinkPorts.SENSOR
                }
                val resp = CarlinkCodec.portResponse(UCarProto.GetPortResponse.Result.SUCCESS, port)
                ch.send(ChannelCategory.CONTROL, 3, CarlinkCodec.encodeControl(resp), MsgType.RES)
                Log.i(tag, "get_port_request type=$type -> port=$port")
            }
            24 -> { // get_ucar_config_request -> respond
                ch.send(ChannelCategory.CONTROL, 25, CarlinkCodec.encodeControl(buildConfig()), MsgType.RES)
                Log.i(tag, "sent GetUCarConfigResponse")
            }
            23 -> { // disconnect
                Log.i(tag, "phone requested disconnect")
                onDisconnect()
            }
            else -> Log.d(tag, "control method ${frame.method} (unhandled)")
        }
    }

    private fun buildConfig(): UCarProto.ControlIndex {
        val c = config
        val resp = UCarProto.GetUCarConfigResponse.newBuilder()
            .setCarBrMac(com.google.protobuf.ByteString.copyFrom(c.carBrMac))
            .setScreenWidth(c.screenWidth)
            .setScreenHeight(c.screenHeight)
            .setDpi(c.dpi)
            .setVideoDisplayWidth(c.videoWidth)
            .setVideoDisplayHeight(c.videoHeight)
            .setFps(c.fps)
            .setIsSupportP2P(c.supportP2p)
            .setIsSupportSoftAP(c.supportSoftAp)
            .setIsDataTransMode(c.dataTransMode)
            .setDefault5GChannel(c.default5gChannel)
            .setIsSupportCamera(c.supportCamera)
            .setIsSupportMic(c.supportMic)
            .setIsSupportLowLatencyDecodingMode(c.supportLowLatency)
            .setIsSupportVoiceWaken(c.supportVoiceWaken)
            .setSdkVersion(c.sdkVersion)
            .setWorkModes(c.workModes)
            .setIsSupportHevc(c.supportHevc)
            .apply { c.vin?.let { setVin(it) } }
            .build()
        return UCarProto.ControlIndex.newBuilder().setGetUcarConfigResponse(resp).build()
    }

    private fun onClosed(ch: CarlinkChannel, t: Throwable?) {
        if (t != null) Log.w(tag, "CONTROL closed: ${t.message}")
    }

    fun sendHeartbeat() {
        channel?.send(ChannelCategory.CONTROL, 1, CarlinkCodec.encodeControl(CarlinkCodec.heartbeat(System.currentTimeMillis() / 1000)))
    }

    /**
     * 通知手机切换日间/夜间（ControlIndex 字段 15）。
     *
     * 参考 APP 里这条叫 `HU_SEND_CARLINK_SWITCH_DAY_NIGHT_INFO`：车机判定明暗、手机照着重绘。
     * 之前本模块只在 GetUCarConfigResponse 里回了静态能力，**从不发**这条 —— 于是手机端
     * 永远停在它自己的默认主题上，车机切深色/进隧道都没反应。
     *
     * @return false 表示 CONTROL 通道还没建立（握手未完成），调用方可稍后重试。
     */
    fun sendDayNight(day: Boolean): Boolean {
        val ch = channel ?: return false
        val sent = runCatching {
            ch.send(
                ChannelCategory.CONTROL,
                METHOD_SWITCH_DAY_NIGHT,
                CarlinkCodec.encodeControl(CarlinkCodec.dayNightNotify(day)),
                MsgType.SEND,
            )
        }.isSuccess
        Log.i(tag, "notify day/night -> ${if (day) "DAY" else "NIGHT"} sent=$sent")
        runCatching {
            com.hpplay.SessionLogger.log("carlink", "notify day/night ${if (day) "DAY" else "NIGHT"}")
        }
        return sent
    }

    fun close() = channel?.close() ?: Unit

    companion object {
        /** ControlIndex 字段 15：notify_switch_day_or_night（method 号即字段号）。 */
        private const val METHOD_SWITCH_DAY_NIGHT = 15
    }
}
