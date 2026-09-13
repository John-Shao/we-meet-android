# Batch 95: native in-meeting AI recording controls

The More menu opens controls bound to the connected LiveKit room SID. Starting requires explicit confirmation; stopping only stops collection and remains available when new starts are disabled. Unknown requests recover the original encrypted intent. Reconnection or a different occurrence closes the old workspace. Opening the exact meeting record keeps the call mounted.

Participants receive a join-token-only status notice independent of toolbar visibility. Unknown/error states do not retain a stale recording claim. The video area reserves space for the notice. Guest and non-manager controls remain restricted by the server.

New Android build flag `WE_MEET_ONLINE_AI_NATIVE` defaults to `false`; this is separate from the existing standalone capture and record-library flags. Existing cloud-video recording remains its previous placeholder and is not enabled by this flag.

Validation: 17 isolated instrumentation tests passed (7 controls/notice, 7 records, 3 coordinator). Enabled Debug/test APK and default-disabled Debug build, design-token and diff checks passed. Light confirmation and dark notice screenshots inspected. No real meeting, microphone, model, deployment or external delivery was exercised. Connected-room layout and reconnection remain deployment/device checks.
