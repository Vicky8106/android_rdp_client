# FreeRDP Mobile — ProGuard/R8 rules.
# minify is currently disabled for debug and release; these rules are kept ready
# for when minification is enabled.

# --- FreeRDP JNI bridge -------------------------------------------------
# Native methods must keep their exact names; the classes are looked up from C.
-keepclasseswithmembers class com.freerdp.freerdpcore.services.LibFreeRDP {
    native <methods>;
}
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }
-keep class com.freerdp.freerdpcore.services.LibFreeRDP$EventListener { *; }
-keep class com.freerdp.freerdpcore.services.LibFreeRDP$UIEventListener { *; }
-keep class com.freerdp.core.engine.NativeFreeRdpEngine { *; }

# --- kotlinx.serialization --------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.freerdp.feature.session.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.freerdp.feature.session.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Keep line numbers for readable native/remote stack traces ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
