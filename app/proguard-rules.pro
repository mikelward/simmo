# R8 keep rules for the minified builds (release and the `firebase`
# build). Without these, code reached only by reflection is renamed or removed
# and the app misbehaves or crashes in ways the unminified debug build never
# shows. Keep this list tight — each rule names why it exists.

# --- kotlinx.serialization ---------------------------------------------------
# The persisted state is read on the startup path with the reflective
# Json.decodeFromString<SimmoState>() (see SimmoStateStore), and the licenses
# screen deserializes AboutLibraries' @Serializable models the same way. R8 full
# mode (AGP's default) can strip the generated $$serializer / Companion those
# lookups need. These are the serialization library's own recommended rules,
# covering every @Serializable class (app and library).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep the `Companion` field of serializable classes (serializer lookup path).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named).
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- libphonenumber ----------------------------------------------------------
# The country detector warms and reads region metadata at startup. libphonenumber
# resolves its bundled metadata by class/resource name, which obfuscation breaks;
# keep the library intact (resource shrinking is off, so the metadata files stay).
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**
