# ══════════════════════════════════════════════════════════════════════════════
# KIGHMU VPN - CONFIGURATION PROGUARD / R8 (STABLE)
# ══════════════════════════════════════════════════════════════════════════════

# --- Optimisations Générales ---
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses 'a'
-flattenpackagehierarchy 'a'

# --- Suppression des informations de débogage ---
-renamesourcefileattribute ''
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,EnclosingMethod

# --- Suppression des Logs en Release ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# --- Conservation des points d'entrée Android ---
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# --- Conservation des modèles de données (JSON/Gson) ---
-keep class com.kighmu.vpn.models.** { *; }
-keepclassmembers class com.kighmu.vpn.models.** { <fields>; }

# --- Conservation des bibliothèques natives (JNI) ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Support Kotlin Coroutines & ViewModels ---
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.lifecycle.** { *; }

# --- Support GSON ---
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# --- Support OkHttp / Okio ---
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class com.fasterxml.jackson.** { *; }

# --- Règles spécifiques au projet ---
-keep class com.kighmu.vpn.config.ConfigEncryption { *; }
-keep class com.kighmu.vpn.vpn.KighmuVpnService { *; }
-keep class com.kighmu.vpn.ui.activities.** { *; }
