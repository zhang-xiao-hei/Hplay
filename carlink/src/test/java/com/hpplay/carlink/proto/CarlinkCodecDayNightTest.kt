package com.hpplay.carlink.proto

import com.ucar.databus.proto.UCarProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机 → 手机的「日夜切换」通知（CONTROL 字段 15）。
 *
 * 参考 APP（Wcast 互联 / ICCOA）把这条命名为 `HU_SEND_CARLINK_SWITCH_DAY_NIGHT_INFO`：
 * 由车机判定当前应该是日间还是夜间，手机照着重绘。这里只锚定两件事：
 *  1. 字段号必须是 15（CONTROL 通道 method 号 == ControlIndex 字段号）；
 *  2. DAY/NIGHT 的枚举值必须是 1/2，不能写反。
 */
class CarlinkCodecDayNightTest {

    @Test
    fun fieldNumber_isFifteen() {
        assertEquals(15, UCarProto.ControlIndex.NOTIFY_SWITCH_DAY_OR_NIGHT_FIELD_NUMBER)
    }

    @Test
    fun dayModeRoundTrip() {
        val bytes = CarlinkCodec.encodeControl(CarlinkCodec.dayNightNotify(day = true))
        val parsed = CarlinkCodec.decodeControl(bytes)
        assertTrue(parsed.hasNotifySwitchDayOrNight())
        assertEquals(
            UCarProto.NotifySwitchDayOrNight.Mode.DAY,
            parsed.notifySwitchDayOrNight.mode,
        )
        assertEquals(1, UCarProto.NotifySwitchDayOrNight.Mode.DAY.number)
    }

    @Test
    fun nightModeRoundTrip() {
        val bytes = CarlinkCodec.encodeControl(CarlinkCodec.dayNightNotify(day = false))
        val parsed = CarlinkCodec.decodeControl(bytes)
        assertTrue(parsed.hasNotifySwitchDayOrNight())
        assertEquals(
            UCarProto.NotifySwitchDayOrNight.Mode.NIGHT,
            parsed.notifySwitchDayOrNight.mode,
        )
        assertEquals(2, UCarProto.NotifySwitchDayOrNight.Mode.NIGHT.number)
    }
}
