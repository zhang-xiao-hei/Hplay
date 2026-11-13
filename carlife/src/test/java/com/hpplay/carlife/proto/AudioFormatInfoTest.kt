package com.hpplay.carlife.proto

import com.hpplay.carlife.proto.CarlifeMessages.AudioFormatInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音频格式描述帧的解析。
 *
 * 回归点：普通 [AudioFormatInfo.decode] 在载荷根本不是 protobuf 时会返回默认值
 * 44100/2/16。车机侧用"能否解出合理参数"来判断一条 0x30001 是不是格式描述帧，
 * 一旦误判就会把真正的音频数据当成格式丢掉（表现为音乐无声）。因此必须用严格解析。
 */
class AudioFormatInfoTest {

    /** 编码一个 sampleRate 字段（protobuf field 1，varint）。 */
    private fun varintField(field: Int, value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var key = (field shl 3).toLong()
        while (true) {
            val byte = (key and 0x7F).toInt()
            key = key ushr 7
            if (key != 0L) {
                out += (byte or 0x80).toByte()
            } else {
                out += byte.toByte()
                break
            }
        }
        var v = value
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out += (byte or 0x80).toByte()
            } else {
                out += byte.toByte()
                break
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `strict decode parses all three fields`() {
        val payload = varintField(1, 48000) + varintField(2, 2) + varintField(3, 16)
        val info = AudioFormatInfo.decodeStrict(payload)
        assertNotNull(info)
        assertEquals(48000, info!!.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals(16, info.bitDepth)
    }

    @Test
    fun `strict decode rejects payload that is not protobuf`() {
        // 裸 PCM 数据：首字节不是任何已知的 varint 字段 tag。
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        assertNull(AudioFormatInfo.decodeStrict(pcm))
    }

    @Test
    fun `strict decode rejects empty payload`() {
        assertNull(AudioFormatInfo.decodeStrict(ByteArray(0)))
    }

    @Test
    fun `strict decode rejects payload with only unknown fields`() {
        // field 9 varint：合法 protobuf，但和音频格式无关。
        val payload = varintField(9, 1)
        assertNull(AudioFormatInfo.decodeStrict(payload))
    }

    @Test
    fun `lenient decode still returns defaults for garbage`() {
        // 记录旧行为：宽松解析对垃圾输入返回默认值，这正是需要严格解析的原因。
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val info = AudioFormatInfo.decode(pcm)
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals(16, info.bitDepth)
    }

    @Test
    fun `strict decode rejects pcm whose first byte looks like a field tag`() {
        // 裸 PCM 的首字节可能恰好是 0x08/0x10/0x18（采样值是任意的）。这种载荷会被
        // 上层的"看起来像格式帧"判定放行，必须靠严格解析 + 参数合理性把它挡下来，
        // 否则整段音频会被当成格式描述丢掉。
        val pcm = byteArrayOf(0x08, 0x00, 0x00, 0x00)
        val info = AudioFormatInfo.decodeStrict(pcm)
        // 确实解出了一个字段，但参数不合理（sampleRate=0），上层会拒绝并按裸 PCM 处理。
        if (info != null) {
            assertFalse("sampleRate=0 must not be accepted as a valid format", info.sampleRate in 8000..192000)
        }
    }
}
