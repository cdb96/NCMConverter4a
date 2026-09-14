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

# The release Uber Jar uses a Java-level relauncher to resolve the incubating
# Vector API module before loading the Compose entry point. Keep both classes
# and the reflection-invoked main method through ProGuard.
-keep class com.cdb96.ncmconverter4a.UberJarLauncherKt {
    public static void main(java.lang.String[]);
}
-keep class com.cdb96.ncmconverter4a.UberJarLauncher { *; }
-keep class com.cdb96.ncmconverter4a.MainKt {
    public static void main(...);
}
