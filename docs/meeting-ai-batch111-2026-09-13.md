# Batch 111: native text-audio admission and retention protocol

Added authenticated, uncached audio-storage capability reads and typed capture/ASR retention state. Text-mode creation accepts only matching capture/record/device receipts with valid retention metadata; an invalid response does not replace the caller's original key/body. Capability responses must explicitly and consistently report availability, and cannot cross an account change.

`CaptureRetention` distinguishes the new-ASR retry deadline from the hard audio-access deadline, rejects invalid dates/cleanup claims, and bounds temporary audio by the earlier local/server deadline. Expired audio is not assumed deleted: a completion timestamp is mandatory for verified cleanup. Media retention remains compatible with older backends lacking the new metadata.

Validation: 26 JVM tests (retention, capture transport and ASR), 9 isolated device capture-recovery regressions, default Debug/test builds and design-token/diff checks passed. No real microphone, provider or deployment calls. The capture selector/service/cache still use media mode; text-only lifecycle and UI follow in the next batches. Existing feature flags remain off by default.
