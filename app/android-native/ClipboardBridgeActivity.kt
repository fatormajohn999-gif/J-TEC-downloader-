package com.omnidrop.app.plugin

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Transparent helper activity that opens for a split-second to access
 * the Android system clipboard (which requires an active foreground window
 * on Android 10+), sends the extracted URL to OmniDrop, and immediately closes.
 */
class ClipboardBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = clipboard.primaryClip
            var extractedUrl = ""

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: ""
                val supported = FloatingBubbleService.extractSupportedUrl(text)
                if (supported != null) {
                    extractedUrl = supported
                } else if (text.startsWith("http://") || text.startsWith("https://")) {
                    extractedUrl = text
                }
            }

            if (extractedUrl.isNotBlank()) {
                Toast.makeText(this, "OmniDrop: Grabbed link from clipboard!", Toast.LENGTH_SHORT).show()

                // 1. If FloatingBubbleService is active, feed directly
                if (FloatingBubbleService.instance != null) {
                    FloatingBubbleService.instance?.handleSharedUrl(extractedUrl)
                }

                // 2. Notify running Capacitor plugin if active
                VideoDownloaderPlugin.instance?.notifyUrlReceived(extractedUrl)

                // 3. Open or bring MainActivity to front with the URL extra
                try {
                    val mainClass = Class.forName("com.omnidrop.app.MainActivity")
                    val openAppIntent = Intent(this, mainClass).apply {
                        this.action = Intent.ACTION_VIEW
                        this.putExtra("url_to_download", extractedUrl)
                        this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(openAppIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(this, "No supported video URL found in clipboard", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            finish()
        }
    }
}
