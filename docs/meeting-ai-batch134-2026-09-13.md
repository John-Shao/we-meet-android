# Batch 134: standalone recording translation archive

Record details route online translations and capture-owner translations to separate readers. Shared recording readers without a capture ID cannot see the standalone archive tab. Archives expose the frozen language pair/mode, completion and bounded page controls; final text uses delivery timestamps without fabricated original-text links or seek positions.

No text is persisted or accumulated across pages. Foreground reads reauthorize after resume; failures remove previous text. Account/source changes reset selection. The common visibleRead helper now invokes the latest read closure, retaining lifecycle/source keys.

Validation: five new capture archive and five existing online archive instrumented scenarios passed. The capture suite was repeated after adding the reverse-direction assertion. Debug/test builds and design-token checks passed; light-list and dark-incomplete screenshots inspected. No provider or production calls. Flags remain default off.

Next: optional production Worker/gateway wiring and final technical review.
