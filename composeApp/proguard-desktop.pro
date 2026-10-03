# ProGuard rules for the desktop release packages (packageRelease*).
#
# The Compose plugin adds its own rules first (Kotlin, Skiko, coroutines,
# Compose). What is here is what this app reaches by name, by reflection or
# from native code, which ProGuard cannot see and would otherwise remove or
# rename. Obfuscation is on: stack traces in the log are translated back with
# the mapping file CI keeps next to each package.

-keepattributes SourceFile, LineNumberTable, *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions
-renamesourcefileattribute SourceFile
# Relative to this file. CI keeps it with the packages; without it an
# obfuscated stack trace from a user's log cannot be read.
-printmapping build/compose/tmp/proguardReleaseJars/mapping.txt

# ---- libmpv, through JNA ---------------------------------------------------
# The interface's method names are the C symbols JNA binds them to, and JNA
# builds the proxy, reads the options map and calls back through reflection.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**

# ---- SQLite ----------------------------------------------------------------
# JNI, a driver registered by name (Class.forName("org.sqlite.JDBC")), and a
# native loader that finds its library as a resource beside its own classes.
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

# ---- Logging ---------------------------------------------------------------
# SLF4J finds logback through ServiceLoader, and logback builds its appenders,
# encoders and policies from logback.xml by class name.
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn jakarta.**
-dontwarn javax.mail.**
-dontwarn javax.servlet.**
-dontwarn org.codehaus.janino.**
-dontwarn org.codehaus.commons.compiler.**
-dontwarn org.fusesource.jansi.**
-dontwarn groovy.**
-dontwarn org.tukaani.xz.**

# ---- kotlinx.serialization --------------------------------------------------
# The library carries these for R8; ProGuard does not read rules out of jars.
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer {
    static **$$serializer INSTANCE;
}
-dontwarn kotlinx.serialization.**

# ---- OkHttp ----------------------------------------------------------------
# Platform detection probes for Android, Conscrypt and BouncyCastle by name;
# none of them is on the desktop classpath.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# The GraalVM native-image feature OkHttp carries; never loaded on a JVM.
-dontwarn okhttp3.internal.graal.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.nativeimage.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Coil ------------------------------------------------------------------
# Components registered through ServiceLoader.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-dontwarn coil3.**

# ---- Everything else on the classpath that names optional dependencies ----
-dontwarn android.**
-dontwarn androidx.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn io.ktor.**
