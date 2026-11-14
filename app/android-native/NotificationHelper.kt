package com.omnidrop.app.plugin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {

    private const val CHANNEL_PERSISTENT_ID = "omnidrop_persistent_channel"
    const val CHANNEL_PROGRESS_ID = "omnidrop_download_progress"
    const val CHANNEL_RESULTS_ID = "omnidrop_download_results"

    private const val NOTIFICATION_PERSISTENT_ID = 2001
    private const val NOTIFICATION_RESULTS_SUMMARY_ID = 4000
    private const val GROUP_KEY_RESULTS = "com.omnidrop.app.DOWNLOAD_RESULTS"
    private const val PREFS_NAME = "omnidrop_native_prefs"

    private val notificationIdSeq = AtomicInteger(3001)
    private val activeFinishedTitles = ConcurrentHashMap<Int, String>()

    fun nextNotificationId(): Int {
        return notificationIdSeq.getAndIncrement()
    }

    fun canPostNotifications(context: Context): Boolean {
        return try {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) {
                return false
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val persistentChannel = NotificationChannel(
                    CHANNEL_PERSISTENT_ID,
                    "OmniDrop Quick Actions",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    this.description = "Quick clipboard capture and floating controls"
                    this.setShowBadge(false)
                    this.setSound(null, null)
                    this.enableVibration(false)
                }

                val progressChannel = NotificationChannel(
                    CHANNEL_PROGRESS_ID,
                    "Download progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    this.description = "Ongoing download progress"
                    this.setShowBadge(false)
                    this.setSound(null, null)
                    this.enableVibration(false)
                }

                val resultsChannel = NotificationChannel(
                    CHANNEL_RESULTS_ID,
                    "Download results",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    this.description = "Completed and failed downloads"
                    this.enableVibration(true)
                }

                manager.createNotificationChannel(persistentChannel)
                manager.createNotificationChannel(progressChannel)
                manager.createNotificationChannel(resultsChannel)
            } catch (_: Throwable) {}
        }
    }

    private fun pendingImmutableFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    }

    private fun safeNotify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPostNotifications(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
        } catch (_: Throwable) {}
    }

    private fun buildMainActivityIntent(context: Context, openTab: String? = null, message: String? = null): Intent {
        val mainClass = try {
            Class.forName("com.omnidrop.app.MainActivity")
        } catch (_: Throwable) {
            null
        }
        val intent = if (mainClass != null) Intent(context, mainClass) else Intent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!openTab.isNullOrBlank()) {
            intent.putExtra("open_tab", openTab)
        }
        if (!message.isNullOrBlank()) {
            intent.putExtra("notif_message", message)
        }
        return intent
    }

    /**
     * Shows persistent notification with a 'Download from clipboard' action button
     */
    fun showPersistentClipboardNotification(context: Context, title: String, content: String) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val clipboardIntent = Intent(context, ClipboardBridgeActivity::class.java).apply {
            this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val clipboardPendingIntent = PendingIntent.getActivity(
            context,
            1,
            clipboardIntent,
            pendingImmutableFlags()
        )

        val openAppIntent = buildMainActivityIntent(context)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            pendingImmutableFlags()
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PERSISTENT_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_input_get,
                "Download from clipboard",
                clipboardPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)

        safeNotify(context, NOTIFICATION_PERSISTENT_ID, builder)
    }

    fun cancelPersistentNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_PERSISTENT_ID)
        } catch (_: Throwable) {}
    }

    /**
     * Ongoing progress notification with CANCEL action targeting FloatingBubbleService
     */
    fun showDownloadProgressNotification(
        context: Context,
        notifId: Int,
        downloadUrl: String,
        title: String,
        percent: Int,
        speedMb: Double,
        isIndeterminate: Boolean,
        statusOverride: String? = null
    ) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val cleanTitle = title.ifBlank { "OmniDrop Download" }
        val text = if (isIndeterminate) {
            statusOverride?.takeIf { it.isNotBlank() } ?: "Connecting..."
        } else {
            val clampedPct = percent.coerceIn(0, 100)
            val spd = if (speedMb > 0.05) String.format(Locale.US, "%.1f MB/s", speedMb) else "0.0 MB/s"
            "$clampedPct% · $spd"
        }

        val cancelIntent = Intent(context, FloatingBubbleService::class.java).apply {
            this.action = FloatingBubbleService.ACTION_NOTIF_CANCEL
            this.putExtra(FloatingBubbleService.EXTRA_DOWNLOAD_URL, downloadUrl)
            this.putExtra(FloatingBubbleService.EXTRA_NOTIF_ID, notifId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            notifId * 10 + 1,
            cancelIntent,
            pendingImmutableFlags()
        )

        val openAppIntent = buildMainActivityIntent(context)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notifId * 10 + 2,
            openAppIntent,
            pendingImmutableFlags()
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS_ID)
            .setContentTitle(cleanTitle)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent.coerceIn(0, 100), isIndeterminate)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL", cancelPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        safeNotify(context, notifId, builder)
    }

    /**
     * Shows notification when a video, audio, or photo post download finishes.
     * Supports BigPicture thumbnail, OPEN / SHARE / DELETE actions, grouping summary, and pref check.
     */
    fun showDownloadFinishedNotification(
        context: Context,
        title: String,
        message: String,
        filePath: String?,
        isImage: Boolean = false,
        isAudio: Boolean = false,
        bytesCopied: Long = 0L,
        folderPath: String? = null,
        thumbnailUrl: String? = null,
        photoUris: List<String> = emptyList()
    ) {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
        if (prefs?.getBoolean("notify_download_finish", true) == false) {
            return
        }

        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val notifId = nextNotificationId()
        val cleanTitle = title.ifBlank { if (isImage) "Saved photos" else "OmniDrop Video" }

        val resolvedPhotoUris = ArrayList<String>().apply {
            for (u in photoUris) {
                if (u.isNotBlank() && !this.contains(u)) {
                    this.add(u)
                }
            }
            if (this.isEmpty() && isImage && !filePath.isNullOrBlank()) {
                this.add(filePath)
            }
        }

        val targetUrisForDelete = ArrayList<String>().apply {
            if (isImage) {
                this.addAll(resolvedPhotoUris)
            } else if (!filePath.isNullOrBlank()) {
                this.add(filePath)
            }
        }

        val contentText = when {
            isImage -> {
                val count = resolvedPhotoUris.size.coerceAtLeast(1)
                if (count == 1) "Saved 1 photo" else "Saved $count photos"
            }
            !folderPath.isNullOrBlank() && bytesCopied > 0L -> {
                "Saved to $folderPath · ${formatSizeLabel(bytesCopied)}"
            }
            !folderPath.isNullOrBlank() -> {
                "Saved to $folderPath"
            }
            bytesCopied > 0L -> {
                val defaultFolder = if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"
                "Saved to $defaultFolder · ${formatSizeLabel(bytesCopied)}"
            }
            else -> message.ifBlank { "Saved to Gallery" }
        }

        val openIntent = buildOpenIntent(
            context = context,
            notifId = notifId,
            title = cleanTitle,
            filePath = filePath,
            isImage = isImage,
            photoUris = resolvedPhotoUris
        )
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notifId * 10 + 1,
            openIntent,
            pendingImmutableFlags()
        )

        val shareIntent = buildShareIntent(
            title = cleanTitle,
            filePath = filePath,
            isImage = isImage,
            isAudio = isAudio,
            photoUris = resolvedPhotoUris
        )
        val sharePendingIntent = if (shareIntent != null) {
            PendingIntent.getActivity(
                context,
                notifId * 10 + 2,
                shareIntent,
                pendingImmutableFlags()
            )
        } else {
            null
        }

        val deleteIntent = Intent(context, FloatingBubbleService::class.java).apply {
            this.action = FloatingBubbleService.ACTION_NOTIF_DELETE
            this.putStringArrayListExtra(FloatingBubbleService.EXTRA_URI_LIST, targetUrisForDelete)
            this.putExtra(FloatingBubbleService.EXTRA_NOTIF_ID, notifId)
        }
        val deletePendingIntent = PendingIntent.getService(
            context,
            notifId * 10 + 3,
            deleteIntent,
            pendingImmutableFlags()
        )

        fun buildBaseBuilder(): NotificationCompat.Builder {
            return NotificationCompat.Builder(context, CHANNEL_RESULTS_ID)
                .setContentTitle(cleanTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .setGroup(GROUP_KEY_RESULTS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(android.R.drawable.ic_media_play, "OPEN", openPendingIntent)
                .apply {
                    if (sharePendingIntent != null) {
                        this.addAction(android.R.drawable.ic_menu_share, "SHARE", sharePendingIntent)
                    }
                    if (targetUrisForDelete.isNotEmpty()) {
                        this.addAction(android.R.drawable.ic_menu_delete, "DELETE", deletePendingIntent)
                    }
                }
        }

        activeFinishedTitles[notifId] = cleanTitle
        safeNotify(context, notifId, buildBaseBuilder())
        updateResultsSummary(context)

        // Load thumbnail bitmap asynchronously so BigPictureStyle never blocks UI thread
        val firstMediaUri = if (isImage) resolvedPhotoUris.firstOrNull() else filePath
        if (!thumbnailUrl.isNullOrBlank() || !firstMediaUri.isNullOrBlank()) {
            val appCtx = context.applicationContext
            Thread {
                try {
                    val bmp = loadNotificationThumbnail(appCtx, thumbnailUrl, firstMediaUri, isImage, isAudio)
                    if (bmp != null && activeFinishedTitles.containsKey(notifId)) {
                        val updatedBuilder = buildBaseBuilder()
                            .setOnlyAlertOnce(true)
                            .setLargeIcon(bmp)
                            .setStyle(
                                NotificationCompat.BigPictureStyle()
                                    .bigPicture(bmp)
                                    .bigLargeIcon(null as Bitmap?)
                                    .setBigContentTitle(cleanTitle)
                                    .setSummaryText(contentText)
                            )
                        safeNotify(appCtx, notifId, updatedBuilder)
                    }
                } catch (_: Throwable) {}
            }.start()
        }
    }

    /**
     * Shows notification when a video download fails, with RETRY and OPEN APP actions
     */
    fun showDownloadFailedNotification(
        context: Context,
        title: String,
        message: String,
        failedUrl: String = "",
        quality: String = "best"
    ) {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
        if (prefs?.getBoolean("notify_download_fail", true) == false) {
            return
        }

        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val notifId = nextNotificationId()
        val reason = message.ifBlank { "Could not complete download." }

        val openAppIntent = buildMainActivityIntent(context)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notifId * 10 + 1,
            openAppIntent,
            pendingImmutableFlags()
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS_ID)
            .setContentTitle(title.ifBlank { "Download Failed" })
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (failedUrl.isNotBlank()) {
            val retryIntent = Intent(context, FloatingBubbleService::class.java).apply {
                this.action = FloatingBubbleService.ACTION_NOTIF_RETRY
                this.putExtra(FloatingBubbleService.EXTRA_DOWNLOAD_URL, failedUrl)
                this.putExtra(FloatingBubbleService.EXTRA_QUALITY, quality)
                this.putExtra(FloatingBubbleService.EXTRA_TITLE, title)
                this.putExtra(FloatingBubbleService.EXTRA_NOTIF_ID, notifId)
            }
            val retryPendingIntent = PendingIntent.getService(
                context,
                notifId * 10 + 2,
                retryIntent,
                pendingImmutableFlags()
            )
            builder.addAction(android.R.drawable.ic_menu_rotate, "RETRY", retryPendingIntent)
        }

        builder.addAction(android.R.drawable.ic_menu_view, "OPEN APP", openAppPendingIntent)

        safeNotify(context, notifId, builder)
    }

    fun cancelNotification(context: Context, notifId: Int) {
        if (notifId <= 0) return
        try {
            activeFinishedTitles.remove(notifId)
            NotificationManagerCompat.from(context).cancel(notifId)
            updateResultsSummary(context)
        } catch (_: Throwable) {}
    }

    fun updateResultsSummary(context: Context) {
        if (!canPostNotifications(context)) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            var activeChildCount = activeFinishedTitles.size
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null) {
                val activeList = try {
                    nm.activeNotifications
                } catch (_: Throwable) {
                    null
                }
                if (activeList != null) {
                    val activeIds = HashSet<Int>()
                    for (sbn in activeList) {
                        if (sbn.id != NOTIFICATION_RESULTS_SUMMARY_ID &&
                            sbn.notification.group == GROUP_KEY_RESULTS
                        ) {
                            activeIds.add(sbn.id)
                        }
                    }
                    // Prune any child IDs manually swiped away by user
                    val keysToRemove = activeFinishedTitles.keys.filter { !activeIds.contains(it) }
                    for (k in keysToRemove) {
                        activeFinishedTitles.remove(k)
                    }
                    activeChildCount = maxOf(activeIds.size, activeFinishedTitles.size)
                }
            }

            if (activeChildCount >= 2) {
                val openVideosIntent = buildMainActivityIntent(context, openTab = "videos")
                val summaryPendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_RESULTS_SUMMARY_ID,
                    openVideosIntent,
                    pendingImmutableFlags()
                )
                val summaryTitle = "$activeChildCount downloads finished"
                val inbox = NotificationCompat.InboxStyle()
                    .setBigContentTitle(summaryTitle)
                var lineCount = 0
                for (t in activeFinishedTitles.values) {
                    if (lineCount >= 5) break
                    inbox.addLine(t)
                    lineCount++
                }

                val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_RESULTS_ID)
                    .setContentTitle(summaryTitle)
                    .setContentText("Tap to open your library")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setGroup(GROUP_KEY_RESULTS)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setStyle(inbox)
                    .setContentIntent(summaryPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)

                safeNotify(context, NOTIFICATION_RESULTS_SUMMARY_ID, summaryBuilder)
            } else {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_RESULTS_SUMMARY_ID)
            }
        } catch (_: Throwable) {}
    }

    private fun buildOpenIntent(
        context: Context,
        notifId: Int,
        title: String,
        filePath: String?,
        isImage: Boolean,
        photoUris: ArrayList<String>
    ): Intent {
        if (isImage && photoUris.isNotEmpty()) {
            return Intent(context, PhotoViewerActivity::class.java).apply {
                this.putStringArrayListExtra("photo_uris", photoUris)
                this.putExtra("uri", photoUris.firstOrNull() ?: "")
                this.putExtra("initial_index", 0)
                this.putExtra("post_title", title)
                this.putExtra("notif_id", notifId)
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (!filePath.isNullOrBlank()) {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                this.setData(Uri.parse(filePath))
                this.putExtra("video_uri", filePath)
                this.putExtra("uri", filePath)
                this.putExtra("video_title", title)
                this.putExtra("title", title)
                this.putExtra("notif_id", notifId)
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return buildMainActivityIntent(context, openTab = "videos").apply {
            this.putExtra("notif_id", notifId)
        }
    }

    private fun buildShareIntent(
        title: String,
        filePath: String?,
        isImage: Boolean,
        isAudio: Boolean,
        photoUris: ArrayList<String>
    ): Intent? {
        return try {
            if (isImage && photoUris.isNotEmpty()) {
                val uriList = ArrayList<Uri>()
                for (u in photoUris) {
                    if (u.isNotBlank()) uriList.add(Uri.parse(u))
                }
                if (uriList.isEmpty()) return null
                val sendIntent = if (uriList.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        this.type = "image/*"
                        this.putExtra(Intent.EXTRA_STREAM, uriList[0])
                        this.putExtra(Intent.EXTRA_SUBJECT, title)
                        this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        this.type = "image/*"
                        this.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
                        this.putExtra(Intent.EXTRA_SUBJECT, title)
                        this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                Intent.createChooser(sendIntent, "Share photos").apply {
                    this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else if (!filePath.isNullOrBlank()) {
                val mediaUri = Uri.parse(filePath)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    this.type = if (isAudio) "audio/*" else "video/*"
                    this.putExtra(Intent.EXTRA_STREAM, mediaUri)
                    this.putExtra(Intent.EXTRA_SUBJECT, title)
                    this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Intent.createChooser(sendIntent, if (isAudio) "Share audio" else "Share video").apply {
                    this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatSizeLabel(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1000.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else if (mb >= 0.1) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            val kb = bytes.toDouble() / 1024.0
            String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
        }
    }

    private fun loadNotificationThumbnail(
        context: Context,
        thumbnailUrl: String?,
        mediaUriStr: String?,
        isImage: Boolean,
        isAudio: Boolean
    ): Bitmap? {
        // 1. Try remote thumbnail URL if provided
        if (!thumbnailUrl.isNullOrBlank() &&
            (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://"))
        ) {
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            try {
                conn = (URL(thumbnailUrl).openConnection() as HttpURLConnection).apply {
                    this.connectTimeout = 4000
                    this.readTimeout = 4000
                    this.instanceFollowRedirects = true
                }
                if (conn.responseCode in 200..299) {
                    input = conn.inputStream
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        val decoded = decodeSampledBytes(bytes, 720, 420)
                        if (decoded != null) return decoded
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { input?.close() } catch (_: Throwable) {}
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }

        // 2. Fallback: local MediaStore URI thumbnail (photo or video)
        if (!mediaUriStr.isNullOrBlank() && !isAudio) {
            val uri = try { Uri.parse(mediaUriStr) } catch (_: Throwable) { null } ?: return null
            if (isImage) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.isNotEmpty()) {
                            return decodeSampledBytes(bytes, 720, 420)
                        }
                    }
                } catch (_: Throwable) {}
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        return context.contentResolver.loadThumbnail(uri, Size(640, 360), null)
                    } catch (_: Throwable) {}
                }
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    return mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Throwable) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            }
        }
        return null
    }

    private fun decodeSampledBytes(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                this.inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sampleSize = 1
            val h = opts.outHeight
            val w = opts.outWidth
            if (h > reqHeight || w > reqWidth) {
                val halfH = h / 2
                val halfW = w / 2
                while ((halfH / sampleSize) >= reqHeight && (halfW / sampleSize) >= reqWidth) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = sampleSize
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }
}
