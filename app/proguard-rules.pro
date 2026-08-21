# Beat maps are serialized by kotlinx.serialization in :core:model.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.tunesync.core.model.** {
    *** Companion;
}
