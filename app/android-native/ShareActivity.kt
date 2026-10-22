package com.omnidrop.app.plugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Handles Android Share Intent (ACTION_SEND with text/plain)
 * Extracts the video link and sends it to OmniDrop's MainActivity.
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
                // Regex to extract URL from text if message contains extra text
                val urlRegex = "(https?://[^\\s]+)".toRegex()
                val match = urlRegex.find(sharedText)
                val targetUrl = match?.value ?: sharedText

                val mainClass = Class.forName("com.omnidrop.app.MainActivity")
                val launchIntent = Intent(this, mainClass).apply {
                    this.action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, targetUrl)
                    putExtra("url_to_download", targetUrl)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(launchIntent)
            }
        }
        finish()
    }
}
