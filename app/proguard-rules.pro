# Constellation-Glass R8 / ProGuard rules (release build).
# Created 2026-06-02 (Zack memory/battery pass): the release buildType has
# isMinifyEnabled=true but this file was missing → release builds failed. R8 +
# stripped LeakCanary is the biggest memory win on the small-RAM Rokid glass.
#
# Most deps (OkHttp, Okio, CameraX, ML Kit barcode, AndroidX Compose, DataStore)
# ship their own consumer R8 rules in-artifact, so we only add what's specific to
# this app — primarily the kotlinx.serialization wire protocol, whose generated
# serializers drive the Glass↔Cortex JSON and MUST NOT be stripped/renamed.

# ── kotlinx.serialization ───────────────────────────────────────────────────
# The serialization gradle plugin (2.0.20) bundles consumer rules, but we keep
# the app's @Serializable types + their generated $$serializer explicitly so the
# on-the-wire keys (the @SerialName snake_case values in Frames.kt) are stable
# regardless of R8 version.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the synthetic Companion + generated serializer of every @Serializable type.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# The wire protocol itself (Frames.kt) — keep the classes + their $$serializer
# whole, with descriptor classes, so field names / payload shapes can't drift.
-keep,includedescriptorclasses class com.constellation.glass.wss.**$$serializer { *; }
-keepclassmembers class com.constellation.glass.wss.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.constellation.glass.wss.GlassEvent { *; }
-keep class com.constellation.glass.wss.GlassEvent$* { *; }
-keep class com.constellation.glass.wss.CortexCommand { *; }
-keep class com.constellation.glass.wss.CortexCommand$* { *; }
-keep class com.constellation.glass.wss.StyledRun { *; }

# ── Coroutines ───────────────────────────────────────────────────────────────
# (kotlinx-coroutines ships consumer rules; this just silences a known R8 note.)
-dontwarn kotlinx.coroutines.**

# ── Timber ───────────────────────────────────────────────────────────────────
-dontwarn org.jetbrains.annotations.**

# ── Keep line numbers for readable release crash traces ──────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
