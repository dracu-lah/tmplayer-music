# TDLib's JNI entry points are called from native code by name.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn dev.g000sha256.tdl.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# FFmpeg decoders are found by reflection.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-dontwarn androidx.media3.**

# The Cast framework makes its options provider from the class name in the manifest (regular build only, src/cast).
# A store build has neither the framework nor the class, and the rule then matches nothing.
-keep class com.tmplayer.music.cast.CastOptionsProvider { <init>(); }
