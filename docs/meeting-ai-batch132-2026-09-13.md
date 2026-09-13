# Batch 132: same-microphone standalone translation transport

Adds a single-run foreground WebSocket consumer using the existing recording PCM tap. Authentication is one first-frame ticket; PCM uses a global little-endian sequence shared with turn controls. A turn drains its padded tail before commit. Continuous and bidirectional manual modes support confirmed text and explicitly allowed transient 24 kHz output.

Queues, pending acknowledgements, provider response memory and connection/turn deadlines are bounded. Source loss, malformed envelopes, input overflow and failures close only translation, with no reconnect or audio replay. Duplicate completed responses cannot unlock a later manual turn. The dedicated OkHttp client has no app credentials, redirects or body logging; source validation requires WSS before connection.

Validation: 12 socket state-machine scenarios, 1 real OkHttp/RFC6455 loopback scenario and 9 existing PCM tap scenarios pass (22 total). The loopback test validates masking, copied PCM, tail padding, control ordering, confirmed finish and a still-usable original source. Debug build and token checks pass. Real device/provider playback is not exercised. Native playback and UI follow; flags remain off.
