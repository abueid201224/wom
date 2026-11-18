package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONObject

object ScannerParser {
    private val URL_CODE_REGEX = Regex("[?&](?:code|item|barcode|sku|inv|invoice)=([^&]+)", RegexOption.IGNORE_CASE)

    fun extractCode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                val obj = JSONObject(trimmed)
                val keys = listOf("itemCode", "code", "barcode", "sku", "invoiceNo", "inv", "id")
                for (k in keys) {
                    val v = obj.optString(k, "").trim()
                    if (v.isNotEmpty()) return cleanString(v)
                }
            }
        }

        if (trimmed.contains("http", ignoreCase = true) || trimmed.contains("?")) {
            val match = URL_CODE_REGEX.find(trimmed)
            if (match != null) {
                return cleanString(match.groupValues[1])
            }
            val lastSegment = trimmed.substringAfterLast('/').substringBefore('?')
            if (lastSegment.isNotBlank()) return cleanString(lastSegment)
        }

        return cleanString(trimmed)
    }

    fun cleanString(raw: String): String {
        return raw.trim().replace(Regex("[\\r\\n\\t]"), "")
    }

    fun stripLeadingZeros(code: String): String {
        val cleaned = cleanString(code)
        val stripped = cleaned.trimStart('0')
        return if (stripped.isEmpty() && cleaned.isNotEmpty()) "0" else stripped
    }
}

class ScanDebouncer(
    private val debounceWindowMs: Long = 380L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private var lastCode: String? = null
    private var lastAcceptedAtMs: Long = 0L

    @Synchronized
    fun shouldAccept(rawCode: String): Boolean {
        val normalized = ScannerParser.cleanString(rawCode)
        if (normalized.isEmpty()) return false
        val now = timeProvider()
        val isDuplicateBurst = normalized.equals(lastCode, ignoreCase = true) &&
            (now - lastAcceptedAtMs) in 0 until debounceWindowMs
        if (isDuplicateBurst) return false
        lastCode = normalized
        lastAcceptedAtMs = now
        return true
    }

    @Synchronized
    fun reset() {
        lastCode = null
        lastAcceptedAtMs = 0L
    }
}

class KeyboardWedgeDetector(
    private val maxInterKeyMs: Long = 70L,
    private val minLength: Int = 3,
    private val debouncer: ScanDebouncer = ScanDebouncer(),
    private val onBarcodeScanned: (String) -> Unit
) {
    private val buffer = StringBuilder()
    private var lastKeyTimeMs = 0L

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val now = System.currentTimeMillis()
        if (now - lastKeyTimeMs > maxInterKeyMs * 3 && buffer.isNotEmpty()) {
            buffer.clear()
        }
        lastKeyTimeMs = now

        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_TAB) {
            if (buffer.length >= minLength) {
                val scanned = ScannerParser.extractCode(buffer.toString())
                buffer.clear()
                if (debouncer.shouldAccept(scanned)) {
                    onBarcodeScanned(scanned)
                }
                return true
            }
            buffer.clear()
            return false
        }

        val ch = event.unicodeChar
        if (ch > 31 && !event.isCtrlPressed && !event.isAltPressed) {
            buffer.append(ch.toChar())
        }
        return false
    }
}

object PdaBroadcastScanner {
    private val ACTIONS = listOf(
        "com.symbol.datawedge.api.RESULT_ACTION",
        "com.honeywell.decode.intent.action.EDIT_DATA",
        "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",
        "android.intent.ACTION_DECODE_DATA",
        "com.scanner.broadcast"
    )

    private val EXTRA_KEYS = listOf(
        "com.symbol.datawedge.data_string",
        "data",
        "barcode_string",
        "SCAN_BARCODE1",
        "scannerdata",
        "decode_data_str"
    )

    fun register(
        context: Context,
        debouncer: ScanDebouncer = ScanDebouncer(),
        onScan: (String) -> Unit
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                for (key in EXTRA_KEYS) {
                    val value = intent.getStringExtra(key)
                    if (!value.isNullOrBlank()) {
                        val extracted = ScannerParser.extractCode(value)
                        if (debouncer.shouldAccept(extracted)) {
                            onScan(extracted)
                        }
                        return
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            ACTIONS.forEach { addAction(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        return receiver
    }
}
