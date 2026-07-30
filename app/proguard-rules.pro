# ProGuard / R8 rules for Poet Music.
#
# Minification is currently OFF (see the release buildType in
# app/build.gradle.kts). These rules exist so enabling `isMinifyEnabled = true`
# is a one-line change rather than a debugging session: everything below covers
# a dependency that resolves names reflectively and would otherwise break only
# at runtime, on a device, with no compile-time warning.

# --- WebView JavaScript bridge -----------------------------------------------
# MainActivity.PoetNativeBridge is called from poet.js by name
# (PoetNative.setStatusBarColor). Obfuscating the method breaks status-bar
# theming silently.
-keepclassmembers class com.musa.poetmusic.MainActivity$PoetNativeBridge {
    public *;
}
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Ktor (server core + CIO engine) -----------------------------------------
# Engines are discovered via ServiceLoader and coroutine internals are looked
# up reflectively.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
# Ktor pulls in optional integrations (Netty/Jetty/Tomcat, logging backends)
# that are not on this project's classpath.
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn org.conscrypt.**

# --- mp3agic (ID3 tag editing) ------------------------------------------------
-keep class com.mpatric.mp3agic.** { *; }
-dontwarn com.mpatric.mp3agic.**

# --- Media3 / ExoPlayer -------------------------------------------------------
# Media3 ships its own consumer rules; these cover the extension points this
# app touches (the MediaSessionService is named in the manifest).
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.musa.poetmusic.playback.PlaybackService { *; }

# --- Widget provider ----------------------------------------------------------
# Instantiated by the framework from the manifest declaration.
-keep class com.musa.poetmusic.widget.PoetWidgetProvider { *; }

# --- Kotlin metadata ----------------------------------------------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep line numbers in crash reports, but hide original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
