# Batch 104: preserve uncertain native writes through access loss

Aligned ASR, summary generation, automation, human review, task conversion and private questions with newer native coordinators: HTTP 401/403/404 after an unknown attempt cannot establish its outcome and therefore retain the original encrypted key/body. Only validated success or an explicit 400/409/422 rejection resolves that exact intent. Recovery still requires user action; restoring access or opening a store does not dispatch anything.

ASR generation identifiers are now validated before creating persistent recovery metadata, so invalid input cannot prevent subsequent valid requests.

Validation: 21 isolated coordinator/store tests and 7 ASR protocol JVM tests passed, including unknown response → access failure → store restart → exact original recovery for every affected operation. Debug/test builds and design token/diff checks passed. No UI, migration, provider or deployment changes. Web online-capture recovery alignment follows.
