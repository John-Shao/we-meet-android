# Batch 103: retained translation browsing

Meeting record details now have a translations tab for readers with original-material access. Shared interpretation and owner-only personal translation archives remain separate from original transcripts and summaries. Lists and segments paginate on the exact record/archive with validated metadata, languages, speaker identities and sequence ordering. Incomplete saving is explicit. Delivery timestamps never become original citations or playback offsets; reverse-direction personal translations show their actual target language.

Each foreground read uses the private authenticated no-store client and checks the current account before and after. The repository rejects mismatched archive provenance, invalid text/timing, duplicate or oversized pages and repeated cursors. Backgrounding, access failure and account changes remove retained text from the UI. No disk text cache or provider invocation is introduced.

Validation: 8 protocol JVM tests and 12 isolated UI regressions (5 archive, 7 record workspace), default Debug build, design token and diff checks passed. Light list and dark incomplete personal archive inspected. Existing feature defaults remain off. No migration/deployment/real material access. Next batch aligns older native write-intent recovery with the current unknown-outcome rules before remaining first-release feature work.
