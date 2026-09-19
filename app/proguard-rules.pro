# JNI entrypoints are called from fixed native symbols.
-keep class com.cartunnel.client.core.HevTunForwarder { *; }
# HEV's JNI_OnLoad registers all four methods by name, including unused stats.
-keep class com.cartunnel.client.core.HevNative { *; }
-keep class libv2ray.** { *; }
