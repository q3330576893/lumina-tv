# NanoHTTPD（内置配对/遥控服务）
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# OkHttp / Coil
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# Org.json 由 Android 平台提供
-dontwarn org.json.**

# Keep exception type names readable in exported playback failure records.
-keepnames class * extends java.lang.Exception

# ZXing
-keep class com.google.zxing.** { *; }

# Media3 FFmpeg 音频扩展：DefaultRenderersFactory 通过反射按类名加载
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
