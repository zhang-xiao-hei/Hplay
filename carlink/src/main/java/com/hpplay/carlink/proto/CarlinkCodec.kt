package com.hpplay.carlink.proto

import com.ucar.databus.proto.UCarProto

/**
 * CarLink 协议体（body）编解码。帧头由 [CarlinkFrame] 处理，这里只负责 protobuf-lite 负载。
 *
 * 顶层信封按通道区分：
 *  - AUTH 通道 → [UCarProto.AuthIndex]（含 auth_request / auth_response / auth_confirm）
 *  - CONTROL 通道 → [UCarProto.ControlIndex]（method 号即字段号，见 proto 注释）
 *  - SENSOR 通道 → [UCarProto.SensorIndex]
 */
object CarlinkCodec {

    // ---- AUTH ----
    fun encodeAuthRequest(req: UCarProto.AuthRequest): ByteArray =
        UCarProto.AuthIndex.newBuilder().setAuthRequest(req).build().toByteArray()

    fun decodeAuthRequest(body: ByteArray): UCarProto.AuthRequest =
        UCarProto.AuthIndex.parseFrom(body).authRequest

    fun encodeAuthResponse(res: UCarProto.AuthResponse): ByteArray =
        UCarProto.AuthIndex.newBuilder().setAuthResponse(res).build().toByteArray()

    fun decodeAuthResponse(body: ByteArray): UCarProto.AuthResponse =
        UCarProto.AuthIndex.parseFrom(body).authResponse

    fun encodeAuthConfirm(confirm: UCarProto.AuthConfirm): ByteArray =
        UCarProto.AuthIndex.newBuilder().setAuthConfirm(confirm).build().toByteArray()

    fun decodeAuthConfirm(body: ByteArray): UCarProto.AuthConfirm =
        UCarProto.AuthIndex.parseFrom(body).authConfirm

    // ---- CONTROL ----
    fun encodeControl(index: UCarProto.ControlIndex): ByteArray = index.toByteArray()

    fun decodeControl(body: ByteArray): UCarProto.ControlIndex =
        UCarProto.ControlIndex.parseFrom(body)

    fun heartbeat(ts: Long): UCarProto.ControlIndex =
        UCarProto.ControlIndex.newBuilder()
            .setHeartbeat(UCarProto.Heartbeat.newBuilder().setTimestamp(ts))
            .build()

    /**
     * 车机 → 手机：当前界面该用日间还是夜间。
     *
     * 对应 ICCOA `ControlIndex` 的字段 15 `notify_switch_day_or_night`（Mode: DAY=1 / NIGHT=2）。
     * 参考 APP（Wcast 互联）把这条称为 `HU_SEND_CARLINK_SWITCH_DAY_NIGHT_INFO`：**由车机判定**，
     * 手机只负责按通知重绘。所以车机必须在连接建立时下发一次、之后每次明暗变化再下发。
     */
    fun dayNightNotify(day: Boolean): UCarProto.ControlIndex =
        UCarProto.ControlIndex.newBuilder()
            .setNotifySwitchDayOrNight(
                UCarProto.NotifySwitchDayOrNight.newBuilder().setMode(
                    if (day) {
                        UCarProto.NotifySwitchDayOrNight.Mode.DAY
                    } else {
                        UCarProto.NotifySwitchDayOrNight.Mode.NIGHT
                    },
                ),
            )
            .build()

    fun portResponse(result: UCarProto.GetPortResponse.Result, port: Int): UCarProto.ControlIndex =
        UCarProto.ControlIndex.newBuilder()
            .setGetPortResponse(
                UCarProto.GetPortResponse.newBuilder().setResult(result).setPort(port),
            )
            .build()

    // ---- SENSOR ----
    fun decodeSensor(body: ByteArray): UCarProto.SensorIndex =
        UCarProto.SensorIndex.parseFrom(body)
}
