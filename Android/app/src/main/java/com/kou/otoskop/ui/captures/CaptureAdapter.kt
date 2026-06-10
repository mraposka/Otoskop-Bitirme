package com.kou.otoskop.ui.captures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kou.otoskop.data.capture.CaptureItem
import com.kou.otoskop.databinding.ItemCaptureBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CaptureAdapter(
    private val fileResolver: (CaptureItem) -> File,
    private val onOpen: (CaptureItem) -> Unit,
    private val onDelete: (CaptureItem) -> Unit,
) : ListAdapter<CaptureItem, CaptureAdapter.VH>(DIFF) {

    private val ioPool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCaptureBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemCaptureBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: CaptureItem) {
            b.name.text = item.targetName ?: "İsimsiz"

            val typeLabel = if (item.type == "video") "Video" else "Foto"
            b.meta.text = buildString {
                append(typeLabel)
                if (item.type == "video") {
                    item.fps?.let { append(" · ${it.toInt()} fps") }
                    item.durationSec?.let { append(" · %.1f sn".format(it)) }
                }
            }

            val az = item.azimuth?.let { "Az %.1f°".format(it) } ?: "Az —"
            val alt = item.altitude?.let { "Alt %.1f°".format(it) } ?: "Alt —"
            b.azalt.text = "$az  $alt · ${timeFmt.format(Date(item.capturedAt))}"

            b.playBadge.visibility = if (item.type == "video") View.VISIBLE else View.GONE
            b.aiBadge.visibility = if (item.aiVerified) View.VISIBLE else View.GONE

            b.deleteBtn.setOnClickListener { onDelete(item) }
            b.root.setOnClickListener { onOpen(item) }

            // Thumbnail (arka planda; yanlış geri-dönüşümü tag ile engelle)
            b.thumb.setImageDrawable(null)
            b.thumb.tag = item.id
            val file = fileResolver(item)
            ioPool.execute {
                val bmp = runCatching { thumbnail(file, item.type) }.getOrNull()
                main.post {
                    if (b.thumb.tag == item.id) {
                        if (bmp != null) b.thumb.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun thumbnail(file: File, type: String): Bitmap? {
        if (!file.exists()) return null
        return if (type == "video") {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                r.getFrameAtTime(0)
            } finally {
                runCatching { r.release() }
            }
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            val target = 256
            while (opts.outWidth / sample > target || opts.outHeight / sample > target) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CaptureItem>() {
            override fun areItemsTheSame(a: CaptureItem, b: CaptureItem) = a.id == b.id
            override fun areContentsTheSame(a: CaptureItem, b: CaptureItem) = a == b
        }
    }
}
