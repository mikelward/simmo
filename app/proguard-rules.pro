# R8 keep rules for the minified builds — the CI `release` and `debug` builds
# (isMinifyEnabled = isCiBuild in app/build.gradle.kts). R8 here is shrink-only
# (-dontoptimize -dontobfuscate, below), so the only risk these guard against is
# tree-shaking removing code reached solely by reflection — which the unminified
# local debug build never shows. Keep this list tight — each rule names why it exists.

# Shrink-only: R8 strips unused code but never rewrites or renames it. That drops
# a whole class of optimizer/obfuscator "works in debug, breaks in release" bugs,
# keeps crash stack traces readable (no mapping.txt to upload), and matches the
# sibling Type Launcher builds. The size win comes almost entirely from shrinking.
-dontoptimize
-dontobfuscate

# --- kotlinx.serialization ---------------------------------------------------
# The persisted state is read on the startup path with the reflective
# Json.decodeFromString<SimmoState>() (see SimmoStateStore), and the licenses
# screen deserializes AboutLibraries' @Serializable models the same way. R8
# shrinking can strip the generated $$serializer / Companion those
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
# resolves its bundled metadata by class/resource name, which R8 removal would
# break; keep the library intact (resource shrinking is off, so the metadata files stay).
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**
