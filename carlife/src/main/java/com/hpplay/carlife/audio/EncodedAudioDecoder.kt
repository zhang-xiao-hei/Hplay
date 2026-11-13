package com.hpplay.carlife.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * 把手机端下发的**编码**音乐帧解成 16-bit PCM。
 *
 * 原实现只认 AUDIO 通道的 `MSG_MEDIA_DATA`(0x30006) 裸 PCM。小米 CarLife+/CarWith 在
 * 部分机型上把音乐放在 `MSG_MEDIA_DATA_ENCODER`(0x30007) / `MSG_AUDIO_AAC`(0x30003)，
 * 甚至先发数据后发格式帧；这些帧过去被整条丢掉，表现就是"有画面、音乐无声"。
 *
 * 支持 ADTS-AAC 与 MP3，解不出来时只记日志，不再把压缩数据当 PCM 写进音轨产生噪声。
 * 解码器按第一帧的同步字惰性创建，所以对纯 PCM 链路零开销。
 */
class EncodedAudioDecoder(
    private val sampleRate: Int,
    private val channels: Int,
) {
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var failed = false
    /** 解码器实际使用的采样率/声道数（可能来自 ADTS 头，与构造入参不同）。 */
    @Volatile private var actualSampleRate = 0
    @Volatile private var actualChannels = 0

    /** 解出一帧 PCM 就回调一次；回调里直接写 AudioTrack。 */
    fun decode(frame: ByteArray, onPcm: (ByteArray) -> Unit) {
        if (failed || frame.isEmpty()) return
        if (codec == null && !start(frame)) return
        val current = codec ?: return
        try {
            val inputIndex = current.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = current.getInputBuffer(inputIndex)
                if (input != null) {
                    input.clear()
                    input.put(frame)
                    current.queueInputBuffer(inputIndex, 0, frame.size, 0, 0)
                } else {
                    current.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            var outputIndex = current.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            while (outputIndex >= 0) {
                if (info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    current.getOutputBuffer(outputIndex)?.let { output ->
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        output.get(pcm)
                        onPcm(pcm)
                    }
                }
                current.releaseOutputBuffer(outputIndex, false)
                outputIndex = current.dequeueOutputBuffer(info, 0)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "decode failed: ${error.javaClass.simpleName}")
            failed = true
            release()
        }
    }

    private fun start(frame: ByteArray): Boolean {
        val mime = detectMime(frame)
        if (mime == null) {
            Log.w(
                TAG,
                "unsupported encoded audio frame: " +
                    frame.take(8).joinToString(" ") {
                        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                    },
            )
            failed = true
            return false
        }
        return try {
            // ADTS 帧头里带着真实的采样率与声道数，必须以它为准：车机侧多半没收到
            // MSG_MEDIA_INIT，兜底值 44100/2 常常和实际不符，配置错了 AAC 解码要么起不来，
            // 要么解出速度不对的音频。
            val adts = if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) adtsInfo(frame) else null
            val rate = adts?.sampleRate ?: sampleRate
            val channelCount = adts?.channelCount ?: channels
            val format = MediaFormat.createAudioFormat(mime, rate, channelCount)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                audioSpecificConfig(frame)?.let {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                }
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder
            actualSampleRate = rate
            actualChannels = channelCount
            Log.i(TAG, "decoder started: $mime ${rate}Hz ${channelCount}ch")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "cannot start $mime decoder: ${error.javaClass.simpleName}")
            failed = true
            false
        }
    }

    /**
     * 解码器实际采用的采样率/声道数。
     *
     * 音轨必须按这个来建：ADTS 头里的真实参数和车机兜底值往往不同，用错采样率播放
     * 会得到"速度不对"的声音。
     */
    fun resolvedSampleRate(): Int = actualSampleRate.takeIf { it > 0 } ?: sampleRate

    fun resolvedChannels(): Int = actualChannels.takeIf { it > 0 } ?: channels

    /** 解码器是否已彻底失败（帧格式不支持，或解码器起不来）。 */
    fun isFailed(): Boolean = failed

    fun release() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        // 解码器释放后要能重新建：failed 只表示"这一轮编码流解不了"，
        // 不该让解码器在释放后永久不可用（断链重连、格式切换都会走 release）。
        failed = false
        actualSampleRate = 0
        actualChannels = 0
    }

    companion object {
        private const val TAG = "carlife-audio-dec"
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L

        /** ADTS 采样率频率表（索引 = ADTS 头的 sampling_frequency_index）。 */
        private val AAC_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350, 0, 0, 0,
        )

        /** ADTS 帧头解出来的真实参数。 */
        data class AdtsInfo(val sampleRate: Int, val channelCount: Int)

        /**
         * 解析 ADTS 帧头里的采样率与声道数。
         *
         * 布局：byte2 的 bit7-6=profile，bit5-2=sampling_frequency_index，bit0=channel_config 的高位；
         * byte3 的 bit7-6=channel_config 的低位。
         */
        fun adtsInfo(frame: ByteArray): AdtsInfo? {
            if (frame.size < 7) return null
            val b0 = frame[0].toInt() and 0xFF
            val b1 = frame[1].toInt() and 0xFF
            if (!(b0 == 0xFF && (b1 and 0xF0) == 0xF0)) return null
            val b2 = frame[2].toInt() and 0xFF
            val b3 = frame[3].toInt() and 0xFF
            val sampleIndex = (b2 shr 2) and 0x0F
            if (sampleIndex >= AAC_SAMPLE_RATES.size) return null
            val rate = AAC_SAMPLE_RATES[sampleIndex]
            if (rate <= 0) return null
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            if (channelConfig <= 0) return null
            return AdtsInfo(rate, channelConfig)
        }

        /** ADTS 同步字优先；MP3 帧头 / ID3 次之。 */
        fun detectMime(frame: ByteArray): String? {
            if (frame.size < 4) return null
            val b0 = frame[0].toInt() and 0xFF
            val b1 = frame[1].toInt() and 0xFF
            val b2 = frame[2].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) return MediaFormat.MIMETYPE_AUDIO_AAC
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return MediaFormat.MIMETYPE_AUDIO_MPEG
            if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return MediaFormat.MIMETYPE_AUDIO_MPEG
            return null
        }

        /** 从 ADTS 头拼 AudioSpecificConfig（csd-0），裸 AAC 帧没有它解码器起不来。 */
        fun audioSpecificConfig(frame: ByteArray): ByteArray? {
            if (frame.size < 7) return null
            val b2 = frame[2].toInt() and 0xFF
            val b3 = frame[3].toInt() and 0xFF
            val profile = (b2 shr 6) and 0x03
            val sampleIndex = (b2 shr 2) and 0x0F
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            if (sampleIndex >= 13 || channelConfig == 0) return null
            val objectType = profile + 1
            val asc = ((objectType shl 11) or (sampleIndex shl 7) or (channelConfig shl 3)) and 0xFFFF
            return byteArrayOf(
                ((asc shr 8) and 0xFF).toByte(),
                (asc and 0xFF).toByte(),
            )
        }
    }
}
