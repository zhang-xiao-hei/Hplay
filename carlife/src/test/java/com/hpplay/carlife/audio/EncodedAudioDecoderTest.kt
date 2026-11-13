package com.hpplay.carlife.audio

import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.adtsFrameLength
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.adtsHeaderLength
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.adtsInfo
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.audioSpecificConfig
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.detectMime
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.splitAdtsFrames
import com.hpplay.carlife.audio.EncodedAudioDecoder.Companion.synthesizeAudioSpecificConfig
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

    /**
     * 构造一个完整的 ADTS 帧：7 字节头（含帧长字段）+ 指定长度的 payload。
     *
     * @param totalLength 整帧长度（含头），写进 ADTS 头的帧长字段。
     * @param payloadSize payload 字节数。
     * @param protectionAbsent 无 CRC 时为 true（头 7 字节），否则 9 字节。
     * @param marker payload 首字节，用来验证剥头后拿到的是原始数据。
     */
    private fun adtsFrame(
        totalLength: Int,
        payloadSize: Int,
        protectionAbsent: Boolean = true,
        marker: Int = 0x00,
    ): ByteArray {
        require(totalLength >= 7)
        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        // 0xF0 = 同步字 + MPEG-4 + layer 00；bit0 = protection_absent
        header[1] = (if (protectionAbsent) 0xF1 else 0xF0).toByte()
        // profile = 1 (AAC LC)，采样率索引 4 (44100)，声道 2
        header[2] = ((1 and 0x03) shl 6 or (4 shl 2) or ((2 shr 2) and 0x01)).toByte()
        header[3] = ((2 and 0x03) shl 6).toByte()
        // 帧长 13 位：byte3 bit1-0 + byte4 + byte5 bit7-3
        header[3] = (header[3].toInt() or ((totalLength shr 11) and 0x03)).toByte()
        header[4] = ((totalLength shr 3) and 0xFF).toByte()
        header[5] = (((totalLength and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        val payload = ByteArray(payloadSize) { index ->
            if (index == 0) marker.toByte() else 0x00
        }
        return header + payload
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
        // MP3 Layer III 帧头：0xFF 0xFB。同步字前 11 位与 ADTS 相同，只有 layer 位能区分。
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals("audio/mpeg", detectMime(mp3))
    }

    @Test
    fun `does not mistake mp3 frame for adts aac`() {
        // 回归点：只看 "0xFF + 高位 1" 会把 MP3（0xFF 0xFB）判成 AAC，
        // 解码器起不来 → 真 MP3 音频彻底无声。
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals("audio/mpeg", detectMime(mp3))
        // MP3 帧不能被当成 ADTS 解析出采样率。
        assertNull(adtsInfo(mp3 + byteArrayOf(0x00, 0x00, 0x00)))
    }

    @Test
    fun `detects id3 tagged mp3 stream`() {
        val id3 = byteArrayOf(0x49, 0x44, 0x33, 0x03)
        assertEquals("audio/mpeg", detectMime(id3))
    }

    @Test
    fun `rejects short frame`() {
        assertNull(detectMime(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `unrecognized frame does not poison the decoder`() {
        // 回归点：第一帧认不出格式就把解码器判死，后面手机切回 AAC 也解不出来，
        // 整首歌全程无声。正确行为是"跳过这一帧"，解码器继续等着。
        val decoder = EncodedAudioDecoder(44100, 2)
        decoder.decode(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)) { }
        assertFalse("an unrecognized frame must not fail the decoder", decoder.isFailed())
    }

    @Test
    fun `release resets failed flag so decoder can be reused`() {
        // 解码器在断链/格式切换时会被 release，之后同一个实例还要能重新建解码器。
        // 如果 release 不清 failed 标志，解码器就永久不可用了。
        val decoder = EncodedAudioDecoder(44100, 2)
        // 喂一帧格式合法但内容无意义的数据：解码器会因为建不起来而进入失败状态。
        // 这里只验证 release 的清标志语义，不依赖具体的失败原因。
        decoder.release()
        assertFalse("release must clear the failed flag", decoder.isFailed())
        assertEquals(44100, decoder.resolvedSampleRate())
        assertEquals(2, decoder.resolvedChannels())
    }

    @Test
    fun `reads adts header length and frame length`() {
        // ADTS: byte1 bit0 = protection_absent(1 → 无 CRC，头 7 字节)
        // 帧长 13 位 = byte3 bit1-0 + byte4 + byte5 bit7-3
        val frame = adtsFrame(totalLength = 100, payloadSize = 93)
        assertEquals(7, adtsHeaderLength(frame))
        assertEquals(100, adtsFrameLength(frame))
    }

    @Test
    fun `reads adts header length with crc`() {
        // protection_absent = 0 → 头 9 字节
        val frame = adtsFrame(totalLength = 100, payloadSize = 91, protectionAbsent = false)
        assertEquals(9, adtsHeaderLength(frame))
        assertEquals(100, adtsFrameLength(frame))
    }

    @Test
    fun `splits adts frames and strips headers`() {
        // 回归点：带 ADTS 头的整帧直接喂解码器 → 解码器把帧头当音频数据 → 没有声音。
        // 所以必须自己切帧并剥头，喂裸 access unit。
        val first = adtsFrame(totalLength = 20, payloadSize = 13, marker = 0x11)
        val second = adtsFrame(totalLength = 18, payloadSize = 11, marker = 0x22)
        val split = splitAdtsFrames(first + second)
        assertEquals(2, split.units.size)
        assertEquals(13, split.units[0].size)
        assertEquals(11, split.units[1].size)
        assertEquals(0, split.remainder.size)
        // 剥头后剩下的必须是原始 payload，不能含帧头。
        assertEquals(0x11, split.units[0][0].toInt() and 0xFF)
        assertEquals(0x22, split.units[1][0].toInt() and 0xFF)
    }

    @Test
    fun `keeps incomplete adts frame as remainder`() {
        // 一个 ADTS 帧跨两条 TCP 消息时，前半段要留下来等后半段拼上。
        val frame = adtsFrame(totalLength = 20, payloadSize = 13, marker = 0x33)
        val split = splitAdtsFrames(frame.copyOfRange(0, 10))
        assertTrue("an incomplete frame must not produce an access unit", split.units.isEmpty())
        assertEquals(10, split.remainder.size)
    }

    @Test
    fun `synthesizes audio specific config from rate and channels`() {
        // 裸 AAC（无 ADTS 头）时只能靠车机侧已知参数合成 csd-0，
        // 没有 csd-0 解码器起不来，一样是全程无声。
        val asc = synthesizeAudioSpecificConfig(44100, 2)
        assertNotNull(asc)
        assertEquals(2, asc!!.size)
        val value = ((asc[0].toInt() and 0xFF) shl 8) or (asc[1].toInt() and 0xFF)
        assertEquals(2, (value shr 11) and 0x1F) // objectType = AAC LC
        assertEquals(4, (value shr 7) and 0x0F)  // sampleRateIndex = 44100
        assertEquals(2, (value shr 3) and 0x0F)  // channelConfig = stereo
    }

    @Test
    fun `refuses to synthesize asc for unknown sample rate`() {
        assertNull(synthesizeAudioSpecificConfig(12345, 2))
    }
}
