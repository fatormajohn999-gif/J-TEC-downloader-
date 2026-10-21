# OmniDrop Native Android Capacitor Plugin

This folder contains the native Kotlin source files powering OmniDrop's background services and system integrations on Android:

1. **Share Receiver (`ShareActivity.kt`)**: Catches `ACTION_SEND` intents when a user shares a video from TikTok, YouTube, Instagram, or Facebook, and passes the link directly into the OmniDrop UI.
2. **Floating Overlay Bubble (`FloatingBubbleService.kt`)**: Persistent, draggable floating bubble rendered via `WindowManager` (`SYSTEM_ALERT_WINDOW`).
3. **Transparent Clipboard Bridge (`ClipboardBridgeActivity.kt`)**: Instantaneous translucent activity that safely extracts clipboard links even under Android 10+ background restrictions.
4. **Quick Notification Action (`NotificationHelper.kt`)**: Persistent notification with "Download from clipboard" action and download finish notifications.
5. **Capacitor Plugin (`VideoDownloaderPlugin.kt`)**: Bridges native Kotlin functionality to the TypeScript frontend.

---

## Wiring into your Capacitor Android Project

1. Run `npx cap add android` in the `/app` directory.
2. Copy these Kotlin files into `android/app/src/main/java/com/omnidrop/app/plugin/`.
3. In `android/app/src/main/java/com/omnidrop/app/MainActivity.kt`, register the plugin:
   ```kotlin
   import com.omnidrop.app.plugin.VideoDownloaderPlugin

   class MainActivity : BridgeActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           registerPlugin(VideoDownloaderPlugin::class.java)
           super.onCreate(savedInstanceState)
       }
   }
   ```
4. Merge the permissions and components from `AndroidManifest_snippet.xml` into `android/app/src/main/AndroidManifest.xml`.
5. Run `npx cap sync android` and build with Gradle or Android Studio!
