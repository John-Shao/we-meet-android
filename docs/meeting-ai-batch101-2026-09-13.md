# Batch 101: native shared-interpretation events and listening state

Added strict bounded UTF-8 interpretation events tied to channel generation, target, subscription ID/revision and actual source participation. Confirmed rows are immutable, separated per speaker and bounded to 60. Diagnostic string representations redact text.

Reading an existing server subscription does not listen. Explicit foreground selection is required before ready events can grant tracks. Playback is bounded by both fresh channel state and the listener lease; expired, changed or revoked choices never auto-resume. Late renewals for an older choice do not affect a newer choice. Each of at most 16 grants binds an actual active source, agent connection and exact track. Departed sources are pruned; muting preserves text listening while leaving clears transient state.

25 JVM tests (11 session/event, 10 protocol, 4 track policy), default Debug and token/diff checks passed. No real audio/provider or deployment. Native SDK transport, channel controls and listener UI follow.
