package com.omnidrop.app.plugin

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "VideoDownloader")
class VideoDownloaderPlugin : Plugin() {

    companion object {
        var lastReceivedUrl: String? = null
        var instance: VideoDownloaderPlugin? = null
        const val PREFS_NAME = "omnidrop_native_prefs"
        const val PREF_HISTORY_KEY = "native_download_history"

        fun saveHistoryEntry(context: Context, entry: JSONObject) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_HISTORY_KEY, "[]") ?: "[]"
                val existing = JSONArray(jsonStr)
                val newArray = JSONArray()
                newArray.put(entry)
                for (i in 0 until existing.length()) {
                    if (newArray.length() >= 200) break
                    val item = existing.getJSONObject(i)
                    val idMatch = item.optString("id") == entry.optString("id")
                    val urlMatch = (item.optString("url") + item.optLong("savedAt")) == (entry.optString("url") + entry.optLong("savedAt"))
                    if (!idMatch && !urlMatch) {
                        newArray.put(item)
                    }
                }
                prefs.edit().apply {
                    this.putString(PREF_HISTORY_KEY, newArray.toString())
                    this.apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getHistoryJson(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREF_HISTORY_KEY, "[]") ?: "[]"
        }
    }

    override fun load() {
        super.load()
        instance = this
        val intent = activity.intent
        handleIncomingIntent(intent)
    }

    override fun handleOnNewIntent(intent: Intent?) {
        super.handleOnNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null && "text/plain" == type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                lastReceivedUrl = sharedText
                notifyUrlReceived(sharedText)
            }
        } else if (intent.hasExtra("url_to_download")) {
            val url = intent.getStringExtra("url_to_download")
            if (!url.isNullOrBlank()) {
                lastReceivedUrl = url
                notifyUrlReceived(url)
            }
        }
    }

    fun notifyUrlReceived(url: String) {
        val ret = JSObject().apply {
            this.put("url", url)
        }
        notifyListeners("onUrlReceived", ret, true)
    }

    fun emitNativeDownloadFinished(entry: JSONObject) {
        val jsObj = JSObject().apply {
            this.put("id", entry.optString("id"))
            this.put("url", entry.optString("url"))
            this.put("title", entry.optString("title"))
            this.put("thumbnail", entry.optString("thumbnail"))
            this.put("uploader", entry.optString("uploader"))
            this.put("platform", entry.optString("platform"))
            this.put("quality", entry.optString("quality"))
            this.put("savedAt", entry.optLong("savedAt"))
            this.put("downloadedAt", entry.optLong("savedAt"))
            this.put("fileName", entry.optString("fileName"))
            this.put("fileUri", entry.optString("fileUri"))
        }
        notifyListeners("nativeDownloadFinished", jsEntryFromObj(entry), true)
    }

    private fun jsEntryFromObj(entry: JSONObject): JSObject {
        return JSObject().apply {
            this.put("id", entry.optString("id"))
            this.put("url", entry.optString("url"))
            this.put("title", entry.optString("title"))
            this.put("thumbnail", entry.optString("thumbnail"))
            this.put("uploader", entry.optString("uploader"))
            this.put("platform", entry.optString("platform"))
            this.put("quality", entry.optString("quality"))
            this.put("savedAt", entry.optLong("savedAt"))
            this.put("downloadedAt", entry.optLong("savedAt"))
            this.put("fileName", entry.optString("fileName"))
            this.put("fileUri", entry.optString("fileUri"))
        }
    }

    @PluginMethod
    fun getNativeHistory(call: PluginCall) {
        try {
            val jsonStr = getHistoryJson(context)
            val jsonArray = JSONArray(jsonStr)
            val jsArray = JSArray()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                jsArray.put(jsEntryFromObj(item))
            }
            val res = JSObject().apply {
                this.put("history", jsArray)
            }
            call.resolve(res)
        } catch (e: Exception) {
            val res = JSObject().apply {
                this.put("history", JSArray())
            }
            call.resolve(res)
        }
    }

    @PluginMethod
    fun clearNativeHistory(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            this.remove(PREF_HISTORY_KEY)
            this.apply()
        }
        val res = JSObject().apply {
            this.put("cleared", true)
        }
        call.resolve(res)
    }

    @PluginMethod
    fun saveNativeSettings(call: PluginCall) {
        val backendUrl = call.getString("backendUrl") ?: ""
        val apiKey = call.getString("apiKey") ?: ""
        val defaultQuality = call.getString("defaultQuality") ?: "best"
        val autoDownload = call.getBoolean("autoDownload", true) ?: true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            this.putString("backend_url", backendUrl)
            this.putString("api_key", apiKey)
            this.putString("default_quality", defaultQuality)
            this.putBoolean("auto_download", autoDownload)
            this.apply()
        }

        FloatingBubbleService.instance?.updateSettings(backendUrl, apiKey, defaultQuality, autoDownload)

        val result = JSObject().apply {
            this.put("saved", true)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun hasOverlayPermission(call: PluginCall) {
        val result = JSObject().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this.put("granted", Settings.canDrawOverlays(context))
            } else {
                this.put("granted", true)
            }
        }
        call.resolve(result)
    }

    @PluginMethod
    fun requestOverlayPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                activity.startActivity(intent)
            }
        }
        val result = JSObject().apply {
            this.put("requested", true)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun startBubble(call: PluginCall) {
        val ctx = context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            call.reject("Overlay permission not granted. Please request permission first.")
            return
        }

        val serviceIntent = Intent(ctx, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent)
        } else {
            ctx.startService(serviceIntent)
        }

        val res = JSObject().apply {
            this.put("status", "running")
        }
        call.resolve(res)
    }

    @PluginMethod
    fun stopBubble(call: PluginCall) {
        val ctx = context
        val serviceIntent = Intent(ctx, FloatingBubbleService::class.java)
        ctx.stopService(serviceIntent)

        val res = JSObject().apply {
            this.put("status", "stopped")
        }
        call.resolve(res)
    }

    @PluginMethod
    fun showPersistentNotification(call: PluginCall) {
        val title = call.getString("title") ?: "OmniDrop Active"
        val message = call.getString("message") ?: "Tap 'Download from clipboard' anytime"
        NotificationHelper.showPersistentClipboardNotification(context, title, message)
        call.resolve()
    }

    @PluginMethod
    fun dismissPersistentNotification(call: PluginCall) {
        NotificationHelper.cancelPersistentNotification(context)
        call.resolve()
    }

    @PluginMethod
    fun showDownloadNotification(call: PluginCall) {
        val title = call.getString("title") ?: "Download Completed"
        val message = call.getString("message") ?: "Video saved to your device"
        val filePath = call.getString("filePath")
        NotificationHelper.showDownloadFinishedNotification(context, title, message, filePath)
        call.resolve()
    }

    @PluginMethod
    fun getLastSharedUrl(call: PluginCall) {
        val result = JSObject().apply {
            this.put("url", lastReceivedUrl ?: "")
        }
        lastReceivedUrl = null
        call.resolve(result)
    }

    @PluginMethod
    fun downloadAndSave(call: PluginCall) {
        val videoUrl = call.getString("url")
        val quality = call.getString("quality") ?: "best"
        var rawTitle = call.getString("title") ?: "video"
        var backendUrl = call.getString("backendUrl")
        val apiKey = call.getString("apiKey")

        if (videoUrl.isNullOrBlank()) {
            call.reject("Missing required parameter: url")
            return
        }

        if (backendUrl.isNullOrBlank()) {
            call.reject("Please configure your Render Backend URL in Settings before downloading on mobile.")
            return
        }

        backendUrl = backendUrl.trim().removeSuffix("/")
        val endpoint = if (backendUrl.startsWith("http://") || backendUrl.startsWith("https://")) {
            "$backendUrl/download"
        } else {
            "https://$backendUrl/download"
        }

        Thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            var targetUri: Uri? = null

            try {
                reportProgress(10, "Fetching video details...")

                // Step 1: Pre-fetch video metadata via /info for accurate history
                var finalTitle = rawTitle
                var thumbnail = ""
                var uploader = "Creator"
                var platform = "video"

                try {
                    val infoEndpoint = if (backendUrl.startsWith("http://") || backendUrl.startsWith("https://")) {
                        "$backendUrl/info"
                    } else {
                        "https://$backendUrl/info"
                    }
                    val iConn = (URL(infoEndpoint).openConnection() as HttpURLConnection).apply {
                        this.requestMethod = "POST"
                        this.connectTimeout = 10000
                        this.readTimeout = 12000
                        this.doOutput = true
                        this.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        if (!apiKey.isNullOrBlank()) {
                            this.setRequestProperty("x-api-key", apiKey.trim())
                        }
                    }
                    val iPayload = JSONObject().apply {
                        this.put("url", videoUrl)
                    }
                    iConn.outputStream.use { os ->
                        os.write(iPayload.toString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    if (iConn.responseCode == HttpURLConnection.HTTP_OK) {
                        val respStr = iConn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(respStr)
                        if (json.has("title") && !json.optString("title").isNullOrBlank()) {
                            finalTitle = json.optString("title")
                        }
                        thumbnail = json.optString("thumbnail", "")
                        uploader = json.optString("uploader", "Creator")
                        platform = json.optString("platform", "video")
                    }
                    iConn.disconnect()
                } catch (_: Exception) {
                    // Fall back gracefully to provided title
                }

                reportProgress(20, "Connecting to download server...")

                val isAudio = quality.equals("audio", ignoreCase = true)
                val ext = if (isAudio) ".mp3" else ".mp4"
                val mediaType = if (isAudio) "audio/mpeg" else "video/mp4"
                val safeTitle = finalTitle.replace("[^a-zA-Z0-9_\\-\\s]".toRegex(), "_")
                    .trim()
                    .take(50)
                    .ifBlank { "OmniDrop_video" }
                val fileName = "${safeTitle}_${System.currentTimeMillis()}$ext"

                val urlObj = URL(endpoint)
                connection = (urlObj.openConnection() as HttpURLConnection).apply {
                    this.requestMethod = "POST"
                    this.connectTimeout = 30000
                    this.readTimeout = 180000
                    this.doInput = true
                    this.doOutput = true
                    this.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    this.setRequestProperty("Accept", "*/*")
                    if (!apiKey.isNullOrBlank()) {
                        this.setRequestProperty("x-api-key", apiKey.trim())
                    }
                }

                val jsonPayload = JSONObject().apply {
                    this.put("url", videoUrl)
                    this.put("quality", quality)
                }
                connection.outputStream.use { os ->
                    os.write(jsonPayload.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorText = try {
                        connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    } catch (e: Exception) {
                        "HTTP $responseCode"
                    }

                    val cleanMsg = try {
                        val parsed = JSONObject(errorText)
                        parsed.optString("detail", parsed.optString("error", errorText))
                    } catch (e: Exception) {
                        errorText
                    }

                    call.reject("Download failed: $cleanMsg")
                    return@Thread
                }

                reportProgress(30, "Preparing phone gallery storage...")

                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    this.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    this.put(MediaStore.MediaColumns.MIME_TYPE, mediaType)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativeFolder = if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"
                        this.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                        this.put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val collectionUri = if (isAudio) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                }

                targetUri = resolver.insert(collectionUri, contentValues)
                if (targetUri == null) {
                    call.reject("Failed to create MediaStore entry in phone gallery.")
                    return@Thread
                }

                outputStream = resolver.openOutputStream(targetUri)
                if (outputStream == null) {
                    call.reject("Failed to open output stream to phone gallery.")
                    return@Thread
                }

                inputStream = connection.inputStream
                val totalBytes = connection.contentLengthLong
                var bytesCopied: Long = 0
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastProgressReportTime: Long = 0

                reportProgress(40, "Saving directly to gallery...")

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesCopied += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressReportTime > 300) {
                        lastProgressReportTime = now
                        val pct = if (totalBytes > 0) {
                            (40 + (bytesCopied.toDouble() / totalBytes * 55)).toInt().coerceIn(40, 95)
                        } else {
                            75
                        }
                        val mb = String.format("%.1f", bytesCopied / (1024.0 * 1024.0))
                        reportProgress(pct, "Saving to gallery: $mb MB...")
                    }
                }
                outputStream.flush()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = ContentValues().apply {
                        this.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(targetUri, updateValues, null, null)
                }

                reportProgress(100, "Saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}!")

                NotificationHelper.showDownloadFinishedNotification(
                    context,
                    "Saved to Gallery",
                    "$fileName saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}",
                    targetUri.toString()
                )

                // Save to native history and notify listeners
                val entryObj = JSONObject().apply {
                    this.put("id", "dl_" + System.currentTimeMillis())
                    this.put("url", videoUrl)
                    this.put("title", finalTitle)
                    this.put("thumbnail", thumbnail)
                    this.put("uploader", uploader)
                    this.put("platform", platform)
                    this.put("quality", quality)
                    this.put("savedAt", System.currentTimeMillis())
                    this.put("fileName", fileName)
                    this.put("fileUri", targetUri.toString())
                }
                saveHistoryEntry(context, entryObj)
                emitNativeDownloadFinished(entryObj)

                val res = JSObject().apply {
                    this.put("fileName", fileName)
                    this.put("uri", targetUri.toString())
                }
                call.resolve(res)

            } catch (e: Exception) {
                if (targetUri != null) {
                    try {
                        context.contentResolver.delete(targetUri, null, null)
                    } catch (_: Exception) {}
                }
                call.reject("Native download error: ${e.message ?: "Unknown error"}")
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                connection?.disconnect()
            }
        }.start()
    }

    private fun reportProgress(percent: Int, statusText: String) {
        val progressObj = JSObject().apply {
            this.put("percent", percent)
            this.put("statusText", statusText)
        }
        notifyListeners("onDownloadProgress", progressObj, true)
    }
}
