# ProGuard rules for desktop release
# Note: Compose plugin injects -dontobfuscate & -keep for kotlin.**/skiko.**
#       in the generated root-config.pro. These cannot be overridden.
#       What we CAN control: shrink, optimize, remove dead code.

# ── Remove Kotlin null checks at runtime (safe: NPE still thrown by JVM) ──
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
}

# ── Aggressive optimization (5 passes, access modification, interface merging) ──
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# Disable arithmetic simplification (can break Kotlin integer overflow semantics)
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# ── Remove non-essential class attributes ──
-keepattributes SourceFile,LineNumberTable

# ── Ignore optional/absent deps ──
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**
-dontwarn org.intellij.**
-dontwarn com.jetbrains.**
-dontwarn org.jspecify.**

-dontnote
