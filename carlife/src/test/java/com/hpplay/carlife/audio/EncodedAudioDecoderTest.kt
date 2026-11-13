package com.hpplay.carlife.audio

import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.adtsInfo
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.audioSpecificConfig
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.detectMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码音频帧头解析。
 *
 * 回归点：小米 CarLife+/CarWith 把音乐放在 0x30007 / 0x30003 这类编码帧上。解码器必须
 * 认出 ADTS 同步字，并从帧头取回**真实**采样率与声道数 —— 车机侧的兜底值（44100/2）
 * 往往和实际不符，用错采样率播放会得到速度不对的声音。
 */
class EncodedAudioDecoderTest {

    /** 构造一个 ADTS 帧头：syncword + profile + 采样率索引 + 声道配置。 */
    private fun adtsHeader(
        sampleRateIndex: Int,
        channelConfig: Int,
        profile: Int = 1,
    ): ByteArray {
        val b2 = ((profile and 0x03) shl 6) or ((sampleRateIndex and 0x0F) shl 2) or
            ((channelConfig shr 2) and 0x01)
        val b3 = (channelConfig and 0x03) shl 6
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            b2.toByte(),
            b3.toByte(),
            0x00,
            0x00,
            0x00,
        )
    }

    @Test
    fun `detects adts aac`() {
        val frame = adtsHeader(sampleRateIndex = 4, channelConfig = 2)
        assertEquals("audio/mp4a-latm", detectMime(frame))
    }

    @Test
    fun `reads sample rate and channels from adts header`() {
        // index 4 = 44100, channelConfig 2 = stereo
        val info = adtsInfo(adtsHeader(sampleRateIndex = 4, channelConfig = 2))
        assertNotNull(info)
        assertEquals(44100, info!!.sampleRate)
        assertEquals(2, info.channelCount)
    }

    @Test
    fun `reads 48000 mono from adts header`() {
        // index 3 = 48000, channelConfig 1 = mono
        val info = adtsInfo(adtsHeader(sampleRateIndex = 3, channelConfig = 1))
        assertNotNull(info)
        assertEquals(48000, info!!.sampleRate)
        assertEquals(1, info.channelCount)
    }

    @Test
    fun `reads 16000 mono from adts header`() {
        // index 8 = 16000
        val info = adtsInfo(adtsHeader(sampleRateIndex = 8, channelConfig = 1))
        assertNotNull(info)
        assertEquals(16000, info!!.sampleRate)
        assertEquals(1, info.channelCount)
    }

    @Test
    fun `rejects non adts payload`() {
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
        assertNull(adtsInfo(pcm))
    }

    @Test
    fun `rejects adts with invalid sample rate index`() {
        // index 13/14/15 是保留值
        val info = adtsInfo(adtsHeader(sampleRateIndex = 13, channelConfig = 2))
        assertNull(info)
    }

    @Test
    fun `rejects adts with zero channel config`() {
        val info = adtsInfo(adtsHeader(sampleRateIndex = 4, channelConfig = 0))
        assertNull(info)
    }

    @Test
    fun `builds audio specific config from adts header`() {
        // AAC LC (profile 1) / 44100 (index 4) / stereo
        val asc = audioSpecificConfig(adtsHeader(sampleRateIndex = 4, channelConfig = 2))
        assertNotNull(asc)
        assertEquals(2, asc!!.size)
        // objectType=2(AAC LC), sampleIndex=4, channelConfig=2
        val value = ((asc[0].toInt() and 0xFF) shl 8) or (asc[1].toInt() and 0xFF)
        val objectType = (value shr 11) and 0x1F
        val sampleIndex = (value shr 7) and 0x0F
        val channelConfig = (value shr 3) and 0x0F
        assertEquals(2, objectType)
        assertEquals(4, sampleIndex)
        assertEquals(2, channelConfig)
    }

    @Test
    fun `detects mp3 frame header`() {
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals("audio/mpeg", detectMime(mp3))
    }

    @Test
    fun `rejects short frame`() {
        assertNull(detectMime(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `release resets failed flag so decoder can be reused`() {
        // 解码器在断链/格式切换时会被 release，之后同一个实例还要能重新建解码器。
        // 如果 release 不清 failed 标志，解码器就永久不可用了。
        val decoder = EncodedAudioDecoder(44100, 2)
        // 用一坨垃圾数据触发"无法识别帧格式"的失败路径。
        decoder.decode(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)) { }
        assertTrue("garbage frame must mark the decoder failed", decoder.isFailed())

        decoder.release()
        assertFalse("release must clear the failed flag", decoder.isFailed())
        assertEquals(44100, decoder.resolvedSampleRate())
        assertEquals(2, decoder.resolvedChannels())
    }
}
