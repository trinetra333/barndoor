# Keep WireGuard tunnel library classes (uses JNI + reflection internally)
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# Keep model classes parsed with org.json reflection-free access (safe by default,
# these rules are just defensive)
-keep class com.barndoor.app.rotation.** { *; }
-keep class com.barndoor.app.dns.DnsServer { *; }
