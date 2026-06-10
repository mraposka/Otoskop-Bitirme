package com.kou.otoskop.data.capture

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gelen Bitmap karelerini gerçek zamanlı olarak H.264/MP4 dosyasına yazar
 * (tarayıcı ve mobil oynatıcı uyumlu). ESP MJPEG akışından çözülen kareler
 * [encodeFrame] ile beslenir; çözünürlük ilk kareye göre sabitlenir.
 *
 * Tasarım: cihaz bağımsız olması için `COLOR_FormatYUV420Flexible` + Codec'in
 * `getInputImage()` plane API'si kullanılır (rowStride/pixelStride'a saygılı).
 * Tüm kodlama tek bir arka plan thread'inde yapılır.
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
    private val frameCount = AtomicInteger(0)
    @Volatile private var started = false
    @Volatile private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var startMs = 0L

    val isRecording: Boolean get() = started && !failed

    /** İlk kareyle çözünürlüğü sabitleyip kodlayıcıyı başlatır. */
    private fun ensureStarted(w: Int, h: Int) {
        if (started || failed) return
        try {
            // H.264 genişlik/yükseklik çift olmalı
            width = w and 1.inv()
            height = h and 1.inv()

            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
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
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            failed = true
            releaseQuietly()
        }
    }

    /** Bir kareyi kodlamaya gönderir (kopyasını alır; çağıran bitmap'i serbest bırakabilir). */
    fun encodeFrame(bitmap: Bitmap) {
        if (failed) return
        // Bitmap'i hemen kopyala (worker'a güvenli geçsin; UI aynı bitmap'i yeniden kullanır)
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val ok = runCatching {
            worker.execute {
                if (failed) { copy.recycle(); return@execute }
                ensureStarted(copy.width, copy.height)
                if (!started) { copy.recycle(); return@execute }
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
        // worker kapandıysa (stop sonrası) kareyi at
        if (!ok) copy.recycle()
    }

    private fun feed(src: Bitmap) {
        val c = codec ?: return
        val index = c.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return // bu kareyi atla (kodlayıcı meşgul)
        val image = c.getInputImage(index) ?: run {
            c.queueInputBuffer(index, 0, 0, ptsUs(), 0)
            return
        }
        // ARGB -> I420 (Y,U,V) plane'lerine yaz
        fillYuv420(src, image)
        val pts = ptsUs()
        c.queueInputBuffer(index, 0, 0, pts, 0)
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

    /**
     * Kaydı bitirir ve sonucu döndürür. result.ok=false ise dosya bozuk/boş.
     */
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

/**
 * ARGB_8888 bitmap'i MediaCodec Image'ının YUV420 plane'lerine (BT.601)
 * rowStride/pixelStride'a saygılı şekilde yazar. Hem planar (I420) hem
 * semi-planar (NV12) düzenleri pixelStride üzerinden desteklenir.
 */
private fun fillYuv420(src: Bitmap, image: android.media.Image) {
    val w = image.width
    val h = image.height
    val argb = IntArray(w * h)
    // Bitmap çözünürlüğü image ile aynı olmayabilir; güvenli tarafta kal
    val bw = src.width.coerceAtMost(w)
    val bh = src.height.coerceAtMost(h)
    src.getPixels(argb, 0, w, 0, 0, bw, bh)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixStride = uPlane.pixelStride
    val vPixStride = vPlane.pixelStride

    for (y in 0 until h) {
        for (x in 0 until w) {
            val p = argb[y * w + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            yBuf.put(y * yRowStride + x, yy.coerceIn(0, 255).toByte())
            if (y % 2 == 0 && x % 2 == 0) {
                val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val cx = x / 2
                val cy = y / 2
                uBuf.put(cy * uRowStride + cx * uPixStride, uu.coerceIn(0, 255).toByte())
                vBuf.put(cy * vRowStride + cx * vPixStride, vv.coerceIn(0, 255).toByte())
            }
        }
    }
}
