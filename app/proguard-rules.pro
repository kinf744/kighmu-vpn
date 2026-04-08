# ═══════════════════════════════════════════════════════════════════════════════
# KIGHMU VPN — ProGuard / R8 Rules  (Anti-Reverse Engineering Edition)
# ═══════════════════════════════════════════════════════════════════════════════

# ─── Optimisation R8 agressive ────────────────────────────────────────────────
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# ─── Repackaging complet dans un package opaque ───────────────────────────────
# Tous les packages internes sont fusionnés dans 'a' pour masquer la structure
-repackageclasses 'a'
-flattenpackagehierarchy 'a'

# ─── Suppression des informations de débogage ─────────────────────────────────
-renamesourcefileattribute ''
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,!MethodParameters

# ─── Garder uniquement les annotations nécessaires ────────────────────────────
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# ─── Points d'entrée Android (ne jamais obfusquer) ────────────────────────────
-keep class com.kighmu.vpn.vpn.KighmuVpnService { *; }
-keep class com.kighmu.vpn.utils.BootReceiver { *; }
-keep class com.kighmu.vpn.ui.activities.MainActivity { *; }
-keep class com.kighmu.vpn.ui.activities.SplashActivity { *; }
-keep class com.kighmu.vpn.ui.activities.ImportActivity { *; }
-keep class com.kighmu.vpn.ui.activities.ExportActivity { *; }
-keep class com.kighmu.vpn.ui.activities.SettingsActivity { *; }
-keep class com.kighmu.vpn.ui.activities.LicenseActivity { *; }

# ─── Modèles JSON (Gson SerializedName doit rester intact) ────────────────────
-keep class com.kighmu.vpn.models.** { *; }
-keepclassmembers class com.kighmu.vpn.models.** { *; }

# ─── Config (chiffrement, validation) ─────────────────────────────────────────
-keep class com.kighmu.vpn.config.** { *; }
-keepclassmembers class com.kighmu.vpn.config.** { *; }

# ─── BuildConfig ──────────────────────────────────────────────────────────────
-keep class com.kighmu.vpn.BuildConfig { *; }

# ─── Méthodes JNI natives ─────────────────────────────────────────────────────
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ─── Sérialisation Parcelable / Serializable ──────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─── Enum ─────────────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Gson ─────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn sun.misc.**

# ─── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ─── BouncyCastle ─────────────────────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ─── SSH (connectbot/sshlib) ──────────────────────────────────────────────────
-keep class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ─── Firebase ─────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ─── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.**
-dontwarn kotlin.**

# ─── AndroidX / Material ──────────────────────────────────────────────────────
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ─── Suppression des logs en release (anti-fuite d'information) ───────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ─── Suppression des stack traces (anti-analyse d'erreur) ─────────────────────
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# ─── Divers avertissements à ignorer ──────────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
