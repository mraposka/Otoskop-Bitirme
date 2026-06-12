package com.kou.otoskop.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * ESP32 `/stream` endpoint'inden gelen `multipart/x-mixed-replace`
 * MJPEG akışını okuyup ImageView olarak gösterir.
 *
 * Kritik tasarım kararları:
 * - Üçüncü parti Flutter widget'ı kullanmadık; native parser ile
 *   her chunk'tan JPEG çıkarıyoruz (SOI=FFD8 .. EOI=FFD9 arasındaki bytes).
 * - Stream IO ayrı bir [CoroutineScope]'ta çalışır; View detach olunca iptal.
 * - Hata olursa [onError] callback'i UI'a iletilir.
 */
class MjpegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : androidx.appcompat.widget.AppCompatImageView(context, attrs, defStyleAttr) {

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var streamUrl: String? = null

    /**
     * Aktif HTTP çağrısı. [stop] bunu iptal eder; aksi halde bloklanan
     * `input.read()` coroutine iptaliyle uyanmaz, TCP soketi açık kalır ve
     * ESP'nin tek-iş-parçacıklı sunucusu yayım döngüsünde tıkanır (port 80
     * dahil tüm API cevapsız kalır). Bu yüzden soketi mutlaka kapatıyoruz.
     */
    @Volatile private var call: Call? = null

    /** UI güncellemesi devam ederken gelen kareleri at (gecikme birikimini önler). */
    @Volatile private var postingFrame = false

    var onError: ((Throwable) -> Unit)? = null

    /**
     * Her çözülen JPEG karesi için çağrılır (IO thread'inde). Video kaydı
     * için kullanılır; dinleyici kareyi hemen kopyalamalıdır (bitmap UI'da
     * yeniden kullanılır).
     */
    var onFrame: ((Bitmap) -> Unit)? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // sürekli akan stream
        .build()

    fun start(url: String) {
        if (streamUrl == url && job?.isActive == true) return
        stop()
        streamUrl = url
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope!!.launch { runStream(url) }
    }

    fun stop() {
        // Önce soketi kapat: bloklanan okumayı uyandırır, ESP yayım döngüsünden
        // çıkıp diğer isteklere (status, camera, gps) tekrar cevap verebilir.
        try {
            call?.cancel()
        } catch (e: Exception) {
            // yoksay
        }
        call = null
        job?.cancel()
        scope?.coroutineContext?.get(Job)?.cancel()
        job = null
        scope = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private suspend fun runStream(url: String) {
        var resp: Response? = null
        try {
            val c = client.newCall(Request.Builder().url(url).build())
            call = c
            resp = c.execute()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            val input = resp.body?.byteStream()
                ?: throw RuntimeException("Empty body")
            parseLoop(input)
        } catch (t: Throwable) {
            // İptal (stop/navigasyon) bir hata değil; sadece gerçek
            // hatalarda kullanıcıya bildir.
            if (t !is CancellationException && coroutineContext.isActive) {
                withContext(Dispatchers.Main) { onError?.invoke(t) }
            }
        } finally {
            try {
                resp?.close()
            } catch (e: Exception) {
                // yoksay
            }
        }
    }

    /**
     * JPEG SOI (0xFFD8) marker'ını bulup EOI'ye (0xFFD9) kadar oku.
     * boundary header'larını saymaktansa byte-stream'i tarayıp doğrudan
     * frame çıkarmak daha sağlam.
     */
    private suspend fun parseLoop(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        val frame = ByteArray(1024 * 1024)
        var frameLen = 0
        var inJpeg = false
        var prev = 0

        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            var i = 0
            while (i < n) {
                val b = buf[i].toInt() and 0xFF
                if (!inJpeg) {
                    if (prev == 0xFF && b == 0xD8) {
                        inJpeg = true
                        frameLen = 0
                        frame[frameLen++] = 0xFF.toByte()
                        frame[frameLen++] = 0xD8.toByte()
                    }
                } else {
                    if (frameLen < frame.size) frame[frameLen++] = b.toByte()
                    if (prev == 0xFF && b == 0xD9) {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val bmp = BitmapFactory.decodeByteArray(frame, 0, frameLen, opts)
                        if (bmp != null) {
                            onFrame?.invoke(bmp)
                            if (!postingFrame) {
                                postingFrame = true
                                postBitmap(bmp)
                            }
                        }
                        inJpeg = false
                        frameLen = 0
                    }
                }
                prev = b
                i++
            }
        }
    }

    private suspend fun postBitmap(bmp: Bitmap) {
        try {
            withContext(Dispatchers.Main) { setImageBitmap(bmp) }
        } finally {
            postingFrame = false
        }
    }
}
