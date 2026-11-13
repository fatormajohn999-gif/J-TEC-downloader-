package com.omnidrop.app.plugin

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.util.concurrent.Executor
import java.util.function.Consumer
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "VideoDownloader")
class VideoDownloaderPlugin : Plugin() {

    companion object {
        var lastReceivedUrl: String? = null
        var instance: VideoDownloaderPlugin? = null
        const val PREFS_NAME = "omnidrop_native_prefs"
        const val PREF_HISTORY_KEY = "native_download_history"
        const val PREFS_METADATA_NAME = "omnidrop_video_metadata"
        const val PREF_METADATA_MAP = "video_metadata_map"

        const val PREF_FAILED_LINKS_KEY = "native_failed_links"
        const val PREF_FAVORITES_KEY = "native_favorites_set"
        const val PREF_COLLECTIONS_KEY = "native_collections_list"
        const val PREF_STATS_RECORDS_KEY = "native_download_stats_records"
        const val PREF_STATS_SUMMARY_KEY = "native_download_stats_summary"

        const val REQUEST_CODE_DELETE = 7101
        const val REQUEST_CODE_RENAME = 7102

        // In-flight download lock keyed by normalized sourceUrl
        val inFlightDownloads = ConcurrentHashMap<String, Boolean>()

        fun normalizeUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            try {
                val uri = Uri.parse(trimmed)
                val host = uri.host?.lowercase() ?: return trimmed
                val scheme = if (uri.scheme.isNullOrBlank()) "https" else uri.scheme

                if (host.contains("youtube.com")) {
                    val path = uri.path ?: ""
                    if (path.contains("/watch")) {
                        val v = uri.getQueryParameter("v")
                        return if (!v.isNullOrBlank()) "$scheme://$host$path?v=$v" else "$scheme://$host$path"
                    }
                    return "$scheme://$host${path.trimEnd('/')}"
                }
                if (host == "youtu.be") {
                    val path = (uri.path ?: "").trimEnd('/')
                    return "$scheme://$host$path"
                }
                if (host.contains("tiktok.com")) {
                    val path = (uri.path ?: "").trimEnd('/')
                    return "$scheme://$host$path"
                }
                if (host.contains("instagram.com")) {
                    val path = (uri.path ?: "").trimEnd('/')
                    return "$scheme://$host$path"
                }
                if (host.contains("facebook.com") || host.contains("fb.watch")) {
                    val path = uri.path ?: ""
                    if (path.contains("/watch")) {
                        val v = uri.getQueryParameter("v")
                        if (!v.isNullOrBlank()) {
                            return "$scheme://$host$path?v=$v"
                        }
                    }
                    return "$scheme://$host${path.trimEnd('/')}"
                }
                return trimmed
            } catch (_: Exception) {
                return trimmed
            }
        }

        fun detectPlatformName(url: String): String {
            val lower = url.lowercase()
            return when {
                lower.contains("tiktok") -> "TikTok"
                lower.contains("youtube") || lower.contains("youtu.be") -> "YouTube"
                lower.contains("instagram") -> "Instagram"
                lower.contains("facebook") || lower.contains("fb.watch") -> "Facebook"
                else -> "Other"
            }
        }

        fun cleanPlatformName(rawPlatform: String?, fallbackUrl: String): String {
            val p = rawPlatform?.trim()?.lowercase() ?: ""
            return when {
                p.contains("tiktok") -> "TikTok"
                p.contains("youtube") || p.contains("youtu") -> "YouTube"
                p.contains("instagram") -> "Instagram"
                p.contains("facebook") || p.contains("fb") -> "Facebook"
                p.isNotBlank() && p != "video" && p != "other" -> {
                    rawPlatform!!.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                else -> {
                    val guessed = detectPlatformName(fallbackUrl)
                    if (guessed != "Other") guessed else "Other"
                }
            }
        }

        fun saveVideoMetadata(context: Context, fileName: String, entry: JSONObject) {
            try {
                val prefs = context.getSharedPreferences(PREFS_METADATA_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_METADATA_MAP, "{}") ?: "{}"
                val root = JSONObject(jsonStr)

                // Evict if exceeding 500 items
                if (root.length() >= 500) {
                    val keys = root.keys()
                    if (keys.hasNext()) {
                        root.remove(keys.next())
                    }
                }

                root.put(fileName, entry)
                prefs.edit().apply {
                    this.putString(PREF_METADATA_MAP, root.toString())
                    this.apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getMetadataForFileName(context: Context, fileName: String): JSONObject? {
            try {
                val prefs = context.getSharedPreferences(PREFS_METADATA_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_METADATA_MAP, "{}") ?: "{}"
                val root = JSONObject(jsonStr)
                if (root.has(fileName)) {
                    return root.getJSONObject(fileName)
                }
            } catch (_: Exception) {}
            return null
        }

        fun getAllMetadata(context: Context): JSONObject {
            try {
                val prefs = context.getSharedPreferences(PREFS_METADATA_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_METADATA_MAP, "{}") ?: "{}"
                return JSONObject(jsonStr)
            } catch (_: Exception) {
                return JSONObject()
            }
        }

        fun checkDuplicateDownload(context: Context, sourceUrl: String): JSONObject? {
            val norm = normalizeUrl(sourceUrl)
            try {
                val all = getAllMetadata(context)
                val keys = all.keys()
                while (keys.hasNext()) {
                    val fileName = keys.next()
                    val item = all.getJSONObject(fileName)
                    val itemUrl = normalizeUrl(item.optString("sourceUrl", item.optString("url", "")))
                    if (itemUrl.isNotBlank() && itemUrl == norm) {
                        val uriStr = item.optString("fileUri", "")
                        val isPhoto = item.optString("type") == "photo"
                        if (uriStr.isNotBlank()) {
                            try {
                                val uri = Uri.parse(uriStr)
                                val col = if (isPhoto) arrayOf(MediaStore.Images.Media._ID) else arrayOf(MediaStore.Video.Media._ID)
                                val cursor = context.contentResolver.query(uri, col, null, null, null)
                                val exists = cursor?.use { it.count > 0 } ?: false
                                if (exists) {
                                    return item.apply {
                                        this.put("fileName", fileName)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }

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

        // ==================== FAILED LINKS ====================
        fun recordFailedLink(context: Context, url: String, platform: String, title: String, reason: String) {
            try {
                val norm = normalizeUrl(url)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_FAILED_LINKS_KEY, "[]") ?: "[]"
                val existing = JSONArray(jsonStr)
                var attempts = 1
                var existingId = "fail_" + System.currentTimeMillis()
                var existingTitle = title
                val list = mutableListOf<JSONObject>()

                for (i in 0 until existing.length()) {
                    val item = existing.getJSONObject(i)
                    val itemNorm = normalizeUrl(item.optString("url", ""))
                    if (itemNorm == norm) {
                        attempts = item.optInt("attempts", 1) + 1
                        existingId = item.optString("id", existingId)
                        if (existingTitle.isBlank() || existingTitle == "video") {
                            existingTitle = item.optString("title", existingTitle)
                        }
                    } else {
                        list.add(item)
                    }
                }

                val newEntry = JSONObject().apply {
                    this.put("id", existingId)
                    this.put("url", norm)
                    this.put("platform", if (platform.isNotBlank()) platform else detectPlatformName(norm))
                    this.put("title", if (existingTitle.isNotBlank()) existingTitle else "Stream")
                    this.put("reason", if (reason.isNotBlank()) reason else "Download failed")
                    this.put("failedAt", System.currentTimeMillis())
                    this.put("attempts", attempts)
                }

                val newArray = JSONArray()
                newArray.put(newEntry)
                for (item in list) {
                    if (newArray.length() >= 100) break
                    newArray.put(item)
                }

                prefs.edit().apply {
                    this.putString(PREF_FAILED_LINKS_KEY, newArray.toString())
                    this.apply()
                }
                instance?.notifyListeners("onFailedLinksChanged", JSObject(), true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun removeFailedLinkByUrl(context: Context, url: String) {
            try {
                val norm = normalizeUrl(url)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_FAILED_LINKS_KEY, "[]") ?: "[]"
                val existing = JSONArray(jsonStr)
                val newArray = JSONArray()
                var changed = false
                for (i in 0 until existing.length()) {
                    val item = existing.getJSONObject(i)
                    if (normalizeUrl(item.optString("url", "")) == norm) {
                        changed = true
                    } else {
                        newArray.put(item)
                    }
                }
                if (changed) {
                    prefs.edit().apply {
                        this.putString(PREF_FAILED_LINKS_KEY, newArray.toString())
                        this.apply()
                    }
                    instance?.notifyListeners("onFailedLinksChanged", JSObject(), true)
                }
            } catch (_: Exception) {}
        }

        fun removeFailedLinkById(context: Context, id: String): Boolean {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(PREF_FAILED_LINKS_KEY, "[]") ?: "[]"
                val existing = JSONArray(jsonStr)
                val newArray = JSONArray()
                var changed = false
                for (i in 0 until existing.length()) {
                    val item = existing.getJSONObject(i)
                    if (item.optString("id") == id) {
                        changed = true
                    } else {
                        newArray.put(item)
                    }
                }
                if (changed) {
                    prefs.edit().apply {
                        this.putString(PREF_FAILED_LINKS_KEY, newArray.toString())
                        this.apply()
                    }
                    instance?.notifyListeners("onFailedLinksChanged", JSObject(), true)
                }
                return changed
            } catch (_: Exception) {
                return false
            }
        }

        fun clearFailedLinks(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    this.putString(PREF_FAILED_LINKS_KEY, "[]")
                    this.apply()
                }
                instance?.notifyListeners("onFailedLinksChanged", JSObject(), true)
            } catch (_: Exception) {}
        }

        fun getFailedLinksArray(context: Context): JSONArray {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                JSONArray(prefs.getString(PREF_FAILED_LINKS_KEY, "[]") ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
        }

        // ==================== FAVORITES & COLLECTIONS ====================
        fun isFavorite(context: Context, mediaKey: String): Boolean {
            if (mediaKey.isBlank()) return false
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val set = prefs.getStringSet(PREF_FAVORITES_KEY, emptySet()) ?: emptySet()
                set.contains(mediaKey)
            } catch (_: Exception) {
                false
            }
        }

        fun toggleFavorite(context: Context, mediaKey: String): Boolean {
            if (mediaKey.isBlank()) return false
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val set = HashSet(prefs.getStringSet(PREF_FAVORITES_KEY, emptySet()) ?: emptySet())
                val newState = if (set.contains(mediaKey)) {
                    set.remove(mediaKey)
                    false
                } else {
                    set.add(mediaKey)
                    true
                }
                prefs.edit().apply {
                    this.putStringSet(PREF_FAVORITES_KEY, set)
                    this.apply()
                }
                instance?.notifyListeners("onFavoritesChanged", JSObject().apply {
                    this.put("mediaKey", mediaKey)
                    this.put("isFavorite", newState)
                }, true)
                newState
            } catch (_: Exception) {
                false
            }
        }

        fun getFavoritesArray(context: Context): JSONArray {
            val arr = JSONArray()
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val set = prefs.getStringSet(PREF_FAVORITES_KEY, emptySet()) ?: emptySet()
                for (k in set) {
                    arr.put(k)
                }
            } catch (_: Exception) {}
            return arr
        }

        fun getCollectionsArray(context: Context): JSONArray {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                JSONArray(prefs.getString(PREF_COLLECTIONS_KEY, "[]") ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
        }

        fun saveCollectionsArray(context: Context, arr: JSONArray) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    this.putString(PREF_COLLECTIONS_KEY, arr.toString())
                    this.apply()
                }
                instance?.notifyListeners("onCollectionsChanged", JSObject(), true)
            } catch (_: Exception) {}
        }

        fun cleanUpDeletedMediaKeys(context: Context, deletedKeys: Set<String>) {
            if (deletedKeys.isEmpty()) return
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val favSet = HashSet(prefs.getStringSet(PREF_FAVORITES_KEY, emptySet()) ?: emptySet())
                var favChanged = false
                for (k in deletedKeys) {
                    if (favSet.remove(k)) favChanged = true
                }
                if (favChanged) {
                    prefs.edit().apply {
                        this.putStringSet(PREF_FAVORITES_KEY, favSet)
                        this.apply()
                    }
                }

                val collections = getCollectionsArray(context)
                var colChanged = false
                val newCols = JSONArray()
                for (i in 0 until collections.length()) {
                    val col = collections.getJSONObject(i)
                    val vKeys = col.optJSONArray("videoKeys") ?: JSONArray()
                    val newVKeys = JSONArray()
                    for (j in 0 until vKeys.length()) {
                        val k = vKeys.getString(j)
                        if (deletedKeys.contains(k)) {
                            colChanged = true
                        } else {
                            newVKeys.put(k)
                        }
                    }
                    col.put("videoKeys", newVKeys)
                    newCols.put(col)
                }
                if (colChanged) {
                    saveCollectionsArray(context, newCols)
                }
            } catch (_: Exception) {}
        }

        fun cleanUpDeletedMediaUris(context: Context, uris: List<String>) {
            if (uris.isEmpty()) return
            try {
                val keysToRemove = mutableSetOf<String>()
                val resolver = context.contentResolver
                for (uStr in uris) {
                    try {
                        val uri = Uri.parse(uStr)
                        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                                val sIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                                val name = if (nIdx >= 0) cursor.getString(nIdx) ?: "" else ""
                                val size = if (sIdx >= 0) cursor.getLong(sIdx) else 0L
                                if (name.isNotBlank()) {
                                    keysToRemove.add("${name}_$size")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                cleanUpDeletedMediaKeys(context, keysToRemove)
            } catch (_: Exception) {}
        }

        // ==================== STATS ====================
        fun recordDownloadStat(context: Context, platform: String, type: String, bytes: Long) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val todayStr = sdf.format(Date())

                val summaryStr = prefs.getString(PREF_STATS_SUMMARY_KEY, "{}") ?: "{}"
                val summary = JSONObject(summaryStr)
                val totalDl = summary.optLong("totalDownloads", 0L) + 1L
                val totalBytes = summary.optLong("totalBytes", 0L) + bytes.coerceAtLeast(0L)
                val vCount = summary.optLong("videoCount", 0L) + (if (type == "video" || type == "audio") 1L else 0L)
                val pCount = summary.optLong("photoCount", 0L) + (if (type == "photo") 1L else 0L)
                val fCount = summary.optLong("failedCount", 0L)

                val platObj = summary.optJSONObject("platforms") ?: JSONObject()
                val cleanPlat = cleanPlatformName(platform, "").ifBlank { "Other" }
                platObj.put(cleanPlat, platObj.optLong(cleanPlat, 0L) + 1L)

                summary.put("totalDownloads", totalDl)
                summary.put("totalBytes", totalBytes)
                summary.put("videoCount", vCount)
                summary.put("photoCount", pCount)
                summary.put("failedCount", fCount)
                summary.put("platforms", platObj)

                val recordsStr = prefs.getString(PREF_STATS_RECORDS_KEY, "[]") ?: "[]"
                val recordsArr = JSONArray(recordsStr)
                val newEntry = JSONObject().apply {
                    this.put("date", todayStr)
                    this.put("platform", cleanPlat)
                    this.put("type", type)
                    this.put("bytes", bytes)
                    this.put("ts", System.currentTimeMillis())
                }
                val newArr = JSONArray()
                newArr.put(newEntry)
                for (i in 0 until recordsArr.length()) {
                    if (newArr.length() >= 500) break
                    newArr.put(recordsArr.getJSONObject(i))
                }

                prefs.edit().apply {
                    this.putString(PREF_STATS_SUMMARY_KEY, summary.toString())
                    this.putString(PREF_STATS_RECORDS_KEY, newArr.toString())
                    this.apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun recordFailedStat(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val summaryStr = prefs.getString(PREF_STATS_SUMMARY_KEY, "{}") ?: "{}"
                val summary = JSONObject(summaryStr)
                val fCount = summary.optLong("failedCount", 0L) + 1L
                summary.put("failedCount", fCount)
                prefs.edit().apply {
                    this.putString(PREF_STATS_SUMMARY_KEY, summary.toString())
                    this.apply()
                }
            } catch (_: Exception) {}
        }

        fun getStatsJson(context: Context): JSONObject {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val summaryStr = prefs.getString(PREF_STATS_SUMMARY_KEY, "{}") ?: "{}"
            val recordsStr = prefs.getString(PREF_STATS_RECORDS_KEY, "[]") ?: "[]"
            val summary = try { JSONObject(summaryStr) } catch (_: Exception) { JSONObject() }
            val records = try { JSONArray(recordsStr) } catch (_: Exception) { JSONArray() }

            val ret = JSONObject()
            val totalDl = summary.optLong("totalDownloads", 0L)
            val totalBytes = summary.optLong("totalBytes", 0L)
            val vCount = summary.optLong("videoCount", 0L)
            val pCount = summary.optLong("photoCount", 0L)
            val fCount = summary.optLong("failedCount", 0L)
            val totalAttempts = totalDl + fCount
            val successRate = if (totalAttempts > 0L) ((totalDl.toDouble() / totalAttempts.toDouble()) * 100.0) else 100.0

            ret.put("totalDownloads", totalDl)
            ret.put("totalBytes", totalBytes)
            ret.put("videoCount", vCount)
            ret.put("photoCount", pCount)
            ret.put("failedCount", fCount)
            ret.put("successRate", Math.round(successRate * 10.0) / 10.0)
            ret.put("platformCounts", summary.optJSONObject("platforms") ?: JSONObject())

            val dayDownloads = mutableMapOf<String, Long>()
            val dayBytes = mutableMapOf<String, Long>()
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                val d = r.optString("date")
                if (d.isNotBlank()) {
                    dayDownloads[d] = (dayDownloads[d] ?: 0L) + 1L
                    dayBytes[d] = (dayBytes[d] ?: 0L) + r.optLong("bytes", 0L)
                }
            }

            var busiest = "None"
            var maxDls = 0L
            for ((d, count) in dayDownloads) {
                if (count > maxDls) {
                    maxDls = count
                    busiest = d
                }
            }
            ret.put("busiestDay", busiest)
            ret.put("averageBytes", if (totalDl > 0L) (totalBytes / totalDl) else 0L)

            val dailyArr = JSONArray()
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            for (offset in 13 downTo 0) {
                val c = java.util.Calendar.getInstance()
                c.add(java.util.Calendar.DAY_OF_YEAR, -offset)
                val ds = sdf.format(c.time)
                val dayObj = JSONObject().apply {
                    this.put("date", ds)
                    this.put("downloads", dayDownloads[ds] ?: 0L)
                    this.put("bytes", dayBytes[ds] ?: 0L)
                }
                dailyArr.put(dayObj)
            }
            ret.put("dailyStats", dailyArr)
            return ret
        }

        fun getTodayStatsSummary(context: Context): JSONObject {
            val ret = JSONObject()
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val summaryStr = prefs.getString(PREF_STATS_SUMMARY_KEY, "{}") ?: "{}"
                val recordsStr = prefs.getString(PREF_STATS_RECORDS_KEY, "[]") ?: "[]"
                val summary = JSONObject(summaryStr)
                val records = JSONArray(recordsStr)
                val totalDl = summary.optLong("totalDownloads", 0L)
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                var todayCount = 0L
                var todayBytes = 0L
                for (i in 0 until records.length()) {
                    val r = records.getJSONObject(i)
                    if (r.optString("date") == todayStr) {
                        todayCount += 1L
                        todayBytes += r.optLong("bytes", 0L)
                    }
                }
                ret.put("hasStats", totalDl > 0L || records.length() > 0)
                ret.put("todayDownloads", todayCount)
                ret.put("todayBytes", todayBytes)
            } catch (_: Throwable) {
                ret.put("hasStats", false)
                ret.put("todayDownloads", 0L)
                ret.put("todayBytes", 0L)
            }
            return ret
        }

        fun resetStats(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    this.putString(PREF_STATS_SUMMARY_KEY, "{}")
                    this.putString(PREF_STATS_RECORDS_KEY, "[]")
                    this.apply()
                }
            } catch (_: Exception) {}
        }

        fun extractAudioDirect(
            context: Context,
            srcUri: Uri,
            title: String?,
            onProgress: ((Int) -> Unit)? = null,
            onSuccess: ((outputUri: Uri, fileName: String) -> Unit)? = null,
            onError: ((String) -> Unit)? = null
        ) {
            Thread {
                var pfd: ParcelFileDescriptor? = null
                var outPfd: ParcelFileDescriptor? = null
                var extractor: MediaExtractor? = null
                var muxer: MediaMuxer? = null
                var outUri: Uri? = null

                try {
                    pfd = context.contentResolver.openFileDescriptor(srcUri, "r")
                        ?: throw IOException("Cannot open video source file")

                    extractor = MediaExtractor()
                    extractor.setDataSource(pfd.fileDescriptor)

                    var audioTrackIndex = -1
                    var audioFormat: MediaFormat? = null
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            audioFormat = format
                            break
                        }
                    }

                    if (audioTrackIndex < 0 || audioFormat == null) {
                        throw IllegalStateException("No audio track found in this video")
                    }

                    val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""

                    var cleanTitle = (title ?: "OmniDrop_Audio").trim()
                    if (cleanTitle.isBlank()) cleanTitle = "OmniDrop_Audio"
                    cleanTitle = cleanTitle.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                    if (cleanTitle.endsWith(".mp4", ignoreCase = true)) {
                        cleanTitle = cleanTitle.substring(0, cleanTitle.length - 4)
                    }
                    if (cleanTitle.endsWith(".m4a", ignoreCase = true)) {
                        cleanTitle = cleanTitle.substring(0, cleanTitle.length - 4)
                    }
                    val finalFileName = "$cleanTitle.m4a"

                    val values = ContentValues().apply {
                        this.put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName)
                        this.put(MediaStore.Audio.Media.TITLE, cleanTitle)
                        this.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            this.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/OmniDrop")
                            this.put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                    }

                    outUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create audio destination in Music/OmniDrop")

                    outPfd = context.contentResolver.openFileDescriptor(outUri, "w")
                        ?: throw IOException("Cannot write to audio destination in Music/OmniDrop")

                    muxer = try {
                        MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Unsupported audio container for output: ${e.message}")
                    }

                    val muxerTrack = try {
                        muxer.addTrack(audioFormat)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Unsupported audio codec ($mime) for M4A container")
                    }

                    muxer.start()
                    extractor.selectTrack(audioTrackIndex)

                    val durationUs = try {
                        audioFormat.getLong(MediaFormat.KEY_DURATION)
                    } catch (_: Throwable) { 0L }

                    val maxBufferSize = try {
                        audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } catch (_: Throwable) {
                        64 * 1024
                    }.coerceAtLeast(64 * 1024)

                    val buffer = ByteBuffer.allocate(maxBufferSize)
                    val bufferInfo = MediaCodec.BufferInfo()
                    var lastProgressEmit = 0L

                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            bufferInfo.size = 0
                            break
                        }
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxerTrack, buffer, bufferInfo)

                        val now = System.currentTimeMillis()
                        if (durationUs > 0L && now - lastProgressEmit > 120L) {
                            lastProgressEmit = now
                            val pct = ((bufferInfo.presentationTimeUs.toDouble() / durationUs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                            onProgress?.invoke(pct)
                        }
                        extractor.advance()
                    }

                    muxer.stop()
                    muxer.release()
                    muxer = null

                    outPfd.close()
                    outPfd = null

                    extractor.release()
                    extractor = null

                    pfd.close()
                    pfd = null

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val updateVals = ContentValues().apply {
                            this.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }
                        context.contentResolver.update(outUri, updateVals, null, null)
                    }

                    onProgress?.invoke(100)
                    onSuccess?.invoke(outUri, finalFileName)
                } catch (t: Throwable) {
                    try { muxer?.release() } catch (_: Throwable) {}
                    try { outPfd?.close() } catch (_: Throwable) {}
                    try { extractor?.release() } catch (_: Throwable) {}
                    try { pfd?.close() } catch (_: Throwable) {}
                    if (outUri != null) {
                        try { context.contentResolver.delete(outUri, null, null) } catch (_: Throwable) {}
                    }
                    onError?.invoke(t.message ?: "Audio extraction failed")
                }
            }.start()
        }
    }

    override fun load() {
        super.load()
        instance = this
        try {
            val updatesDir = java.io.File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.deleteRecursively()
            }
        } catch (_: Exception) {}
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
                val normalized = normalizeUrl(sharedText)
                lastReceivedUrl = normalized
                notifyUrlReceived(normalized)
            }
        } else if (intent.hasExtra("url_to_download")) {
            val url = intent.getStringExtra("url_to_download")
            if (!url.isNullOrBlank()) {
                val normalized = normalizeUrl(url)
                lastReceivedUrl = normalized
                notifyUrlReceived(normalized)
            }
        }

        if (intent.getStringExtra("open_tab") == "settings") {
            val ret = JSObject().apply {
                this.put("tab", "settings")
            }
            notifyListeners("openSettingsTab", ret, true)
        }
    }

    private var savedDeleteCall: PluginCall? = null
    private var savedDeleteDirectCount: Int = 0
    private var savedDeletePendingUris: List<String> = emptyList()

    private var savedRenameCall: PluginCall? = null
    private var savedRenameUri: String? = null
    private var savedRenameNewTitle: String? = null
    private var savedRenameNewPlatform: String? = null

    override fun handleOnResume() {
        super.handleOnResume()
        FloatingBubbleService.setOmniDropForeground(true)
    }

    override fun handleOnPause() {
        super.handleOnPause()
        FloatingBubbleService.setOmniDropForeground(false)
    }

    override fun handleOnStop() {
        super.handleOnStop()
        FloatingBubbleService.setOmniDropForeground(false)
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        FloatingBubbleService.setOmniDropForeground(false)
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DELETE) {
            val call = savedDeleteCall
            savedDeleteCall = null
            if (call != null) {
                if (resultCode == Activity.RESULT_OK) {
                    for (u in savedDeletePendingUris) {
                        removeHistoryByUri(context, u)
                    }
                    cleanUpDeletedMediaUris(context, savedDeletePendingUris)
                    val totalDeleted = savedDeleteDirectCount + savedDeletePendingUris.size
                    val ret = JSObject().apply {
                        this.put("deleted", totalDeleted)
                        this.put("count", totalDeleted)
                        this.put("failed", 0)
                        this.put("cancelled", false)
                    }
                    call.resolve(ret)
                } else {
                    val ret = JSObject().apply {
                        this.put("deleted", savedDeleteDirectCount)
                        this.put("count", savedDeleteDirectCount)
                        this.put("failed", savedDeletePendingUris.size)
                        this.put("cancelled", true)
                    }
                    call.resolve(ret)
                }
            }
            savedDeletePendingUris = emptyList()
            savedDeleteDirectCount = 0
        } else if (requestCode == REQUEST_CODE_RENAME) {
            val call = savedRenameCall
            val uriStr = savedRenameUri
            val newTitle = savedRenameNewTitle ?: ""
            val newPlatform = savedRenameNewPlatform ?: "Other"
            savedRenameCall = null
            savedRenameUri = null
            savedRenameNewTitle = null
            savedRenameNewPlatform = null

            if (call != null) {
                if (resultCode == Activity.RESULT_OK && !uriStr.isNullOrBlank()) {
                    applyRenameSuccess(call, uriStr, newTitle, newPlatform)
                } else {
                    val ret = JSObject().apply {
                        this.put("success", false)
                        this.put("cancelled", true)
                    }
                    call.resolve(ret)
                }
            }
        }
    }

    private fun performMediaDelete(call: PluginCall, uriStrings: List<String>) {
        if (uriStrings.isEmpty()) {
            val ret = JSObject().apply {
                this.put("deleted", 0)
                this.put("count", 0)
                this.put("failed", 0)
                this.put("cancelled", false)
            }
            call.resolve(ret)
            return
        }

        Thread {
            var directDeleted = 0
            val securityExceptionUris = mutableListOf<Uri>()
            var recoverableException: android.app.RecoverableSecurityException? = null

            for (uStr in uriStrings) {
                val uri = try {
                    Uri.parse(uStr)
                } catch (_: Exception) {
                    null
                } ?: continue

                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        directDeleted++
                        removeHistoryByUri(context, uStr)
                    } else {
                        val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                        val exists = (cursor?.count ?: 0) > 0
                        cursor?.close()
                        if (exists) {
                            securityExceptionUris.add(uri)
                        } else {
                            directDeleted++
                            removeHistoryByUri(context, uStr)
                        }
                    }
                } catch (sec: SecurityException) {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && sec is android.app.RecoverableSecurityException) {
                        recoverableException = sec
                    }
                    securityExceptionUris.add(uri)
                } catch (_: Exception) {}
            }

            if (securityExceptionUris.isEmpty()) {
                cleanUpDeletedMediaUris(context, uriStrings)
                val ret = JSObject().apply {
                    this.put("deleted", directDeleted)
                    this.put("count", directDeleted)
                    this.put("failed", 0)
                    this.put("cancelled", false)
                }
                call.resolve(ret)
                return@Thread
            }

            activity.runOnUiThread {
                try {
                    val recSec = recoverableException
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, securityExceptionUris)
                        savedDeleteCall = call
                        savedDeleteDirectCount = directDeleted
                        savedDeletePendingUris = securityExceptionUris.map { it.toString() }
                        activity.startIntentSenderForResult(
                            pendingIntent.intentSender,
                            REQUEST_CODE_DELETE,
                            null,
                            0,
                            0,
                            0
                        )
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && recSec != null) {
                        savedDeleteCall = call
                        savedDeleteDirectCount = directDeleted
                        savedDeletePendingUris = securityExceptionUris.map { it.toString() }
                        activity.startIntentSenderForResult(
                            recSec.userAction.actionIntent.intentSender,
                            REQUEST_CODE_DELETE,
                            null,
                            0,
                            0,
                            0
                        )
                    } else {
                        val ret = JSObject().apply {
                            this.put("deleted", directDeleted)
                            this.put("count", directDeleted)
                            this.put("failed", securityExceptionUris.size)
                            this.put("cancelled", false)
                        }
                        call.resolve(ret)
                    }
                } catch (e: Exception) {
                    val ret = JSObject().apply {
                        this.put("deleted", directDeleted)
                        this.put("count", directDeleted)
                        this.put("failed", securityExceptionUris.size)
                        this.put("cancelled", false)
                    }
                    call.resolve(ret)
                }
            }
        }.start()
    }

    private fun applyRenameSuccess(call: PluginCall, uriStr: String, newTitle: String, newPlatform: String) {
        Thread {
            try {
                val uri = Uri.parse(uriStr)
                val cv = ContentValues().apply {
                    this.put(MediaStore.Video.Media.TITLE, newTitle)
                }
                context.contentResolver.update(uri, cv, null, null)

                var displayName = ""
                context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        displayName = c.getString(0) ?: ""
                    }
                }

                val meta = (if (displayName.isNotBlank()) getMetadataForFileName(context, displayName) else null) ?: JSONObject()
                meta.put("title", newTitle)
                meta.put("platform", newPlatform)
                meta.put("fileUri", uriStr)
                if (displayName.isNotBlank()) {
                    saveVideoMetadata(context, displayName, meta)
                }

                val ret = JSObject().apply {
                    this.put("success", true)
                    this.put("title", newTitle)
                    this.put("platform", newPlatform)
                    this.put("cancelled", false)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                val ret = JSObject().apply {
                    this.put("success", false)
                    this.put("cancelled", false)
                }
                call.resolve(ret)
            }
        }.start()
    }

    private fun requestWritePermissionForRename(
        call: PluginCall,
        uri: Uri,
        uriStr: String,
        newTitle: String,
        newPlatform: String,
        sec: SecurityException? = null
    ) {
        activity.runOnUiThread {
            try {
                val recSec = sec as? android.app.RecoverableSecurityException
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                    savedRenameCall = call
                    savedRenameUri = uriStr
                    savedRenameNewTitle = newTitle
                    savedRenameNewPlatform = newPlatform
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender,
                        REQUEST_CODE_RENAME,
                        null,
                        0,
                        0,
                        0
                    )
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && recSec != null) {
                    savedRenameCall = call
                    savedRenameUri = uriStr
                    savedRenameNewTitle = newTitle
                    savedRenameNewPlatform = newPlatform
                    activity.startIntentSenderForResult(
                        recSec.userAction.actionIntent.intentSender,
                        REQUEST_CODE_RENAME,
                        null,
                        0,
                        0,
                        0
                    )
                } else {
                    val ret = JSObject().apply {
                        this.put("success", false)
                        this.put("cancelled", false)
                    }
                    call.resolve(ret)
                }
            } catch (e: Exception) {
                val ret = JSObject().apply {
                    this.put("success", false)
                    this.put("cancelled", false)
                }
                call.resolve(ret)
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
    fun checkDuplicate(call: PluginCall) {
        val rawUrl = call.getString("url")
        if (rawUrl.isNullOrBlank()) {
            val ret = JSObject().apply { this.put("exists", false) }
            call.resolve(ret)
            return
        }

        Thread {
            val dup = checkDuplicateDownload(context, rawUrl)
            val res = JSObject().apply {
                if (dup != null) {
                    this.put("exists", true)
                    this.put("uri", dup.optString("fileUri", ""))
                    this.put("title", dup.optString("title", ""))
                    this.put("fileName", dup.optString("fileName", ""))
                } else {
                    this.put("exists", false)
                }
            }
            call.resolve(res)
        }.start()
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
    fun clearMetadataCache(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS_METADATA_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            this.remove(PREF_METADATA_MAP)
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
        val autoDownloadOnPaste = call.getBoolean("autoDownloadOnPaste", false) ?: false
        val autoPlayNext = call.getBoolean("autoPlayNext", true) ?: true
        val repeatMode = call.getString("repeatMode") ?: "off"
        val floatingSide = call.getString("floatingSide") ?: "right"
        val floatingShowMs = call.getBoolean("floatingShowMs", true) ?: true
        val floatingAutoClear = call.getBoolean("floatingAutoClear", true) ?: true
        val floatingVibrate = call.getBoolean("floatingVibrate", true) ?: true
        val floatingHideInsideApp = call.getBoolean("floatingHideInsideApp", true) ?: true
        val autoCheckUpdates = call.getBoolean("autoCheckUpdates", true) ?: true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            this.putString("backend_url", backendUrl)
            this.putString("api_key", apiKey)
            this.putString("default_quality", defaultQuality)
            this.putBoolean("auto_download", autoDownload)
            this.putBoolean("auto_download_on_paste", autoDownloadOnPaste)
            this.putBoolean("auto_play_next", autoPlayNext)
            this.putString("repeat_mode", repeatMode)
            this.putString("floating_side", floatingSide)
            this.putBoolean("floating_show_ms", floatingShowMs)
            this.putBoolean("floating_auto_clear", floatingAutoClear)
            this.putBoolean("floating_vibrate", floatingVibrate)
            this.putBoolean("floating_hide_inside_app", floatingHideInsideApp)
            this.putBoolean("auto_check_updates", autoCheckUpdates)
            this.apply()
        }

        FloatingBubbleService.instance?.updateSettings(
            backendUrl,
            apiKey,
            defaultQuality,
            autoDownload,
            floatingAutoClear,
            floatingVibrate,
            floatingHideInsideApp,
            autoCheckUpdates
        )

        FloatingBubbleService.syncService(context)

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
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            ).apply {
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            val res = JSObject().apply {
                this.put("status", "no_permission")
                this.put("needPermission", true)
            }
            call.resolve(res)
            return
        }

        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                this.putBoolean("floating_bubble_enabled", true)
                this.apply()
            }
            FloatingBubbleService.syncService(ctx)

            val res = JSObject().apply {
                this.put("status", "running")
                this.put("needPermission", false)
            }
            call.resolve(res)
        } catch (e: Exception) {
            call.reject("Failed to start Edge Tab service: ${e.message ?: "Unknown error"}")
        }
    }

    @PluginMethod
    fun isBubbleRunning(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = (FloatingBubbleService.instance != null) && prefs.getBoolean("floating_bubble_enabled", false)
        val res = JSObject().apply {
            this.put("running", isRunning)
        }
        call.resolve(res)
    }

    @PluginMethod
    fun testBubbleNow(call: PluginCall) {
        FloatingBubbleService.testTabNow(5000L)
        val res = JSObject().apply {
            this.put("success", true)
        }
        call.resolve(res)
    }

    @PluginMethod
    fun stopBubble(call: PluginCall) {
        val ctx = context
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            this.putBoolean("floating_bubble_enabled", false)
            this.apply()
        }
        FloatingBubbleService.syncService(ctx)

        val res = JSObject().apply {
            this.put("status", "stopped")
        }
        call.resolve(res)
    }

    @PluginMethod
    fun requestAddQuickTile(call: PluginCall) {
        val ctx = context ?: activity ?: run {
            val res = JSObject().apply {
                this.put("supported", false)
                this.put("result", "manual")
            }
            call.resolve(res)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val statusBarManager = ctx.getSystemService(StatusBarManager::class.java)
                if (statusBarManager == null) {
                    val res = JSObject().apply {
                        this.put("supported", false)
                        this.put("result", "manual")
                    }
                    call.resolve(res)
                    return
                }

                val comp = ComponentName(ctx, QuickTileService::class.java)
                val icon = Icon.createWithResource(ctx, android.R.drawable.stat_sys_download)
                val executor = Executor { command -> command.run() }
                val callback = Consumer<Int> { resultCode ->
                    val ret = JSObject().apply {
                        this.put("supported", true)
                        when (resultCode) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                                this.put("result", "added")
                            }
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                                this.put("result", "already_added")
                            }
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                                this.put("result", "declined")
                            }
                            else -> {
                                this.put("result", "declined")
                            }
                        }
                    }
                    call.resolve(ret)
                }

                statusBarManager.requestAddTileService(
                    comp,
                    "OmniDrop Tab",
                    icon,
                    executor,
                    callback
                )
            } catch (t: Throwable) {
                val res = JSObject().apply {
                    this.put("supported", false)
                    this.put("result", "manual")
                    this.put("error", t.message ?: "Unknown error")
                }
                call.resolve(res)
            }
        } else {
            val res = JSObject().apply {
                this.put("supported", false)
                this.put("result", "manual")
            }
            call.resolve(res)
        }
    }

    @PluginMethod
    fun getKeepAliveStatus(call: PluginCall) {
        val ctx = context ?: activity ?: run {
            call.reject("Context unavailable")
            return
        }
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isServiceRunning = FloatingBubbleService.instance != null
        val lastPingTime = p.getLong(FloatingBubbleService.PREF_KEEP_ALIVE_LAST_TIME, 0L)
        val lastPingMs = p.getInt(FloatingBubbleService.PREF_KEEP_ALIVE_LAST_MS, -1)
        val lastPingStatus = p.getString(FloatingBubbleService.PREF_KEEP_ALIVE_LAST_STATUS, "IDLE") ?: "IDLE"
        val nextPingTime = p.getLong(FloatingBubbleService.PREF_KEEP_ALIVE_NEXT_TIME, 0L)

        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = p.getString(FloatingBubbleService.PREF_KEEP_ALIVE_LAST_DATE, "") ?: ""
        val pingsToday = if (savedDate == todayDateStr) {
            p.getInt(FloatingBubbleService.PREF_KEEP_ALIVE_PINGS_TODAY, 0)
        } else {
            0
        }

        val currentMonthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val savedMonth = p.getString(FloatingBubbleService.PREF_KEEP_ALIVE_ACTIVE_MONTH_KEY, "") ?: ""
        val totalMinutes = if (savedMonth == currentMonthKey) {
            p.getInt(FloatingBubbleService.PREF_KEEP_ALIVE_ACTIVE_MINUTES_MONTH, 0)
        } else {
            0
        }
        val estimatedHours = Math.round((totalMinutes / 60.0) * 10.0) / 10.0

        val ret = JSObject().apply {
            this.put("isRunning", isServiceRunning)
            this.put("lastPingTime", lastPingTime)
            this.put("lastPingMs", lastPingMs)
            this.put("lastPingStatus", lastPingStatus)
            this.put("nextPingTime", nextPingTime)
            this.put("pingsToday", pingsToday)
            this.put("estimatedHoursThisMonth", estimatedHours)
        }
        call.resolve(ret)
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
        val rawTitle = call.getString("title") ?: "video"
        val backendUrl = call.getString("backendUrl")
        val apiKey = call.getString("apiKey")

        if (videoUrl.isNullOrBlank()) {
            call.reject("Missing required parameter: url")
            return
        }

        if (backendUrl.isNullOrBlank()) {
            call.reject("Please configure your Render Backend URL in Settings before downloading on mobile.")
            return
        }

        val normalizedUrl = normalizeUrl(videoUrl)

        // In-flight download lock: prevent 2 concurrent downloads of the same link
        if (inFlightDownloads.putIfAbsent(normalizedUrl, true) != null) {
            call.reject("Download already in progress for this video link.")
            return
        }

        executeDownloadPipeline(normalizedUrl, quality, rawTitle, backendUrl, apiKey, call)
    }

    private fun executeDownloadPipeline(
        normalizedUrl: String,
        quality: String,
        rawTitle: String,
        rawBackendUrl: String,
        apiKey: String?,
        callToResolve: PluginCall? = null
    ) {
        val backendUrl = rawBackendUrl.trim().removeSuffix("/")
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
            var finalTitle = rawTitle
            var cleanPlatform = "Other"

            try {
                reportProgress(10, "Fetching video details...")

                // Step 1: Pre-fetch video metadata via POST /info
                var thumbnail = ""
                var uploader = "Creator"
                var platform = ""

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
                        this.put("url", normalizedUrl)
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
                        platform = json.optString("platform", "")
                    }
                    iConn.disconnect()
                } catch (_: Exception) {
                    // Fall back gracefully
                }

                // Clean platform: never use "Video" as platform
                val cleanPlatform = cleanPlatformName(platform, normalizedUrl)

                // Fallback title if /info failed or had no title: "<platform> video - <date and time>"
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val dateStr = sdf.format(Date())
                if (finalTitle.isBlank() || finalTitle.equals("video", ignoreCase = true) || finalTitle.startsWith("OmniDrop", ignoreCase = true)) {
                    finalTitle = "$cleanPlatform video - $dateStr"
                }

                reportProgress(20, "Connecting to download server...")

                val isAudio = quality.equals("audio", ignoreCase = true)
                val ext = if (isAudio) ".mp3" else ".mp4"
                val mediaType = if (isAudio) "audio/mpeg" else "video/mp4"

                // Clean filename: remove forbidden characters (\ / : * ? " < > | \r \n), shorten to 80 chars
                val cleanTitle = finalTitle
                    .replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_")
                    .replace("\\s+".toRegex(), " ")
                    .trim()
                    .take(80)
                    .ifBlank { "$cleanPlatform video - $dateStr" }
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
                    this.put("url", normalizedUrl)
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

                    callToResolve?.reject("Download failed: $cleanMsg")
                    return@Thread
                }

                val contentType = connection.contentType ?: ""
                if (contentType.contains("application/zip", ignoreCase = true)) {
                    reportProgress(35, "Extracting photo slideshow archive...")
                    val zis = ZipInputStream(connection.inputStream)
                    val resolver = context.contentResolver
                    val savedUris = ArrayList<Uri>()
                    var zipEntry = zis.nextEntry
                    var photoIndex = 0

                    while (zipEntry != null) {
                        val entryName = zipEntry.name
                        if (!zipEntry.isDirectory && (entryName.endsWith(".jpg", ignoreCase = true) || entryName.endsWith(".jpeg", ignoreCase = true) || entryName.endsWith(".png", ignoreCase = true) || entryName.endsWith(".webp", ignoreCase = true))) {
                            photoIndex++
                            val paddedNum = String.format(Locale.US, "%02d", photoIndex)
                            val cleanTitle = finalTitle
                                .replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_")
                                .replace("\\s+".toRegex(), " ")
                                .trim()
                                .take(60)
                                .ifBlank { "TikTok_photo" }
                            val photoDisplayName = "${cleanTitle}_$paddedNum.jpg"

                            val imgValues = ContentValues().apply {
                                this.put(MediaStore.MediaColumns.DISPLAY_NAME, photoDisplayName)
                                this.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    this.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OmniDrop")
                                    this.put(MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                            }

                            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            } else {
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            }

                            val itemUri = resolver.insert(collectionUri, imgValues)
                            if (itemUri != null) {
                                resolver.openOutputStream(itemUri)?.use { outStream ->
                                    val buf = ByteArray(8192)
                                    var len: Int
                                    while (zis.read(buf).also { len = it } != -1) {
                                        outStream.write(buf, 0, len)
                                    }
                                    outStream.flush()
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val updateValues = ContentValues().apply {
                                        this.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    }
                                    resolver.update(itemUri, updateValues, null, null)
                                }
                                savedUris.add(itemUri)
                                val pct = (35 + (photoIndex * 5)).coerceAtMost(95)
                                reportProgress(pct, "Saving photo $photoIndex to Pictures/OmniDrop...")
                            }
                        }
                        zis.closeEntry()
                        zipEntry = zis.nextEntry
                    }
                    zis.close()

                    val totalPhotos = savedUris.size
                    val firstUri = savedUris.firstOrNull()?.toString() ?: ""
                    val archiveFileName = "$finalTitle [TikTok photos]"

                    reportProgress(100, "Saved $totalPhotos photos to Pictures/OmniDrop!")

                    NotificationHelper.showDownloadFinishedNotification(
                        context,
                        "Saved $totalPhotos photos",
                        "Saved to Pictures/OmniDrop",
                        firstUri,
                        isImage = true
                    )

                    val metaObj = JSONObject().apply {
                        this.put("type", "photo")
                        this.put("title", finalTitle)
                        this.put("platform", "TikTok")
                        this.put("uploader", uploader)
                        this.put("thumbnailUrl", thumbnail)
                        this.put("sourceUrl", normalizedUrl)
                        this.put("imageCount", totalPhotos)
                        this.put("downloadTime", System.currentTimeMillis())
                        this.put("fileName", archiveFileName)
                        this.put("fileUri", firstUri)
                    }
                    saveVideoMetadata(context, archiveFileName, metaObj)

                    val entryObj = JSONObject().apply {
                        this.put("id", "dl_" + System.currentTimeMillis())
                        this.put("type", "photo")
                        this.put("url", normalizedUrl)
                        this.put("title", finalTitle)
                        this.put("thumbnail", thumbnail)
                        this.put("uploader", uploader)
                        this.put("platform", "TikTok")
                        this.put("quality", "photo")
                        this.put("imageCount", totalPhotos)
                        this.put("savedAt", System.currentTimeMillis())
                        this.put("fileName", archiveFileName)
                        this.put("fileUri", firstUri)
                    }
                    saveHistoryEntry(context, entryObj)
                    emitNativeDownloadFinished(entryObj)
                    removeFailedLinkByUrl(context, normalizedUrl)
                    recordDownloadStat(context, "TikTok", "photo", (totalPhotos.toLong() * 800L * 1024L))

                    val res = JSObject().apply {
                        this.put("fileName", archiveFileName)
                        this.put("uri", firstUri)
                        this.put("type", "photo")
                        this.put("imageCount", totalPhotos)
                    }
                    callToResolve?.resolve(res)
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
                    callToResolve?.reject("Failed to create MediaStore entry in phone gallery.")
                    return@Thread
                }

                outputStream = resolver.openOutputStream(targetUri)
                if (outputStream == null) {
                    callToResolve?.reject("Failed to open output stream to phone gallery.")
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
                        val mb = String.format(Locale.US, "%.1f", bytesCopied / (1024.0 * 1024.0))
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

                // Save to SharedPreferences metadata store (keyed by fileName, up to 500)
                val metaObj = JSONObject().apply {
                    this.put("title", finalTitle)
                    this.put("platform", cleanPlatform)
                    this.put("uploader", uploader)
                    this.put("thumbnailUrl", thumbnail)
                    this.put("sourceUrl", normalizedUrl)
                    this.put("downloadTime", System.currentTimeMillis())
                    this.put("fileName", fileName)
                    this.put("fileUri", targetUri.toString())
                }
                saveVideoMetadata(context, fileName, metaObj)

                // Save to native history and notify listeners
                val entryObj = JSONObject().apply {
                    this.put("id", "dl_" + System.currentTimeMillis())
                    this.put("url", normalizedUrl)
                    this.put("title", finalTitle)
                    this.put("thumbnail", thumbnail)
                    this.put("uploader", uploader)
                    this.put("platform", cleanPlatform)
                    this.put("quality", quality)
                    this.put("savedAt", System.currentTimeMillis())
                    this.put("fileName", fileName)
                    this.put("fileUri", targetUri.toString())
                }
                saveHistoryEntry(context, entryObj)
                emitNativeDownloadFinished(entryObj)
                removeFailedLinkByUrl(context, normalizedUrl)
                recordDownloadStat(context, cleanPlatform, if (isAudio) "audio" else "video", bytesCopied)

                val res = JSObject().apply {
                    this.put("fileName", fileName)
                    this.put("uri", targetUri.toString())
                }
                callToResolve?.resolve(res)

            } catch (e: Exception) {
                if (targetUri != null) {
                    try {
                        context.contentResolver.delete(targetUri, null, null)
                    } catch (_: Exception) {}
                }
                val rawErr = e.message ?: "Unknown error"
                recordFailedLink(context, normalizedUrl, cleanPlatform, finalTitle, rawErr)
                recordFailedStat(context)
                callToResolve?.reject("Native download error: $rawErr")
            } finally {
                inFlightDownloads.remove(normalizedUrl)
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
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            val video = androidx.core.content.ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VIDEO") == android.content.pm.PackageManager.PERMISSION_GRANTED
            val images = androidx.core.content.ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_IMAGES") == android.content.pm.PackageManager.PERMISSION_GRANTED
            video && images
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_IMAGES")
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val anyMissing = perms.any {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (anyMissing) {
                androidx.core.app.ActivityCompat.requestPermissions(activity, perms, 992)
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
                val allMeta = getAllMetadata(context)
                val metaByUri = HashMap<String, JSONObject>()
                val metaByName = HashMap<String, JSONObject>()

                val keys = allMeta.keys()
                while (keys.hasNext()) {
                    val fn = keys.next()
                    val item = allMeta.getJSONObject(fn)
                    val uri = item.optString("fileUri")
                    if (uri.isNotBlank()) metaByUri[uri] = item
                    metaByName[fn] = item
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

                        val metaEntry = metaByUri[uriStr] ?: metaByName[displayName]

                        // Order of platform determination:
                        // 1. Saved metadata
                        var platform = metaEntry?.optString("platform", "")?.trim() ?: ""
                        if (platform.equals("video", ignoreCase = true)) platform = ""

                        // 2. [platform] tag in filename
                        if (platform.isBlank()) {
                            val tagMatch = "\\[([^\\]]+)\\]".toRegex().find(displayName)
                            if (tagMatch != null) {
                                val tag = tagMatch.groupValues[1].trim()
                                if (!tag.equals("video", ignoreCase = true) && tag.isNotBlank()) {
                                    platform = when (tag.lowercase()) {
                                        "tiktok" -> "TikTok"
                                        "youtube" -> "YouTube"
                                        "instagram" -> "Instagram"
                                        "facebook" -> "Facebook"
                                        else -> tag
                                    }
                                }
                            }
                        }

                        // 3. Guess from sourceUrl domain
                        if (platform.isBlank()) {
                            val sourceUrl = metaEntry?.optString("sourceUrl", "") ?: ""
                            if (sourceUrl.isNotBlank()) {
                                val guessed = detectPlatformName(sourceUrl)
                                if (guessed != "Other") platform = guessed
                            }
                        }

                        // 4. "Other"
                        if (platform.isBlank() || platform.equals("video", ignoreCase = true)) {
                            platform = "Other"
                        }

                        // Title resolution: saved metadata -> clean filename
                        var title = metaEntry?.optString("title", "")?.trim() ?: ""
                        if (title.isBlank()) {
                            val base = displayName.substringBeforeLast(".")
                            val clean = base.replace("\\[[^\\]]+\\]".toRegex(), "").replace("_", " ").trim()
                            title = if (clean.isNotBlank()) clean else displayName
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

                        if (thumbBase64.isBlank() && metaEntry != null) {
                            thumbBase64 = metaEntry.optString("thumbnailUrl", metaEntry.optString("thumbnail", ""))
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
                            this.put("fileName", displayName)
                            if (metaEntry != null) {
                                this.put("sourceUrl", metaEntry.optString("sourceUrl", ""))
                            }
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
        val urisArray = call.getArray("uris")
        val singleUri = call.getString("uri")
        val targetUris = mutableListOf<String>()

        if (urisArray != null) {
            for (i in 0 until urisArray.length()) {
                val u = urisArray.optString(i)
                if (!u.isNullOrBlank()) targetUris.add(u)
            }
        } else if (!singleUri.isNullOrBlank()) {
            targetUris.add(singleUri)
        }

        if (targetUris.isEmpty()) {
            call.reject("Missing required parameter: uri")
            return
        }
        performMediaDelete(call, targetUris)
    }

    @PluginMethod
    fun renameVideo(call: PluginCall) {
        val uriStr = call.getString("uri")
        val newTitle = call.getString("newTitle")?.trim() ?: ""
        val newPlatform = call.getString("newPlatform")?.trim() ?: "Other"

        if (uriStr.isNullOrBlank() || newTitle.isBlank()) {
            call.reject("Missing required parameters: uri or newTitle")
            return
        }

        Thread {
            try {
                val uri = Uri.parse(uriStr)
                val cv = ContentValues().apply {
                    this.put(MediaStore.Video.Media.TITLE, newTitle)
                }
                val updated = context.contentResolver.update(uri, cv, null, null)
                if (updated > 0) {
                    applyRenameSuccess(call, uriStr, newTitle, newPlatform)
                } else {
                    requestWritePermissionForRename(call, uri, uriStr, newTitle, newPlatform)
                }
            } catch (sec: SecurityException) {
                requestWritePermissionForRename(call, Uri.parse(uriStr), uriStr, newTitle, newPlatform, sec)
            } catch (e: Exception) {
                call.reject("Failed to rename video: ${e.message ?: "Unknown error"}")
            }
        }.start()
    }

    @PluginMethod
    fun playVideo(call: PluginCall) {
        val uriStr = call.getString("uri")
        val title = call.getString("title") ?: "OmniDrop Video"
        val initialIndex = call.getInt("initialIndex", 0) ?: 0
        val playlistArray = call.getArray("playlist")

        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        try {
            val list = mutableListOf<VideoPlayerActivity.PlaylistItem>()
            if (playlistArray != null) {
                for (i in 0 until playlistArray.length()) {
                    val obj = playlistArray.optJSONObject(i)
                    if (obj != null) {
                        val u = obj.optString("uri", "")
                        val t = obj.optString("title", "Video ${i + 1}")
                        val p = obj.optString("platform", "Other")
                        val fn = obj.optString("fileName", "")
                        val sz = obj.optLong("size", 0L)
                        if (u.isNotBlank()) {
                            list.add(VideoPlayerActivity.PlaylistItem(u, t, p, if (fn.isNotBlank()) fn else null, sz))
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                list.add(VideoPlayerActivity.PlaylistItem(uriStr, title, "Other", null, 0L))
            }

            // In-memory playlist holder to prevent TransactionTooLargeException
            VideoPlayerActivity.setPlaylist(list)

            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                this.data = Uri.parse(uriStr)
                this.putExtra("video_title", title)
                this.putExtra("current_index", initialIndex)
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

    @PluginMethod
    fun getPhotoLibrary(call: PluginCall) {
        Thread {
            try {
                val allMeta = getAllMetadata(context)
                val metaByUri = HashMap<String, JSONObject>()
                val metaByName = HashMap<String, JSONObject>()

                val keys = allMeta.keys()
                while (keys.hasNext()) {
                    val fn = keys.next()
                    val item = allMeta.getJSONObject(fn)
                    val uri = item.optString("fileUri")
                    if (uri.isNotBlank()) metaByUri[uri] = item
                    metaByName[fn] = item
                }

                val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val projectionList = mutableListOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projectionList.add(MediaStore.Images.Media.RELATIVE_PATH)
                }
                projectionList.add(MediaStore.Images.Media.DATA)

                val projection = projectionList.toTypedArray()
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
                } else {
                    "${MediaStore.Images.Media.DATA} LIKE ?"
                }
                val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf("%Pictures/OmniDrop%", "%/Pictures/OmniDrop/%")
                } else {
                    arrayOf("%/Pictures/OmniDrop/%")
                }
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                val photosArray = JSArray()
                val cursor = context.contentResolver.query(collectionUri, projection, selection, selectionArgs, sortOrder)

                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                    while (c.moveToNext()) {
                        val idVal = c.getLong(idCol)
                        val displayName = c.getString(nameCol) ?: "photo.jpg"
                        val sizeBytes = c.getLong(sizeCol)
                        val dateAddedSec = c.getLong(dateCol)
                        val dateAddedMs = dateAddedSec * 1000L
                        val width = c.getInt(widthCol)
                        val height = c.getInt(heightCol)

                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idVal)
                        val uriStr = contentUri.toString()

                        // Post title extraction:
                        // Strip " [TikTok photos]" tag and trailing numbers "_01"
                        val baseName = displayName.substringBeforeLast(".")
                        var extractedPostTitle = baseName
                            .replace("\\[TikTok photos\\]".toRegex(RegexOption.IGNORE_CASE), "")
                            .replace("_\\d+$".toRegex(), "")
                            .trim()
                        if (extractedPostTitle.isBlank()) extractedPostTitle = displayName

                        val metaEntry = metaByUri[uriStr] ?: metaByName[displayName] ?: metaByName["$extractedPostTitle [TikTok photos]"]
                        val postTitle = metaEntry?.optString("title", "")?.takeIf { it.isNotBlank() } ?: extractedPostTitle
                        val platform = metaEntry?.optString("platform", "TikTok")?.takeIf { it.isNotBlank() } ?: "TikTok"
                        val sourceUrl = metaEntry?.optString("sourceUrl", "") ?: ""

                        var thumbBase64 = ""
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val bm = context.contentResolver.loadThumbnail(contentUri, Size(256, 256), null)
                                val baos = ByteArrayOutputStream()
                                bm.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                                val bytes = baos.toByteArray()
                                thumbBase64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }
                        } catch (_: Exception) {}

                        val pObj = JSObject().apply {
                            this.put("id", idVal.toString())
                            this.put("uri", uriStr)
                            this.put("fileName", displayName)
                            this.put("size", sizeBytes)
                            this.put("dateAdded", dateAddedMs)
                            this.put("width", width)
                            this.put("height", height)
                            this.put("postTitle", postTitle)
                            this.put("thumbnail", thumbBase64)
                            this.put("platform", platform)
                            this.put("sourceUrl", sourceUrl)
                        }
                        photosArray.put(pObj)
                    }
                }

                val ret = JSObject().apply {
                    this.put("photos", photosArray)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to query photo library: ${e.message ?: "Unknown error"}")
            }
        }.start()
    }

    @PluginMethod
    fun deletePhotos(call: PluginCall) {
        val urisArray = call.getArray("uris")
        val singleUri = call.getString("uri")
        val targetUris = mutableListOf<String>()

        if (urisArray != null) {
            for (i in 0 until urisArray.length()) {
                val u = urisArray.optString(i)
                if (!u.isNullOrBlank()) targetUris.add(u)
            }
        } else if (!singleUri.isNullOrBlank()) {
            targetUris.add(singleUri)
        }

        if (targetUris.isEmpty()) {
            call.reject("Missing required parameter: uris")
            return
        }

        performMediaDelete(call, targetUris)
    }

    @PluginMethod
    fun openPhotoViewer(call: PluginCall) {
        val urisArray = call.getArray("uris")
        val initialIndex = call.getInt("index", 0) ?: 0
        val postTitle = call.getString("postTitle") ?: "OmniDrop Photos"

        val uriList = ArrayList<String>()
        if (urisArray != null) {
            for (i in 0 until urisArray.length()) {
                val u = urisArray.optString(i)
                if (!u.isNullOrBlank()) uriList.add(u)
            }
        }
        val singleUri = call.getString("uri")
        if (uriList.isEmpty() && !singleUri.isNullOrBlank()) {
            uriList.add(singleUri)
        }

        if (uriList.isEmpty()) {
            call.reject("Missing required parameter: uris")
            return
        }

        try {
            val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                this.putStringArrayListExtra("photo_uris", uriList)
                this.putExtra("initial_index", initialIndex)
                this.putExtra("post_title", postTitle)
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            val ret = JSObject().apply {
                this.put("started", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to launch PhotoViewerActivity: ${e.message ?: "Unknown error"}")
        }
    }

    @PluginMethod
    fun getAppVersion(call: PluginCall) {
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionName = pInfo.versionName ?: "1.0.0"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            val ret = JSObject().apply {
                this.put("versionName", versionName)
                this.put("versionCode", versionCode)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject().apply {
                this.put("versionName", "1.0.0")
                this.put("versionCode", 1)
            }
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun downloadAndInstallUpdate(call: PluginCall) {
        val downloadUrl = call.getString("url")
        val expectedSize = call.getLong("size") ?: (call.getInt("size")?.toLong() ?: 0L)

        if (downloadUrl.isNullOrBlank()) {
            call.reject("Missing required parameter: url")
            return
        }

        Thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null

            try {
                val updatesDir = java.io.File(context.cacheDir, "updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }
                val apkFile = java.io.File(updatesDir, "OmniDrop.apk")

                // If file already exists with exact size, skip re-download
                val alreadyDownloaded = apkFile.exists() && (expectedSize > 0 && apkFile.length() == expectedSize)

                if (!alreadyDownloaded) {
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    var currentUrl = downloadUrl
                    var redirectCount = 0
                    val maxRedirects = 6

                    while (redirectCount < maxRedirects) {
                        val u = URL(currentUrl)
                        connection = (u.openConnection() as HttpURLConnection).apply {
                            this.instanceFollowRedirects = true
                            this.connectTimeout = 15000
                            this.readTimeout = 30000
                            this.setRequestProperty("User-Agent", "OmniDrop-Android")
                            this.setRequestProperty("Accept", "application/octet-stream")
                        }

                        val code = connection.responseCode
                        if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                            code == HttpURLConnection.HTTP_MOVED_TEMP ||
                            code == HttpURLConnection.HTTP_SEE_OTHER ||
                            code == 307 || code == 308) {
                            val location = connection.getHeaderField("Location")
                            connection.disconnect()
                            if (location.isNullOrBlank()) {
                                throw java.io.IOException("Redirect with empty Location header")
                            }
                            currentUrl = if (location.startsWith("http")) location else URL(u, location).toString()
                            redirectCount++
                        } else if (code == HttpURLConnection.HTTP_OK) {
                            break
                        } else {
                            throw java.io.IOException("HTTP error $code from update server")
                        }
                    }

                    val serverContentLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        connection!!.contentLengthLong
                    } else {
                        connection!!.contentLength.toLong()
                    }
                    val totalBytes = if (expectedSize > 0) expectedSize else (if (serverContentLength > 0) serverContentLength else 0L)

                    inputStream = connection!!.inputStream
                    outputStream = java.io.FileOutputStream(apkFile)

                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    var bytesRead: Int
                    var lastReportPercent = -1

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        if (percent != lastReportPercent || bytesDownloaded == totalBytes) {
                            lastReportPercent = percent
                            val mbDownloaded = bytesDownloaded / (1024.0 * 1024.0)
                            val mbTotal = totalBytes / (1024.0 * 1024.0)
                            val formattedMb = if (totalBytes > 0) {
                                String.format(Locale.US, "%.1f / %.1f MB", mbDownloaded, mbTotal)
                            } else {
                                String.format(Locale.US, "%.1f MB", mbDownloaded)
                            }

                            val progressObj = JSObject().apply {
                                this.put("percent", percent)
                                this.put("bytesDownloaded", bytesDownloaded)
                                this.put("totalBytes", totalBytes)
                                this.put("formattedMb", formattedMb)
                            }
                            notifyListeners("updateProgress", progressObj, true)
                        }
                    }

                    outputStream.flush()
                }

                // Check final file size
                val finalLength = apkFile.length()
                if (expectedSize > 0 && finalLength != expectedSize) {
                    apkFile.delete()
                    throw java.io.IOException("Download incomplete: file size ($finalLength) does not match expected size ($expectedSize)")
                } else if (finalLength < 1000) {
                    apkFile.delete()
                    throw java.io.IOException("Downloaded APK file is corrupted or too small ($finalLength bytes)")
                }

                // Final 100% progress report
                val mbFinal = finalLength / (1024.0 * 1024.0)
                val finalProgress = JSObject().apply {
                    this.put("percent", 100)
                    this.put("bytesDownloaded", finalLength)
                    this.put("totalBytes", finalLength)
                    this.put("formattedMb", String.format(Locale.US, "%.1f / %.1f MB", mbFinal, mbFinal))
                }
                notifyListeners("updateProgress", finalProgress, true)

                // Check install permission (API 26+)
                val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else {
                    true
                }

                if (!canInstall) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            this.setData(Uri.parse("package:${context.packageName}"))
                            this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(manageIntent)
                    }
                    val ret = JSObject().apply {
                        this.put("started", false)
                        this.put("needPermission", true)
                        this.put("message", "Turn on Allow from this source, then come back and tap Install again")
                    }
                    call.resolve(ret)
                    return@Thread
                }

                // Trigger package installer through FileProvider
                val authority = "${context.packageName}.fileprovider"
                val apkUri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    this.setDataAndType(apkUri, "application/vnd.android.package-archive")
                    this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(installIntent)

                val ret = JSObject().apply {
                    this.put("started", true)
                    this.put("needPermission", false)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Update install failed: ${e.message ?: "Unknown error"}")
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                connection?.disconnect()
            }
        }.start()
    }

    @PluginMethod
    fun getLastCrash(call: PluginCall) {
        try {
            val file = java.io.File(context.filesDir, "last_crash.txt")
            val text = if (file.exists()) file.readText(Charsets.UTF_8) else ""
            val ret = JSObject().apply {
                this.put("crash", text)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject().apply {
                this.put("crash", "")
            }
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun clearLastCrash(call: PluginCall) {
        try {
            val file = java.io.File(context.filesDir, "last_crash.txt")
            if (file.exists()) {
                file.delete()
            }
            val ret = JSObject().apply {
                this.put("cleared", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject().apply {
                this.put("cleared", false)
            }
            call.resolve(ret)
        }
    }

    // ==================== FAILED LINKS PLUGIN METHODS ====================
    @PluginMethod
    fun getFailedLinks(call: PluginCall) {
        val arr = getFailedLinksArray(context)
        val ret = JSObject().apply {
            val jsArr = JSArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val obj = JSObject().apply {
                    this.put("id", item.optString("id"))
                    this.put("url", item.optString("url"))
                    this.put("platform", item.optString("platform"))
                    this.put("title", item.optString("title"))
                    this.put("reason", item.optString("reason"))
                    this.put("failedAt", item.optLong("failedAt"))
                    this.put("attempts", item.optInt("attempts", 1))
                }
                jsArr.put(obj)
            }
            this.put("failedLinks", jsArr)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun removeFailedLink(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrBlank()) {
            call.reject("Missing required parameter: id")
            return
        }
        val removed = removeFailedLinkById(context, id)
        val ret = JSObject().apply {
            this.put("removed", removed)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun clearFailedLinks(call: PluginCall) {
        clearFailedLinks(context)
        val ret = JSObject().apply {
            this.put("cleared", true)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun retryFailedLink(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrBlank()) {
            call.reject("Missing required parameter: id")
            return
        }
        val arr = getFailedLinksArray(context)
        var targetUrl: String? = null
        var targetTitle: String? = null
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optString("id") == id) {
                targetUrl = item.optString("url")
                targetTitle = item.optString("title")
                break
            }
        }
        if (targetUrl.isNullOrBlank()) {
            call.reject("Failed link not found: $id")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val defaultQuality = prefs.getString("default_quality", "best") ?: "best"

        if (backendUrl.isBlank()) {
            call.reject("Please configure Render Backend URL in Settings before retrying.")
            return
        }

        val normUrl = normalizeUrl(targetUrl)
        if (inFlightDownloads.putIfAbsent(normUrl, true) != null) {
            call.reject("Download already in progress for this link.")
            return
        }

        executeDownloadPipeline(normUrl, defaultQuality, targetTitle ?: "video", backendUrl, apiKey, null)
        val ret = JSObject().apply {
            this.put("started", true)
        }
        call.resolve(ret)
    }

    // ==================== FAVORITES & COLLECTIONS PLUGIN METHODS ====================
    @PluginMethod
    fun getFavoritesAndCollections(call: PluginCall) {
        val favs = getFavoritesArray(context)
        val cols = getCollectionsArray(context)
        val ret = JSObject().apply {
            val favJsArr = JSArray()
            for (i in 0 until favs.length()) {
                favJsArr.put(favs.getString(i))
            }
            this.put("favorites", favJsArr)

            val colsJsArr = JSArray()
            for (i in 0 until cols.length()) {
                val c = cols.getJSONObject(i)
                val cObj = JSObject().apply {
                    this.put("id", c.optString("id"))
                    this.put("name", c.optString("name"))
                    this.put("createdAt", c.optLong("createdAt"))
                    val vkArr = JSArray()
                    val vkJson = c.optJSONArray("videoKeys") ?: JSONArray()
                    for (j in 0 until vkJson.length()) {
                        vkArr.put(vkJson.getString(j))
                    }
                    this.put("videoKeys", vkArr)
                }
                colsJsArr.put(cObj)
            }
            this.put("collections", colsJsArr)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun toggleFavorite(call: PluginCall) {
        val mediaKey = call.getString("mediaKey")
        if (mediaKey.isNullOrBlank()) {
            call.reject("Missing required parameter: mediaKey")
            return
        }
        val isFav = toggleFavorite(context, mediaKey)
        val ret = JSObject().apply {
            this.put("mediaKey", mediaKey)
            this.put("isFavorite", isFav)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun createCollection(call: PluginCall) {
        val name = call.getString("name")?.trim()
        if (name.isNullOrBlank()) {
            call.reject("Missing required parameter: name")
            return
        }
        val cols = getCollectionsArray(context)
        val id = "col_" + System.currentTimeMillis()
        val newCol = JSONObject().apply {
            this.put("id", id)
            this.put("name", name)
            this.put("videoKeys", JSONArray())
            this.put("createdAt", System.currentTimeMillis())
        }
        cols.put(newCol)
        saveCollectionsArray(context, cols)

        val ret = JSObject().apply {
            val cObj = JSObject().apply {
                this.put("id", id)
                this.put("name", name)
                this.put("videoKeys", JSArray())
                this.put("createdAt", newCol.getLong("createdAt"))
            }
            this.put("collection", cObj)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun renameCollection(call: PluginCall) {
        val id = call.getString("id")
        val name = call.getString("name")?.trim()
        if (id.isNullOrBlank() || name.isNullOrBlank()) {
            call.reject("Missing required parameters: id or name")
            return
        }
        val cols = getCollectionsArray(context)
        var renamed = false
        for (i in 0 until cols.length()) {
            val c = cols.getJSONObject(i)
            if (c.optString("id") == id) {
                c.put("name", name)
                renamed = true
                break
            }
        }
        if (renamed) {
            saveCollectionsArray(context, cols)
        }
        val ret = JSObject().apply {
            this.put("renamed", renamed)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun deleteCollection(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrBlank()) {
            call.reject("Missing required parameter: id")
            return
        }
        val cols = getCollectionsArray(context)
        val newCols = JSONArray()
        var deleted = false
        for (i in 0 until cols.length()) {
            val c = cols.getJSONObject(i)
            if (c.optString("id") == id) {
                deleted = true
            } else {
                newCols.put(c)
            }
        }
        if (deleted) {
            saveCollectionsArray(context, newCols)
        }
        val ret = JSObject().apply {
            this.put("deleted", deleted)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun addToCollection(call: PluginCall) {
        val collectionId = call.getString("collectionId")
        val mediaKeys = call.getArray("mediaKeys")
        if (collectionId.isNullOrBlank() || mediaKeys == null) {
            call.reject("Missing required parameters: collectionId or mediaKeys")
            return
        }
        val cols = getCollectionsArray(context)
        var changed = false
        for (i in 0 until cols.length()) {
            val c = cols.getJSONObject(i)
            if (c.optString("id") == collectionId) {
                val vKeys = c.optJSONArray("videoKeys") ?: JSONArray()
                val currentSet = HashSet<String>()
                for (j in 0 until vKeys.length()) {
                    currentSet.add(vKeys.getString(j))
                }
                for (j in 0 until mediaKeys.length()) {
                    val k = mediaKeys.getString(j)
                    if (k.isNotBlank() && currentSet.add(k)) {
                        vKeys.put(k)
                        changed = true
                    }
                }
                c.put("videoKeys", vKeys)
                break
            }
        }
        if (changed) {
            saveCollectionsArray(context, cols)
        }
        val ret = JSObject().apply {
            this.put("success", true)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun removeFromCollection(call: PluginCall) {
        val collectionId = call.getString("collectionId")
        val mediaKeys = call.getArray("mediaKeys")
        if (collectionId.isNullOrBlank() || mediaKeys == null) {
            call.reject("Missing required parameters: collectionId or mediaKeys")
            return
        }
        val keysToRemove = HashSet<String>()
        for (i in 0 until mediaKeys.length()) {
            keysToRemove.add(mediaKeys.getString(i))
        }

        val cols = getCollectionsArray(context)
        var changed = false
        for (i in 0 until cols.length()) {
            val c = cols.getJSONObject(i)
            if (c.optString("id") == collectionId) {
                val vKeys = c.optJSONArray("videoKeys") ?: JSONArray()
                val newKeys = JSONArray()
                for (j in 0 until vKeys.length()) {
                    val k = vKeys.getString(j)
                    if (keysToRemove.contains(k)) {
                        changed = true
                    } else {
                        newKeys.put(k)
                    }
                }
                c.put("videoKeys", newKeys)
                break
            }
        }
        if (changed) {
            saveCollectionsArray(context, cols)
        }
        val ret = JSObject().apply {
            this.put("success", true)
        }
        call.resolve(ret)
    }

    // ==================== STATS PLUGIN METHODS ====================
    @PluginMethod
    fun getDownloadStats(call: PluginCall) {
        val stats = getStatsJson(context)
        val ret = JSObject().apply {
            this.put("totalDownloads", stats.optLong("totalDownloads", 0L))
            this.put("totalBytes", stats.optLong("totalBytes", 0L))
            this.put("videoCount", stats.optLong("videoCount", 0L))
            this.put("photoCount", stats.optLong("photoCount", 0L))
            this.put("failedCount", stats.optLong("failedCount", 0L))
            this.put("successRate", stats.optDouble("successRate", 100.0))
            this.put("busiestDay", stats.optString("busiestDay", "None"))
            this.put("averageBytes", stats.optLong("averageBytes", 0L))

            val platsObj = stats.optJSONObject("platformCounts") ?: JSONObject()
            val platRet = JSObject()
            val pKeys = platsObj.keys()
            while (pKeys.hasNext()) {
                val pk = pKeys.next()
                platRet.put(pk, platsObj.optLong(pk, 0L))
            }
            this.put("platformCounts", platRet)

            val dailyArr = stats.optJSONArray("dailyStats") ?: JSONArray()
            val dailyRet = JSArray()
            for (i in 0 until dailyArr.length()) {
                val d = dailyArr.getJSONObject(i)
                val dObj = JSObject().apply {
                    this.put("date", d.optString("date"))
                    this.put("downloads", d.optLong("downloads", 0L))
                    this.put("bytes", d.optLong("bytes", 0L))
                }
                dailyRet.put(dObj)
            }
            this.put("dailyStats", dailyRet)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun resetDownloadStats(call: PluginCall) {
        resetStats(context)
        val ret = JSObject().apply {
            this.put("reset", true)
        }
        call.resolve(ret)
    }

    // ==================== AUDIO EXTRACTOR PLUGIN METHODS ====================
    @PluginMethod
    fun extractAudio(call: PluginCall) {
        val uriStr = call.getString("uri")
        val title = call.getString("title") ?: "OmniDrop_Audio"
        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        val srcUri = Uri.parse(uriStr)
        extractAudioDirect(
            context,
            srcUri,
            title,
            onProgress = { pct ->
                val data = JSObject().apply {
                    this.put("percent", pct)
                    this.put("title", title)
                }
                notifyListeners("audioExtractProgress", data)
            },
            onSuccess = { outUri, fileName ->
                val ret = JSObject().apply {
                    this.put("success", true)
                    this.put("uri", outUri.toString())
                    this.put("fileName", fileName)
                    this.put("mimeType", "audio/mp4")
                    this.put("relativePath", "Music/OmniDrop")
                }
                call.resolve(ret)
            },
            onError = { err ->
                call.reject(err)
            }
        )
    }

    @PluginMethod
    fun openAudio(call: PluginCall) {
        val uriStr = call.getString("uri")
        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        try {
            val uri = Uri.parse(uriStr)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.setDataAndType(uri, "audio/*")
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val ret = JSObject().apply {
                this.put("success", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("No audio player found to open file: ${e.message ?: "Unknown error"}")
        }
    }

    @PluginMethod
    fun shareAudio(call: PluginCall) {
        val uriStr = call.getString("uri")
        val title = call.getString("title") ?: "OmniDrop Audio"
        if (uriStr.isNullOrBlank()) {
            call.reject("Missing required parameter: uri")
            return
        }
        try {
            val uri = Uri.parse(uriStr)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                this.type = "audio/*"
                this.putExtra(Intent.EXTRA_STREAM, uri)
                this.putExtra(Intent.EXTRA_SUBJECT, title)
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share audio").apply {
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            val ret = JSObject().apply {
                this.put("shared", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to share audio: ${e.message ?: "Unknown error"}")
        }
    }
}

