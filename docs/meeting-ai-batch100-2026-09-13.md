# Batch 100: native shared-interpretation protocol

Added exact-occurrence channel management, personal join/leave and bounded renewal APIs. Manager capability is separate from current participant presence. A channel start receipt must remain prepared with the requested target/retention; channel stops match the exact ID. Personal changes validate connection/channel/revision, and renewal additionally binds the observed subscription ID.

Channel intents and personal listening intents are encrypted separately. Listening recovery also binds the local participant SID, so another connection cannot inherit an old choice. Unknown responses and permission loss retain original keys; replaying a channel command never subscribes the manager. Frozen join receipts remain historical, and only fresh reads/renewals may calculate a deadline from request dispatch. Shared-control HTTP calls are capped at 8 seconds against the 20-second listener lease.

Validation: 17 JVM tests (10 shared protocol, 7 private protocol), 12 isolated instrumentation tests (4 shared coordinator, 4 private coordinator, 4 encrypted store), default-disabled Debug/test APK and token/diff checks passed. No migration, provider/audio invocation or deployment. Shared event/audio lifecycle and native channel UI follow.
