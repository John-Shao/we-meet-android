# Batch 97: native translation events and track subscription boundary

Added bounded strict-UTF-8 private translation events, exact run/generation and agent checks, immutable confirmed rows, and a 60-row transient history. Events/rows redact their text in diagnostic string representations.

When `WE_MEET_ONLINE_AI_NATIVE` is enabled, LiveKit starts with automatic subscriptions disabled. A subscription manager subscribes ordinary known participants normally, while reserved translation/interpretation identities require an exact room, local connection, agent identity/SID, track SID and bounded monotonic grant. It mutes before unsubscribe, rechecks late subscribed tracks every 250 ms and on SDK events, and clears grants on reconnect/disconnect/release. Private/shared workspaces have independent grant ownership. No translation UI grants playback yet.

Validation: 16 JVM tests (5 event, 4 audio policy, 7 protocol) passed. Enabled and default-disabled Debug builds and token/diff checks passed against the installed SDK. No real network/audio was used. Actual subscription cancellation timing, ordinary meeting audio/video and physical-device reconnect behavior require deployment/device testing; the local checks validate policy and SDK compilation, not an end-to-end call.
