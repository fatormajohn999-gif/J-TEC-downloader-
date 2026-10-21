package com.omnidrop.app.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "VideoDownloader")
class VideoDownloaderPlugin : Plugin() {

    companion object {
        var lastReceivedUrl: String? = null
        var instance: VideoDownloaderPlugin? = null
    }

    override fun load() {
        super.load()
        instance = this
        // Check if intent launched with shared text
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
        val ret = JSObject()
        ret.put("url", url)
        notifyListeners("onUrlReceived", ret, true)
    }

    @PluginMethod
    fun hasOverlayPermission(call: PluginCall) {
        val result = JSObject()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            result.put("granted", Settings.canDrawOverlays(context))
        } else {
            result.put("granted", true)
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
        val result = JSObject()
        result.put("requested", true)
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

        val res = JSObject()
        res.put("status", "running")
        call.resolve(res)
    }

    @PluginMethod
    fun stopBubble(call: PluginCall) {
        val ctx = context
        val serviceIntent = Intent(ctx, FloatingBubbleService::class.java)
        ctx.stopService(serviceIntent)

        val res = JSObject()
        res.put("status", "stopped")
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
        val result = JSObject()
        result.put("url", lastReceivedUrl ?: "")
        // Clear after read so it doesn't trigger repeatedly
        lastReceivedUrl = null
        call.resolve(result)
    }
}
