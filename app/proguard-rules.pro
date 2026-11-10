# Arya Messenger — R8 rules for release builds.
#
# The app has no reflection-heavy framework beyond Room and ZXing, so these rules stay small on
# purpose. Do not add blanket "-keep class **" rules: they defeat shrinking and make the artifact
# look opaque to static analysis.

-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# Kotlin metadata is read by tooling and by Room at compile time only, but keeping it avoids
# confusing stack traces.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Room generates implementations reflectively. Keep the database subclasses and entity classes;
# everything else in androidx.room can be shrunk.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase { public <init>(); }
-dontwarn androidx.room.paging.**

# ZXing decodes via reflection-free code but the embedded capture activity is declared in the
# merged manifest by name, so its entry points must survive shrinking.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# org.json is provided by the platform; silence the compile-time reference.
-dontwarn org.json.**

# The Android Keystore / JCA providers are platform classes.
-dontwarn javax.crypto.**
-dontwarn java.security.**
