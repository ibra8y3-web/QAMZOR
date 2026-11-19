# QAMZOR core uses kotlinx.serialization with reflection-free generated serializers; keep the public SDK surface.
-keep class org.qamzor.javasdk.** { *; }
-keep class org.qamzor.sdk.android.** { *; }
-keep class org.qamzor.core.ArchiveEngine { *; }
-keep class org.qamzor.core.ArchiveEngine$* { *; }
