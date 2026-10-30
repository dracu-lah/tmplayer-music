# TDLib's JNI entry points are called from native code by name.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn dev.g000sha256.tdl.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# FFmpeg decoders are found by reflection.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-dontwarn androidx.media3.**
