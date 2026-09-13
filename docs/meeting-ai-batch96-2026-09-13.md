# Batch 96: native private translation protocol

Added exact room/SID controls for the existing manager-owned private translation service. Starts explicitly bind a participation, language pair, mode, audio choice and optional translation retention. Stops omit all start options. The original encrypted request/key survives restarts, unknown outcomes and permission loss; explicit recovery cannot turn an accepted start into a stop or change its consent.

Receipts validate the configured Qwen model/private scope, language/mode/audio/retention, exact stop run, generation ordering and immutable configuration. State reads reject ambiguous connections. Account changes discard late responses. This batch does not subscribe to audio or expose a translation UI.

Validation: 15 JVM tests (7 translation, 8 online capture), 11 isolated instrumentation tests (4 translation coordinator, 3 capture coordinator, 4 encrypted store), default-disabled Debug/test APK, token/diff checks passed. No real meeting, provider or microphone use. Translation event/audio lifecycle and native UI follow.
