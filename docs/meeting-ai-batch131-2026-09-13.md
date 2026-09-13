# Batch 131: standalone recording translation protocol

Adds exact capture/account/device translation controls, expiring WSS tickets and owner-only retained archive readers through the existing private HTTP transport (no redirect, cache, debug body logging or connection retry). Model/configuration, source revision, immutable receipts and archive provenance are validated before results are accepted.

The encrypted meeting intent store gains CAPTURE_TRANSLATION. Only the command body/key are persisted; the lease and connection ticket remain transient. Explicit recovery reuses the original key and body. Authentication errors, timeouts, throttling, failed transport and malformed successful responses preserve the pending command; only validated receipts or 400/409/422 rejection resolve it. Recovery never opens a WebSocket or requests a ticket.

Validation: 12 JVM repository scenarios and 5 isolated device recovery scenarios pass. Debug/test APK builds and design-token checks pass with default feature flags off. No provider, network deployment or physical microphone was exercised. Same-microphone WS transport, playback and UI follow.
