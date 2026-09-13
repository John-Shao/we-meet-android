# Batch 98: private translation session state

Added a connection-bound, transient translation session for the native workspace. API freshness starts at request dispatch, sound requires explicit consent and an exact ready event, and changed source/run or read failures clear playback. Confirmed text remains transient and separate from originals.

Manual forward/reverse speech requires an enabled meeting microphone, consecutive control sequence and authoritative completion before another turn. Unsequenced duplicated completion events cannot unlock a later turn. Ending held speech remains possible after freshness expires; new speech does not. Unknown data commands silence this session and require stopping the affected run instead of replaying speech; this block survives foreground/background state clearing within the controller.

24 JVM regressions (8 session, 5 event, 4 audio policy, 7 repository), default Debug and token/diff checks passed. No real provider/audio use. This batch supplies state logic; native workspace/SDK transport integration follows. Process-death/manual-turn recovery still relies on explicit server-state synchronization in the upcoming workspace.
