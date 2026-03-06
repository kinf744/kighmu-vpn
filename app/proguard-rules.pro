# ─── KIGHMU VPN ProGuard Rules ────────────────────────────────────────────────

# Keep application entry points
-keep class com.kighmu.vpn.vpn.KighmuVpnService { *; }
-keep class com.kighmu.vpn.ui.activities.** { *; }
-keep class com.kighmu.vpn.utils.BootReceiver { *; }

# Keep models (needed for JSON serialization)
-keep class com.kighmu.vpn.models.** { *; }
-keepclassmembers class com.kighmu.vpn.models.** { *; }

# Keep config classes
-keep class com.kighmu.vpn.config.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# JSch SSH library
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# OkHttp WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Anti-Reverse Engineering ──────────────────────────────────────────────────

# Aggressive obfuscation
-repackageclasses 'k'
-allowaccessmodification
-overloadaggressively

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Encrypt string literals (requires dexguard for full effect)
# -encryptstrings class com.kighmu.vpn.config.**

# Remove debug info
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Prevent decompilation of critical classes
-keepattributes !LocalVariableTable,!LocalVariableTypeTable

# ─── Suppress Warnings ────────────────────────────────────────────────────────

-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-dontwarn java.lang.invoke.**
