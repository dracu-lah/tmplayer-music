# TDLib's JNI entry points are called from native code by name.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn dev.g000sha256.tdl.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
