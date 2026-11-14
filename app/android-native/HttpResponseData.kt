package com.omnidrop.app.plugin

import java.io.InputStream
import java.net.HttpURLConnection

data class HttpResponseData(
    val statusCode: Int,
    val contentType: String?,
    val contentLength: Long = -1L,
    val headers: Map<String, String> = emptyMap(),
    val inputStream: InputStream? = null,
    val connection: HttpURLConnection? = null,
    val errorBody: String? = null,
    val elapsedMs: Long = 0L,
    val bodyBytes: ByteArray? = null,
    val errorDetail: String? = null,
    val isTimeout: Boolean = false,
    val isNoInternet: Boolean = false,
    val isConnectionError: Boolean = false,
    val friendlyError: String = "",
    val locationHeader: String? = headers["location"]
) {
    fun bodyText(): String = bodyBytes?.toString(Charsets.UTF_8) ?: errorBody ?: ""
}
