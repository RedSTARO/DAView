# R8 rules for the Android release APK, on top of the optimised Android
# defaults and the rules every library ships in its own AAR or jar.
#
# What is here is what the app reaches by name or by ServiceLoader. Obfuscation
# is on: stack traces from a release build are translated back with the
# mapping file CI keeps next to the APK.

-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# SLF4J 2 finds slf4j-simple through ServiceLoader; without a provider the
# core's logging goes nowhere at all.
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { <init>(); }
-dontwarn org.slf4j.**

# DefaultRenderersFactory creates the FFmpeg audio renderer by class name when
# extension renderers are on, which is how DTS and TrueHD get decoded.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }

# Classes the core names only for the desktop (JDBC, logback) or that libraries
# probe for and do without on Android.
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn ch.qos.logback.**
-dontwarn org.sqlite.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
