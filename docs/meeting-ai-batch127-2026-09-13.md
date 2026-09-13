# Batch 127 ? Speech-turn PCM draining

CapturePcmTap.Subscription.finish() drains the selected turn, including a final short frame padded to a whole millisecond, without ending the source microphone or original recording. Repeated/old finish calls cannot affect a replacement subscription. Authority loss/overflow erases queued and leased audio; source finish still prevents attachment after the microphone ends.

Validation: 20 JVM tap/pump tests and 8 isolated foreground-service tests passed, including original recording continuing past five seconds on the same synthetic input after a turn ends. Enabled fixture and final default Debug/test builds plus design token checks pass. All native rollout flags remain off. No actual microphone/network/provider calls were made. Native translation protocol/UI integration follows.
