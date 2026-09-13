# Batch 113: native text-only recording controls

The native recording page now offers an unchecked text-only option after authenticated storage admission succeeds. Starting preserves the selected mode through microphone permission handling; resuming uses the existing capture mode. Revoked admission blocks a selected text start while allowing the user to switch back to media.

- Consent explains temporary server retention, the 30-minute retry/start window and 24-hour maximum access window, separate transcription activation/quota, and loss of unuploaded memory audio when the recording service ends.
- The foreground service watches text-audio expiry, immediately requests hardware stop, invalidates pending starts and clears expired local buffers. Expired captures cannot resume. An explicit incomplete-finish confirmation discards unavailable local audio and uses the durable recovery protocol from batch 112.
- Text captures show retention deadlines and verified cleanup states above the content tabs. Failed or invalid cleanup receipts never claim successful deletion. Text captures expose no audio player or summary audio-seek action. The general record detail already gates playback on media retention.
- New transcription requires a valid current retention window; an existing uncertain request remains explicitly reconcilable after expiry. No transcription or recording starts from a page read.
- Text-mode interrupted/saved messaging no longer promises that audio was preserved.

Validation: 21 unique isolated device tests across foreground service, recording screen, retention UI and ASR UI. Tests use synthetic PCM and a fake protocol, never the microphone or provider. The first run found two test issues (dialog screenshot root ambiguity and expecting a disabled button that is intentionally hidden); corrected UI tests pass. Debug and test APK builds and design-token/diff checks pass; default native feature flags restored to false. Light/dark screenshots inspected. Real devices, deployment retention cleanup and model quality remain user-run acceptance work.

No API or database migration in this batch. Independent-recording translation and native cloud recording remain development work; M3/M4 are not complete.
