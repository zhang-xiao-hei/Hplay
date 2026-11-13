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
 *
 * ## 为什么自己拆 ADTS 帧、剥掉帧头
 *
 * AAC 解码器要的是**裸 access unit**（不含 ADTS 头），格式信息放在 `csd-0` 里。
 * 如果把带 ADTS 头的整帧直接喂进去、又不声明 `KEY_IS_ADTS=1`，解码器会把 7 字节帧头
 * 当成音频数据 —— 表现是**不报错但一点声音都没有**，恰恰就是"连上了却全程无声"。
 *
 * 所以这里按 ADTS 头里的帧长字段把载荷切成完整帧、逐帧剥头后再喂。这条契约
 * （`csd-0` + 裸 access unit）是所有 Android AAC 解码器都保证支持的，不依赖厂商实现
 * 自己对 `KEY_IS_ADTS` 的解释差异。
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
    /** 当前解码器对应的帧格式。 */
    @Volatile private var mime: String? = null
    /** 一个 ADTS 帧跨两条消息时的残余字节（TCP 分片）。 */
    private var pending = ByteArray(0)
    /** 连续认不出格式的帧数：只用于限频日志，不中断解码。 */
    private var unrecognized = 0

    /** 解出一帧 PCM 就回调一次；回调里直接写 AudioTrack。 */
    fun decode(frame: ByteArray, onPcm: (ByteArray) -> Unit) {
        decode(frame, null, onPcm)
    }

    /**
     * 解一帧编码音频。
     *
     * @param expectedMime 调用方按消息号已经确定的格式（如 `MSG_AUDIO_AAC` 就是 AAC）。
     *   载荷没有同步字时用它兜底 —— 有些机型发的是不带 ADTS 头的裸 AAC。
     */
    fun decode(frame: ByteArray, expectedMime: String?, onPcm: (ByteArray) -> Unit) {
        if (failed || frame.isEmpty()) return
        val detected = detectMime(frame)
        val useMime = detected ?: expectedMime
        if (useMime == null) {
            // 认不出格式的帧**不能**毒死解码器：只跳过这一帧。
            //
            // 之前这里直接 failed=true，于是第一帧 ADPCM（或任何非 AAC/MP3 的载荷）就让
            // 解码器永久失效 —— 后面手机再切回 AAC 也解不出来，整首歌全程无声。
            unrecognized++
            if (unrecognized == 1 || unrecognized % 50 == 0) {
                Log.w(
                    TAG,
                    "unrecognized encoded audio frame #$unrecognized: " +
                        frame.take(8).joinToString(" ") {
                            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                        },
                )
            }
            return
        }
        unrecognized = 0
        if (codec == null && !start(frame, useMime, detected != null)) return
        val current = codec ?: return
        try {
            for (unit in accessUnits(frame, useMime, detected != null)) {
                val inputIndex = current.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = current.getInputBuffer(inputIndex)
                    if (input != null) {
                        input.clear()
                        input.put(unit)
                        current.queueInputBuffer(inputIndex, 0, unit.size, 0, 0)
                    } else {
                        current.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
                drain(current, onPcm)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "decode failed: ${error.javaClass.simpleName}")
            failed = true
            releaseCodec()
        }
    }

    /** 把输出缓冲里的 PCM 取出来交给调用方。 */
    private fun drain(codec: MediaCodec, onPcm: (ByteArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
        while (outputIndex >= 0) {
            if (info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            ) {
                codec.getOutputBuffer(outputIndex)?.let { output ->
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size)
                    output.get(pcm)
                    onPcm(pcm)
                }
            }
            codec.releaseOutputBuffer(outputIndex, false)
            outputIndex = codec.dequeueOutputBuffer(info, 0)
        }
    }

    /**
     * 取出这一帧里的 access unit 列表。
     *
     * - MP3：整块就是一帧，带帧头喂给解码器（MP3 解码器自己认帧头）。
     * - AAC + ADTS：按帧长字段切分，逐帧剥掉 ADTS 头。
     * - AAC 无 ADTS 头：整块就是一个裸 access unit，直接喂。
     */
    private fun accessUnits(frame: ByteArray, useMime: String, hasSync: Boolean): List<ByteArray> {
        if (useMime != MediaFormat.MIMETYPE_AUDIO_AAC || !hasSync) {
            return listOf(frame)
        }
        val buf = if (pending.isEmpty()) frame else pending + frame
        val split = splitAdtsFrames(buf)
        // 尾部不完整的部分留到下一条消息再拼。
        pending = split.remainder
        if (split.units.isEmpty() && pending.size > MAX_PENDING) {
            // 一直凑不出完整帧：多半根本不是 ADTS，别把内存耗在这上面。
            pending = ByteArray(0)
        }
        return split.units
    }

    private fun start(frame: ByteArray, useMime: String, hasSync: Boolean): Boolean {
        // ADTS 帧头里带着真实的采样率与声道数，必须以它为准：车机侧多半没收到
        // MSG_MEDIA_INIT，兜底值 44100/2 常常和实际不符，配置错了 AAC 解码要么起不来，
        // 要么解出速度不对的音频。
        val adts = if (useMime == MediaFormat.MIMETYPE_AUDIO_AAC && hasSync) adtsInfo(frame) else null
        val rate = adts?.sampleRate ?: sampleRate
        val channelCount = adts?.channelCount ?: channels
        return try {
            val format = MediaFormat.createAudioFormat(useMime, rate, channelCount)
            if (useMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                // csd-0 放 AudioSpecificConfig：裸 AAC 解码必需，缺了它解码器起不来。
                val asc = if (hasSync) {
                    audioSpecificConfig(frame)
                } else {
                    synthesizeAudioSpecificConfig(rate, channelCount)
                }
                asc?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            }
            val decoder = MediaCodec.createDecoderByType(useMime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder
            mime = useMime
            actualSampleRate = rate
            actualChannels = channelCount
            Log.i(TAG, "decoder started: $useMime ${rate}Hz ${channelCount}ch")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "cannot start $useMime decoder: ${error.javaClass.simpleName}")
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

    /** 解码器是否已彻底失败（解码器起不来，或解码过程中抛异常）。 */
    fun isFailed(): Boolean = failed

    /** 彻底释放（断链、格式切换等场景）：调用后同一个实例还能重新建解码器。 */
    fun release() {
        releaseCodec()
        // 解码器释放后要能重新建：failed 只表示"这一轮编码流解不了"，
        // 不该让解码器在释放后永久不可用（断链重连、格式切换都会走 release）。
        failed = false
        actualSampleRate = 0
        actualChannels = 0
        mime = null
        pending = ByteArray(0)
    }

    /** 只拆解码器，保留失败状态（解码过程中出错时用）。 */
    private fun releaseCodec() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }

    companion object {
        private const val TAG = "carlife-audio-dec"
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
        /** 等待拼接的残余字节上限：超过就认定不是 ADTS，丢弃重来。 */
        private const val MAX_PENDING = 64 * 1024

        /**
         * AAC 的 MIME 名。
         *
         * 会话层按消息号（`MSG_AUDIO_AAC`）就能确定载荷是 AAC，但有些机型发的是
         * **不带 ADTS 头的裸 AAC**：载荷里没有同步字，只能靠消息号把格式告诉解码器，
         * 否则解码器认不出帧，整首歌全程无声。
         */
        const val MIME_AUDIO_AAC = "audio/mp4a-latm"
        /** MP3 的 MIME 名。 */
        const val MIME_AUDIO_MPEG = "audio/mpeg"

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
            // ADTS 要求 layer == 00，否则是 MP3 帧（同步字前 11 位相同）。
            if (!(b0 == 0xFF && (b1 and 0xF6) == 0xF0 && ((b1 shr 1) and 0x03) == 0x00)) return null
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

        /**
         * 判定帧格式。
         *
         * ADTS 与 MP3 的同步字前 11 位都是 0xFFE，只有 layer 位能区分：
         *  - ADTS  要求 layer == 00（byte1 的 bit2-1 = 00）
         *  - MP3   要求 layer == 01（Layer III，byte1 的 bit2-1 = 01）
         * 只看 "0xFF + 高位 1" 会把 MP3 帧（0xFF 0xFB）误判成 AAC，解码器起不来，
         * 结果就是真 MP3 音频彻底没有声音。
         */
        fun detectMime(frame: ByteArray): String? {
            if (frame.size < 4) return null
            val b0 = frame[0].toInt() and 0xFF
            val b1 = frame[1].toInt() and 0xFF
            val b2 = frame[2].toInt() and 0xFF
            // ID3v2 标签头：后面跟的是 MP3 帧流。
            if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return MediaFormat.MIMETYPE_AUDIO_MPEG
            if (b0 != 0xFF) return null
            val layer = (b1 shr 1) and 0x03
            // ADTS: 同步字 0xFFF + layer 必须为 00
            if ((b1 and 0xF6) == 0xF0 && layer == 0x00) return MediaFormat.MIMETYPE_AUDIO_AAC
            // MP3: 同步字 11 位全 1 + Layer III
            if ((b1 and 0xE0) == 0xE0 && layer == 0x01) return MediaFormat.MIMETYPE_AUDIO_MPEG
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

        /**
         * 采样率/声道数 → AudioSpecificConfig。
         *
         * 手机发来的是不带 ADTS 头的裸 AAC 时，帧里没有任何格式信息，只能靠车机侧
         * 已知的采样率/声道数合成 csd-0 —— 没有它解码器起不来，一样是全程无声。
         */
        fun synthesizeAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray? {
            val sampleIndex = AAC_SAMPLE_RATES.indexOfFirst { it == sampleRate }
            if (sampleIndex < 0) return null
            if (channels !in 1..7) return null
            val objectType = 2 // AAC LC
            val asc = ((objectType shl 11) or (sampleIndex shl 7) or (channels shl 3)) and 0xFFFF
            return byteArrayOf(
                ((asc shr 8) and 0xFF).toByte(),
                (asc and 0xFF).toByte(),
            )
        }

        /**
         * ADTS 帧头长度：7 字节（无 CRC）或 9 字节（带 CRC）。
         *
         * byte1 的 bit0 = protection_absent：1 表示没有 CRC，头是 7 字节。
         */
        fun adtsHeaderLength(frame: ByteArray, offset: Int = 0): Int {
            if (offset + 7 > frame.size) return 0
            val b0 = frame[offset].toInt() and 0xFF
            val b1 = frame[offset + 1].toInt() and 0xFF
            if (!(b0 == 0xFF && (b1 and 0xF6) == 0xF0 && ((b1 shr 1) and 0x03) == 0x00)) return 0
            val protectionAbsent = b1 and 0x01
            return if (protectionAbsent == 1) 7 else 9
        }

        /**
         * ADTS 整帧长度（含帧头）：byte3 的 bit1-0 + byte4 + byte5 的 bit7-3，共 13 位。
         */
        fun adtsFrameLength(frame: ByteArray, offset: Int = 0): Int {
            if (offset + 6 > frame.size) return 0
            val b3 = frame[offset + 3].toInt() and 0xFF
            val b4 = frame[offset + 4].toInt() and 0xFF
            val b5 = frame[offset + 5].toInt() and 0xFF
            return ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) shr 5)
        }

        /** ADTS 切帧结果：完整的裸 access unit 列表 + 尾部不完整的残余。 */
        data class AdtsSplit(val units: List<ByteArray>, val remainder: ByteArray)

        /**
         * 把连续的 ADTS 帧切成裸 access unit（剥掉每帧的 ADTS 头）。
         *
         * 为什么要剥头：带 ADTS 头的整帧直接喂解码器、又不声明 `KEY_IS_ADTS=1` 时，
         * 解码器会把 7 字节帧头当成音频数据 —— 不报错，但一点声音都没有。
         *
         * 尾部不足一整帧的部分放进 [AdtsSplit.remainder]，等下一条消息拼上。
         */
        fun splitAdtsFrames(payload: ByteArray): AdtsSplit {
            val units = ArrayList<ByteArray>(1)
            var offset = 0
            while (offset < payload.size) {
                val headerLength = adtsHeaderLength(payload, offset)
                val frameLength = adtsFrameLength(payload, offset)
                if (headerLength == 0 || frameLength < headerLength) break
                if (offset + frameLength > payload.size) break
                units.add(payload.copyOfRange(offset + headerLength, offset + frameLength))
                offset += frameLength
            }
            val remainder = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
            return AdtsSplit(units, remainder)
        }
    }
}
