package com.example.ui

class WmsStrings(val isRtl: Boolean) {
    fun t(ar: String, en: String): String = if (isRtl) ar else en
}
