package com.tunesync.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.tunesync.core.dsp.AudioSignal
import java.io.FileNotFoundException
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min

/** Everything decode can produce, including the ways it can legitimately fail. */
sealed interface DecodeResult {
    data class Success(
        val signal: AudioSignal,
        val trackId: String,
        val encoderDelayMs: Float,
        val peaks: WaveformPeaks,
    ) : DecodeResult

    /** Spotify caches, purchased M4P and anything else with a crypto scheme. */
    data object Protected : DecodeResult

    data class Unsupported(val mime: String?) : DecodeResult

    data object NotReadable : DecodeResult

    data class Truncated(
        val signal: AudioSignal,
        val trackId: String,
        val encoderDelayMs: Float,
        val peaks: WaveformPeaks,
        val decodedFraction: Float,
    ) : DecodeResult
}

/** Min/max envelope per column, precomputed so scrubbing never touches audio. */
class WaveformPeaks(val min: FloatArray, val max: FloatArray) {
    val columns: Int get() = min.size
}

object AudioDecoder {

    private const val PEAK_COLUMNS = 2048
    private const val TIMEOUT_US = 10_000L

    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): DecodeResult {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                if (pfd == null) return DecodeResult.NotReadable
                extractor.setDataSource(pfd.fileDescriptor)
            }
        } catch (e: FileNotFoundException) {
            return DecodeResult.NotReadable
        } catch (e: SecurityException) {
            // The persistable grant was revoked, or never taken.
            return DecodeResult.NotReadable
        } catch (e: Exception) {
            return DecodeResult.NotReadable
        }

        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return DecodeResult.Unsupported(null)

            val mime = format.getString(MediaFormat.KEY_MIME)!!

            // A crypto scheme means DRM. Detect it before MediaCodec throws, so the
            // user gets an explanation rather than a codec error.
            if (format.containsKey("crypto-mode") || format.containsKey(MediaFormat.KEY_IS_ADTS).let { false }) {
                return DecodeResult.Protected
            }
            if (extractor.getPsshInfo()?.isNotEmpty() == true) return DecodeResult.Protected

            extractor.selectTrack(trackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            // Encoder delay and padding. Ignoring these offsets every beat map by
            // 20-70 ms against the file, consistently, and it gets blamed on the
            // beat tracker rather than on the decoder.
            val encoderDelayFrames = if (format.containsKey("encoder-delay")) {
                format.getInteger("encoder-delay")
            } else {
                0
            }
            val encoderPaddingFrames = if (format.containsKey("encoder-padding")) {
                format.getInteger("encoder-padding")
            } else {
                0
            }

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                return DecodeResult.Unsupported(mime)
            }

            val mono: FloatArray
            var decodedUs = 0L
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val collected = decodeLoop(
                    extractor, codec, channels, durationUs, onProgress, isCancelled,
                ) { decodedUs = it }
                mono = collected
            } catch (e: MediaCodec.CryptoException) {
                return DecodeResult.Protected
            } catch (e: IllegalStateException) {
                return DecodeResult.Unsupported(mime)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val trimmed = trim(mono, encoderDelayFrames, encoderPaddingFrames)
            val signal = AudioSignal(trimmed, sampleRate)
            val trackId = fingerprint(trimmed, sampleRate)
            val peaks = buildPeaks(trimmed)
            val delayMs = 0f // already removed from the samples

            val expectedFrames = if (durationUs > 0) durationUs * sampleRate / 1_000_000L else 0L
            val fraction = if (expectedFrames > 0) {
                (trimmed.size.toFloat() / expectedFrames).coerceIn(0f, 1f)
            } else {
                1f
            }
            // Trust the decoder over the container: a broken VBR header is common
            // and the decoded length is the truth.
            return if (fraction < 0.95f && expectedFrames > 0) {
                DecodeResult.Truncated(signal, trackId, delayMs, peaks, fraction)
            } else {
                DecodeResult.Success(signal, trackId, delayMs, peaks)
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private inline fun decodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channels: Int,
        durationUs: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        onDecodedUs: (Long) -> Unit,
    ): FloatArray {
        val out = FloatArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var lastProgress = 0f

        while (!sawOutputEos) {
            if (isCancelled()) break

            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex >= 0) {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    appendMono(buffer.order(ByteOrder.nativeOrder()), info.size, channels, out)
                }
                onDecodedUs(info.presentationTimeUs)
                if (durationUs > 0) {
                    val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    if (p - lastProgress > 0.02f) {
                        lastProgress = p
                        onProgress(p)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        return out.toFloatArray()
    }

    private fun appendMono(
        buffer: java.nio.ByteBuffer,
        size: Int,
        channels: Int,
        out: FloatArrayBuilder,
    ) {
        val shorts = buffer.asShortBuffer()
        val count = size / 2
        val frames = count / channels
        var read = 0
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += shorts.get(read++) / 32768f
            out.add(acc / channels)
        }
    }

    private fun trim(x: FloatArray, delayFrames: Int, paddingFrames: Int): FloatArray {
        val start = delayFrames.coerceIn(0, x.size)
        val end = (x.size - paddingFrames).coerceIn(start, x.size)
        return if (start == 0 && end == x.size) x else x.copyOfRange(start, end)
    }

    /**
     * Content hash, so a renamed or moved file reuses its existing beat map and a
     * re-import does not re-analyse. Samples the head, middle and tail rather than
     * the whole track — hashing 40 MB to recognise a file is not worth the wait.
     */
    private fun fingerprint(x: FloatArray, sampleRate: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(8)
        digest.update(buf.putInt(x.size).putInt(sampleRate).array())

        val chunk = min(x.size, 1 shl 16)
        val offsets = intArrayOf(0, (x.size - chunk) / 2, x.size - chunk).filter { it >= 0 }
        val bytes = java.nio.ByteBuffer.allocate(chunk * 4)
        for (off in offsets) {
            bytes.clear()
            for (i in off until off + chunk) bytes.putFloat(x[i])
            digest.update(bytes.array())
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun buildPeaks(x: FloatArray): WaveformPeaks {
        val cols = min(PEAK_COLUMNS, maxOf(1, x.size))
        val min = FloatArray(cols)
        val max = FloatArray(cols)
        val per = maxOf(1, x.size / cols)
        for (c in 0 until cols) {
            val start = c * per
            val end = min(x.size, start + per)
            var lo = 0f
            var hi = 0f
            for (i in start until end) {
                val v = x[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            min[c] = lo
            max[c] = hi
        }
        return WaveformPeaks(min, max)
    }

    fun peakDbfs(x: FloatArray): Float {
        var p = 0f
        for (v in x) {
            val a = abs(v)
            if (a > p) p = a
        }
        return if (p <= 0f) -120f else (20.0 * kotlin.math.log10(p.toDouble())).toFloat()
    }
}

/** Growable float buffer; ArrayList<Float> would box every sample. */
private class FloatArrayBuilder(initial: Int = 1 shl 20) {
    private var data = FloatArray(initial)
    private var size = 0

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}
