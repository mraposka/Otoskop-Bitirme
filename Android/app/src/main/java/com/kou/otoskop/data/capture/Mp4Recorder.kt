package com.kou.otoskop.data.capture

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gelen Bitmap karelerini gerçek zamanlı olarak H.264/MP4 dosyasına yazar.
 * ESP MJPEG akışından çözülen kareler [encodeFrame] ile beslenir.
 *
 * Cihaz uyumluluğu için YUV plane API yerine NV12/I420 byte buffer kullanılır
 * (getInputImage birçok cihazda siyah kare üretir).
 */
class Mp4Recorder(
    private val outFile: File,
    private val fps: Int = 15,
    private val bitRate: Int = 4_000_000,
) {
    private companion object {
        const val TAG = "Mp4Recorder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var width = 0
    private var height = 0
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val frameCount = AtomicInteger(0)
    @Volatile private var started = false
    @Volatile private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var startMs = 0L

    val isRecording: Boolean get() = started && !failed

    private fun ensureStarted(w: Int, h: Int) {
        if (started || failed) return
        try {
            width = w and 1.inv()
            height = h and 1.inv()
            colorFormat = pickYuvColorFormat()

            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            startMs = System.currentTimeMillis()
            started = true
            Log.d(TAG, "started ${width}x$height fmt=$colorFormat")
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            failed = true
            releaseQuietly()
        }
    }

    fun encodeFrame(bitmap: Bitmap) {
        if (failed) return
        val copy = toSoftwareArgb(bitmap) ?: return
        val ok = runCatching {
            worker.execute {
                if (failed) {
                    copy.recycle()
                    return@execute
                }
                ensureStarted(copy.width, copy.height)
                if (!started) {
                    copy.recycle()
                    return@execute
                }
                try {
                    feed(copy)
                    drain(endOfStream = false)
                } catch (t: Throwable) {
                    Log.e(TAG, "encodeFrame failed", t)
                    failed = true
                } finally {
                    copy.recycle()
                }
            }
        }.isSuccess
        if (!ok) copy.recycle()
    }

    private fun feed(src: Bitmap) {
        val c = codec ?: return
        val index = c.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val input = c.getInputBuffer(index) ?: return

        val frame = if (src.width == width && src.height == height) {
            src
        } else {
            Bitmap.createScaledBitmap(src, width, height, true)
        }
        val yuv = bitmapToYuv(frame, width, height, colorFormat)
        if (frame !== src) frame.recycle()

        input.clear()
        input.put(yuv)
        c.queueInputBuffer(index, 0, yuv.size, ptsUs(), 0)
        frameCount.incrementAndGet()
    }

    private fun ptsUs(): Long = frameCount.get() * 1_000_000L / fps

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val encoded = c.getOutputBuffer(outIndex)
                    if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    fun stop(): Result {
        val durationSec = if (startMs > 0) (System.currentTimeMillis() - startMs) / 1000.0 else 0.0
        val frames = frameCount.get()
        val task = java.util.concurrent.FutureTask {
            try {
                if (started && !failed) {
                    val c = codec!!
                    val index = c.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        c.queueInputBuffer(
                            index, 0, 0, ptsUs(),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                    drain(endOfStream = true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "stop drain failed", t)
                failed = true
            } finally {
                releaseQuietly()
            }
        }
        worker.execute(task)
        runCatching { task.get() }
        worker.shutdown()

        val ok = !failed && outFile.exists() && outFile.length() > 0 && frames > 0
        if (!ok) runCatching { outFile.delete() }
        return Result(ok, frames, durationSec, fps.toDouble())
    }

    private fun releaseQuietly() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
    }

    data class Result(val ok: Boolean, val frames: Int, val durationSec: Double, val fps: Double)
}

private fun toSoftwareArgb(bitmap: Bitmap): Bitmap? {
    val cfg = bitmap.config ?: Bitmap.Config.ARGB_8888
    return if (cfg == Bitmap.Config.ARGB_8888 && bitmap.config != Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
}

private fun pickYuvColorFormat(): Int {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    for (info in list.codecInfos) {
        if (!info.isEncoder) continue
        for (type in info.supportedTypes) {
            if (!type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) continue
            val caps = info.getCapabilitiesForType(type)
            for (fmt in caps.colorFormats) {
                if (fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return fmt
            }
            for (fmt in caps.colorFormats) {
                if (fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return fmt
            }
        }
    }
    return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
}

private fun bitmapToYuv(
    src: Bitmap,
    width: Int,
    height: Int,
    colorFormat: Int,
): ByteArray = when (colorFormat) {
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> argbToI420(src, width, height)
    else -> argbToNV12(src, width, height)
}

/** NV12: Y düzlemi + birleşik UV (U,V,U,V…). */
private fun argbToNV12(src: Bitmap, width: Int, height: Int): ByteArray {
    val argb = IntArray(width * height)
    src.getPixels(argb, 0, width, 0, 0, width, height)
    val frameSize = width * height
    val yuv = ByteArray(frameSize + frameSize / 2)
    var yIndex = 0
    var uvIndex = frameSize
    for (j in 0 until height) {
        for (i in 0 until width) {
            val p = argb[j * width + i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            yuv[yIndex++] = y.coerceIn(0, 255).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
            }
        }
    }
    return yuv
}

/** I420: Y + U + V ayrı düzlemler. */
private fun argbToI420(src: Bitmap, width: Int, height: Int): ByteArray {
    val argb = IntArray(width * height)
    src.getPixels(argb, 0, width, 0, 0, width, height)
    val ySize = width * height
    val uvSize = ySize / 4
    val yuv = ByteArray(ySize + uvSize * 2)
    var yIndex = 0
    var uIndex = ySize
    var vIndex = ySize + uvSize
    for (j in 0 until height) {
        for (i in 0 until width) {
            val p = argb[j * width + i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            yuv[yIndex++] = y.coerceIn(0, 255).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                yuv[vIndex++] = v.coerceIn(0, 255).toByte()
            }
        }
    }
    return yuv
}
