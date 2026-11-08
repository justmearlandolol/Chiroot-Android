# The nested MediaSessionService is instantiated by Android by manifest name.
-keep class id.my.chiroot.MainActivity$ChirootPlaybackService { *; }
-keep class id.my.chiroot.MainActivity { *; }
-dontwarn androidx.media3.**
