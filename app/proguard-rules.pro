-keepattributes Signature
-keepattributes *Annotation*
-keep class com.kqstone.mtphotos.data.model.** { *; }
-keep class com.kqstone.mtphotos.data.api.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
