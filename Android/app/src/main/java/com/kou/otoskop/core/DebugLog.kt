package com.kou.otoskop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama genelinde paylaşılan debug konsolu tamponu. Hem [TelescopeViewModel]
 * (/status, /move, /calibrate, doğrulama) hem [ObjectsViewModel] (alan tarama)
 * buraya yazar; canlı ekrandaki konsol bunu dinler. Böylece FTDI olmadan da
 * sahada her adımın sonucu görülebilir.
 */
object DebugLog {
    private const val MAX = 80
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(line: String) {
        val stamped = "${clock.format(Date())}  $line"
        _lines.value = (_lines.value + stamped).takeLast(MAX)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
