# Default ProGuard rules for the We Meet Android app.
#
# Add project-specific rules here.  Compose, Retrofit/Moshi and LiveKit
# typically work without additional rules in their default consumer rules,
# but if you hit a NoSuchMethodError or NoClassDefFoundError on a release
# build, this is the place to add a -keep clause.

# Keep our DTOs reflectively used by Moshi.
-keep class com.we.meet.data.api.dto.** { *; }

# Audio routing hot-reroute (see CallAudioDeviceModule): we reflect the
# private `audioTrack` field on WebRTC's internal WebRtcAudioTrack to call
# AudioTrack.setPreferredDevice directly. Without this keep rule, R8 will
# rename the field and the reflection will silently return null — mid-call
# speaker/earpiece switching regresses on Android 15+ release builds.
-keepclassmembers class livekit.org.webrtc.audio.WebRtcAudioTrack {
    private android.media.AudioTrack audioTrack;
}
