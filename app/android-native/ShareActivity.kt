package com.omnidrop.app.plugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Handles Android Share Intent (ACTION_SEND with text/plain)
 * Extracts the video link and sends it to OmniDrop's floating service or MainActivity.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val action = intent?.action
        val type = intent?.type

        if (Intent.ACTION_SEND == action && type != null && "text/plain" == type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val urlRegex = "(https?://[^\\s]+)".toRegex()
                val match = urlRegex.find(sharedText)
                val targetUrl = match?.value ?: sharedText

                // 1. If FloatingBubbleService is active, feed the URL to it for instant auto-download
                if (FloatingBubbleService.instance != null) {
                    FloatingBubbleService.instance?.handleSharedUrl(targetUrl)
                } else {
                    // 2. Otherwise bring MainActivity to foreground
                    try {
                        val mainClass = Class.forName("com.omnidrop.app.MainActivity")
                        val launchIntent = Intent(this, mainClass).apply {
                            this.action = Intent.ACTION_SEND
                            this.putExtra(Intent.EXTRA_TEXT, targetUrl)
                            this.putExtra("url_to_download", targetUrl)
                            this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(launchIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        finish()
    }
}
