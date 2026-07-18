# Add project specific ProGuard/R8 rules here.

# Keep symbol name mappings matching the main app structure
-dontobfuscate

# These methods are invoked from the native SSH dispatcher through JNI.
-keep class com.termux.terminal.GhosttySessionWorker {
    public void onNativeSshOutput(byte[]);
    public void onNativeSshClosed();
}

# Bouncy Castle cryptography keep rules
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**



# Prevent warnings for optional security/crypto providers
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn sun.security.x509.X509Key
