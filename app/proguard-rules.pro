# Default ProGuard rules for android_rdp_client
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* *;
}
-keep class com.rdp.client.freerdp.** { *; }
