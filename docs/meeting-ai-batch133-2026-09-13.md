# Batch 133: recording translation controls and playback

Independent recording now mounts a source/revision-bound translation panel. Explicit starts use the encrypted original command; uncertain recovery never requests a new ticket or resumes audio. It observes the foreground service PCM tap and cannot create another microphone.

The resumed-screen controller releases its socket, live text and output on disposal, account/source changes and denied refresh. Operation fencing rejects late reads and old audio-focus callbacks. The output-only 24 kHz AudioTrack has a three-second queue limit; mute discards queued audio and unmute accepts future output only. Focus loss and noisy routing terminate playback without automatic recovery.

Controls provide continuous translation, two manual directions, explicit speech completion, stop, recovery and independent playback/save consent. Settings cannot change an active or uncertain command. The new WE_MEET_CAPTURE_TRANSLATION_NATIVE flag defaults false and also requires the existing capture flow.

Validation: enabled Debug and instrumentation APK builds, 8 controller and 5 Compose tests on the isolated offline emulator, then default-off builds and design-token checks. Tests use fake provider/audio output; actual DashScope latency, physical audio focus, Bluetooth and acoustic feedback require deployment/device acceptance.

Next: saved standalone translation archives, gateway deployment wiring and final technical review.
