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

# ZXing
-keep class com.google.zxing.** { *; }
