# Batch 136: authentication session fencing

Technical review found that a 401 from an old login could be retried with the currently stored account token, and an in-flight refresh could overwrite a new login. AuthInterceptor now tags the outgoing session; refresh accepts only that session, rotates the exact encrypted credential snapshot atomically and retries at most once. Login/logout change the persisted session ID. Late unauthorized responses cannot clear a newer login or a successfully rotated token. Refresh failures no longer log exception payloads.

Validation: six new isolated authentication scenarios plus eight capture translation controller regressions pass (14 device tests). Debug/test builds and design-token checks pass. No real login server or provider was contacted. Existing credentials without a session marker are initialized lazily; refresh preserves their login session.

Final review continues with Web uncertain-command recovery and response validation.
