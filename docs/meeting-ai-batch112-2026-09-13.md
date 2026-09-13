# Batch 112: native temporary audio and explicit incomplete recovery

The native capture journal now supports text-only audio in a bounded memory map. It persists encrypted session/receipt metadata and numbered source identities, but never stores text-mode audio in SQLite. Media-mode audio remains encrypted and recoverable as before.

- Acknowledgement, expiry, explicit discard, sealing and journal close clear managed temporary buffers. Reads enforce local/server retention limits. Reopening marks missing unacknowledged text audio as interrupted and closed, retaining its original sequence identities.
- An upload whose response was lost can still be resolved by a matching server receipt after process loss, without resending audio or subtracting missing bytes twice. Temporary plaintext upload copies are cleared in `finally` blocks.
- Text-mode prepare/start check authenticated admission; old creation/command keys remain unchanged. The service/UI still pass the media default until batch 113.
- `finish(..., allowMissing = true)` is an explicit text-mode recovery path. Normal finishing continues to require all source receipts. The accepted incomplete choice and immutable seal body persist together, so a lost response can be recovered after restart without new consent or a different request. Missing sequences remain visible in the server manifest.
- Journal opening now authenticates metadata while reconciling temporary state. The existing tamper test was updated to expect rejection at open rather than first list; it also verifies the corrupted recovery payload was not overwritten or erased.

Validation: 24 isolated device journal/recovery tests, 19 JVM capture/retention tests, default Debug/test builds and design-token/diff checks. No live microphone/provider/deployment. Existing journal schema 1 remains compatible because added session JSON fields have defaults; no database migration. Native selector, service expiry shutdown and retention presentation follow.
