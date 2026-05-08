# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools proguard/proguard-android.txt

# Keep OkHttp SSE internals
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.** { *; }

# Keep JSON org.json classes
-keep class org.json.** { *; }

# Keep ConversationHistory for reflection access from MainActivity extension
-keep class com.rokid.style.claude.ConversationHistory { *; }
-keepclassmembers class com.rokid.style.claude.VoiceAssistantService {
    private com.rokid.style.claude.ConversationHistory history;
}
