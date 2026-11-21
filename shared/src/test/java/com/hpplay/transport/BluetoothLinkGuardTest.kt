package com.hpplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 蓝牙链路守卫的行为契约：并发去重、失败退避、同一世代只播报一次。
 * 全部用显式时间戳，不依赖真实时钟。
 */
class BluetoothLinkGuardTest {

    private val phone = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        BluetoothLinkGuard.reset()
    }

    @Test
    fun secondConcurrentAttemptIsRejected() {
        assertEquals(BluetoothLinkGuard.Begin.STARTED, BluetoothLinkGuard.beginConnect(phone, 0L))
        assertEquals(BluetoothLinkGuard.Begin.IN_FLIGHT, BluetoothLinkGuard.beginConnect(phone, 10L))
    }

    @Test
    fun failureEntersBackoffThatGrows() {
        BluetoothLinkGuard.beginConnect(phone, 0L)
        BluetoothLinkGuard.markFailed(phone, 0L)
        assertEquals(BluetoothLinkGuard.Begin.COOLING_DOWN, BluetoothLinkGuard.beginConnect(phone, 500L))
        assertEquals(1_000L, BluetoothLinkGuard.cooldownRemainingMs(phone, 0L))
        // 退避到期可再次尝试
        assertEquals(BluetoothLinkGuard.Begin.STARTED, BluetoothLinkGuard.beginConnect(phone, 1_000L))
        BluetoothLinkGuard.markFailed(phone, 1_000L)
        assertEquals(BluetoothLinkGuard.Begin.COOLING_DOWN, BluetoothLinkGuard.beginConnect(phone, 2_500L))
        assertEquals(2_000L, BluetoothLinkGuard.cooldownRemainingMs(phone, 1_000L))
    }

    @Test
    fun backoffIsCappedAtEightSeconds() {
        var now = 0L
        repeat(6) {
            BluetoothLinkGuard.beginConnect(phone, now)
            BluetoothLinkGuard.markFailed(phone, now)
            now += 20_000L
        }
        BluetoothLinkGuard.beginConnect(phone, now)
        BluetoothLinkGuard.markFailed(phone, now)
        assertEquals(8_000L, BluetoothLinkGuard.cooldownRemainingMs(phone, now))
    }

    @Test
    fun duplicateConnectedCallbackIsAnnouncedOnlyOnce() {
        BluetoothLinkGuard.beginConnect(phone, 0L)
        assertTrue(BluetoothLinkGuard.markConnected(phone, 0L))
        // 窗口内的重复回调一律吞掉（有的 ROM 会连发两三次 STATE_CONNECTED）
        assertFalse(BluetoothLinkGuard.markConnected(phone, 1_200L))
        assertFalse(BluetoothLinkGuard.markConnected(phone, 4_000L))
        // 超过窗口的又一次连接回调说明链路真的重建过，允许再播报一次
        assertTrue(BluetoothLinkGuard.markConnected(phone, 20_000L))
        assertTrue(BluetoothLinkGuard.isConnected(phone))
    }

    @Test
    fun reconnectAfterRealDisconnectAnnouncesAgain() {
        BluetoothLinkGuard.beginConnect(phone, 0L)
        assertTrue(BluetoothLinkGuard.markConnected(phone, 0L))
        BluetoothLinkGuard.markDisconnected(phone)
        BluetoothLinkGuard.beginConnect(phone, 10_000L)
        assertTrue(BluetoothLinkGuard.markConnected(phone, 10_000L))
    }

    @Test
    fun abandonedAttemptDoesNotBlockNextOne() {
        BluetoothLinkGuard.beginConnect(phone, 0L)
        BluetoothLinkGuard.markAbandoned(phone)
        assertEquals(BluetoothLinkGuard.Begin.STARTED, BluetoothLinkGuard.beginConnect(phone, 100L))
        assertFalse(BluetoothLinkGuard.isCoolingDown(phone, 100L))
    }

    @Test
    fun differentDevicesAreTrackedSeparately() {
        val other = "11:22:33:44:55:66"
        BluetoothLinkGuard.beginConnect(phone, 0L)
        BluetoothLinkGuard.markFailed(phone, 0L)
        assertEquals(BluetoothLinkGuard.Begin.STARTED, BluetoothLinkGuard.beginConnect(other, 100L))
    }
}
