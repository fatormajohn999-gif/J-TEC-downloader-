package com.omnidrop.app.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_PERSISTENT_ID = "omnidrop_persistent_channel"
    private const val CHANNEL_DOWNLOADS_ID = "omnidrop_downloads_channel"
    private const val NOTIFICATION_PERSISTENT_ID = 2001
    private var downloadNotificationCounter = 3000

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val persistentChannel = NotificationChannel(
                CHANNEL_PERSISTENT_ID,
                "OmniDrop Quick Actions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Quick clipboard capture and floating controls"
                setShowBadge(false)
            }

            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOADS_ID,
                "OmniDrop Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for finished video downloads"
                enableVibration(true)
            }

            manager.createNotificationChannel(persistentChannel)
            manager.createNotificationChannel(downloadChannel)
        }
    }

    /**
     * Shows persistent notification with a 'Download from clipboard' action button
     */
    fun showPersistentClipboardNotification(context: Context, title: String, content: String) {
        ensureChannels(context)

        val clipboardIntent = Intent(context, ClipboardBridgeActivity::class.java)
        val clipboardPendingIntent = PendingIntent.getActivity(
            context,
            1,
            clipboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val mainClass = try {
            Class.forName("com.omnidrop.app.MainActivity")
        } catch (e: Exception) {
            null
        }

        val openAppIntent = if (mainClass != null) Intent(context, mainClass) else Intent()
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PERSISTENT_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_input_get,
                "Download from clipboard",
                clipboardPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_PERSISTENT_ID, notification)
    }

    /**
     * Dismiss persistent notification
     */
    fun cancelPersistentNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_PERSISTENT_ID)
    }

    /**
     * Shows notification when a video download finishes
     */
    fun showDownloadFinishedNotification(context: Context, title: String, message: String, filePath: String?) {
        ensureChannels(context)

        val openIntent = if (!filePath.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(filePath), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            val mainClass = try {
                Class.forName("com.omnidrop.app.MainActivity")
            } catch (e: Exception) {
                null
            }
            if (mainClass != null) Intent(context, mainClass) else Intent()
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            downloadNotificationCounter,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(downloadNotificationCounter++, notification)
    }
}
