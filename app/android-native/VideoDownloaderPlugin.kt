package com.omnidrop.app.plugin

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Size
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream
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

        fun removeHistoryByUri(context: Context, uriStr: String) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_HISTORY_KEY, "[]") ?: "[]"
                val existing = JSONArray(jsonStr)
                val newArray = JSONArray()
                for (i in 0 until existing.length()) {
                    val item = existing.getJSONObject(i)
                    if (item.optString("fileUri") != uriStr) {
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
                val cleanTitle = finalTitle.replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_")
                    .trim()
                    .take(70)
                    .ifBlank { "OmniDrop_video" }
                val cleanPlatform = platform.replace("[^a-zA-Z0-9]".toRegex(), "").lowercase().ifBlank { "video" }
                val fileName = "$cleanTitle [$cleanPlatform]$ext"

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

    @PluginMethod
    fun hasVideoPermission(call: PluginCall) {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_VIDEO"
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val res = JSObject().apply {
            this.put("granted", granted)
        }
        call.resolve(res)
    }

    @PluginMethod
    fun requestVideoPermission(call: PluginCall) {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_VIDEO"
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(perm), 992)
            }
        }
        val res = JSObject().apply {
            this.put("requested", true)
        }
        call.resolve(res)
    }

    @PluginMethod
    fun getVideoLibrary(call: PluginCall) {
        Thread {
            try {
                val historyJsonStr = getHistoryJson(context)
                val historyArray = JSONArray(historyJsonStr)
                val historyByUri = HashMap<String, JSONObject>()
                val historyByName = HashMap<String, JSONObject>()

                for (i in 0 until historyArray.length()) {
                    val item = historyArray.getJSONObject(i)
                    val uri = item.optString("fileUri")
                    val name = item.optString("fileName")
                    if (uri.isNotBlank()) historyByUri[uri] = item
                    if (name.isNotBlank()) historyByName[name] = item
                }

                val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val projectionList = mutableListOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projectionList.add(MediaStore.Video.Media.RELATIVE_PATH)
                }
                projectionList.add(MediaStore.Video.Media.DATA)

                val projection = projectionList.toTypedArray()
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DATA} LIKE ?"
                } else {
                    "${MediaStore.Video.Media.DATA} LIKE ?"
                }
                val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf("%Movies/OmniDrop%", "%/Movies/OmniDrop/%")
                } else {
                    arrayOf("%/Movies/OmniDrop/%")
                }
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                val videosArray = JSArray()
                val cursor = context.contentResolver.query(collectionUri, projection, selection, selectionArgs, sortOrder)

                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                    while (c.moveToNext()) {
                        val idVal = c.getLong(idCol)
                        val displayName = c.getString(nameCol) ?: "video.mp4"
                        val sizeBytes = c.getLong(sizeCol)
                        val durationMs = c.getLong(durCol)
                        val dateAddedSec = c.getLong(dateCol)
                        val dateAddedMs = dateAddedSec * 1000L

                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idVal)
                        val uriStr = contentUri.toString()

                        val historyEntry = historyByUri[uriStr] ?: historyByName[displayName]

                        val rawBaseName = displayName.substringBeforeLast(".")
                        var platform = historyEntry?.optString("platform") ?: ""
                        if (platform.isBlank()) {
                            val lower = displayName.lowercase()
                            platform = when {
                                lower.contains("tiktok") -> "tiktok"
                                lower.contains("youtube") || lower.contains("youtu") -> "youtube"
                                lower.contains("instagram") -> "instagram"
                                lower.contains("facebook") || lower.contains("fb") -> "facebook"
                                else -> "video"
                            }
                        }

                        var title = historyEntry?.optString("title") ?: ""
                        if (title.isBlank()) {
                            val clean = rawBaseName.replace("\\[$platform\\]".toRegex(RegexOption.IGNORE_CASE), "")
                                .replace("\\[[^\\]]+\\]".toRegex(), "")
                                .replace("_", " ")
                                .trim()
                            title = if (clean.isNotBlank()) clean else rawBaseName
                        }

                        var thumbBase64 = ""
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val bm = context.contentResolver.loadThumbnail(contentUri, Size(320, 180), null)
                                val baos = ByteArrayOutputStream()
                                bm.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                                val bytes = baos.toByteArray()
                                thumbBase64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }
                        } catch (_: Exception) {}

                        if (thumbBase64.isBlank() && historyEntry != null) {
                            thumbBase64 = historyEntry.optString("thumbnail", "")
                        }

                        val vObj = JSObject().apply {
                            this.put("id", idVal.toString())
                            this.put("uri", uriStr)
                            this.put("title", title)
                            this.put("platform", platform)
                            this.put("size", sizeBytes)
                            this.put("durationMs", durationMs)
                            this.put("dateAdded", dateAddedMs)
                            this.put("thumbnail", thumbBase64)
                        }
                        videosArray.put(vObj)
                    }
                }

                val ret = JSObject().apply {
                    this.put("videos", videosArray)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to query video library: ${e.message ?: "Unknown error"}")
            }
        }.start()
    }

    @PluginMethod
    fun deleteVideo(call: PluginCall) {
        val uriStr = call.getString("uri")
        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        Thread {
            try {
                val uri = Uri.parse(uriStr)
                val rows = context.contentResolver.delete(uri, null, null)
                removeHistoryByUri(context, uriStr)
                val ret = JSObject().apply {
                    this.put("deleted", rows > 0)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to delete video: ${e.message ?: "Unknown error"}")
            }
        }.start()
    }

    @PluginMethod
    fun playVideo(call: PluginCall) {
        val uriStr = call.getString("uri")
        val title = call.getString("title") ?: "OmniDrop Video"
        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        try {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                this.data = Uri.parse(uriStr)
                this.putExtra("video_title", title)
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            val ret = JSObject().apply {
                this.put("started", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to launch VideoPlayerActivity: ${e.message ?: "Unknown error"}")
        }
    }

    @PluginMethod
    fun shareVideo(call: PluginCall) {
        val uriStr = call.getString("uri")
        val title = call.getString("title") ?: "OmniDrop Video"
        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        try {
            val uri = Uri.parse(uriStr)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                this.type = "video/*"
                this.putExtra(Intent.EXTRA_STREAM, uri)
                this.putExtra(Intent.EXTRA_SUBJECT, title)
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share video").apply {
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            val ret = JSObject().apply {
                this.put("shared", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to share video: ${e.message ?: "Unknown error"}")
        }
    }
}
