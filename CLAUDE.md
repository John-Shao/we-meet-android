# CLAUDE.md — We Meet Android

## Project Overview

We Meet Android is a native Android client for the We Meet video conferencing platform. It connects to the Django REST backend + LiveKit server in sibling repo `../we-meet/`.

Core flow: SMS OTP login → join room by meeting code / UUID → LiveKit audio/video → leave.

This project is the mobile client counterpart of `../we-meet/src/frontend` (React + TypeScript web frontend), a useful reference for UI patterns, LiveKit integration, and API usage.

## Tech Stack

- **Language**: Kotlin 2.0+
- **UI**: Jetpack Compose + Material 3
- **Architecture**: Single Activity, Compose Navigation, MVVM (ViewModel + StateFlow)
- **Video**: LiveKit Android SDK 2.x (`io.livekit:livekit-android`)
- **Networking**: Retrofit 2 + Moshi + OkHttp
- **Token Storage**: EncryptedSharedPreferences (`androidx.security:security-crypto`)
- **Min SDK**: 24 / **Target SDK**: 34

## Package Structure

```
com.we.meet
├── WeMeetApp.kt              # Application class
├── MainActivity.kt             # Single Activity host
├── data/
│   ├── api/                    # Retrofit interfaces & ApiClient singleton
│   │   └── dto/                # Request/response data classes
│   ├── auth/                   # TokenStore, AuthInterceptor
│   └── repository/             # AuthRepository, RoomRepository
├── ui/
│   ├── theme/                  # Material 3 theme (Color, Type, Theme)
│   ├── nav/AppNav.kt           # NavHost routes
│   ├── login/                  # LoginScreen + LoginViewModel
│   ├── home/                   # HomeScreen + HomeViewModel
│   ├── main/MainTabScreen.kt   # Bottom tab navigation
│   ├── profile/ProfileScreen.kt
│   ├── preview/                # Camera preview before joining
│   └── room/                   # RoomScreen + RoomViewModel + ParticipantTile
└── livekit/
    └── LiveKitController.kt    # Thin wrapper over LiveKit Room
```

## Build & Run

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install to emulator/device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android Studio Koala+.

## Configuration

Base URL and LiveKit override are in `gradle.properties`:
```
WE_MEET_BASE_URL=https://meet.we-meet.online
WE_MEET_LIVEKIT_URL_OVERRIDE=
```

For local development (emulator): set `WE_MEET_BASE_URL=http://10.0.2.2:8071` in `local.properties`.

## Backend Contract

Backend: `../we-meet/` (Django + LiveKit). Mobile design doc:
`../we-meet/docs/extensions/移动端App客户端支持方案.md`.

Key endpoints:
- `POST /api/mobile/auth/send-otp/` — no auth required
- `POST /api/mobile/auth/verify-otp/` — returns access_token, refresh_token, expires_in
- `GET /api/v1.0/rooms/{idOrMeetingCode}/` — returns room info with `livekit: {url, room, token}`

we-meet-specific notes:
- The joinable 6-digit code is the room's `meeting_code` field — `slug` is
  name-derived and not typeable. `RoomDto` maps `meeting_code` onto its `slug`
  field so the rest of the app keeps using `slug` as 会议号.
- Avatar/cover buckets are private: `upload-url` returns no `public_url`, and
  `avatar_url`/`cover_url` from `users/me/` are short-lived signed URLs (treat
  as expiring — re-fetch the profile rather than caching the URL long-term).

## Conventions

- Application-scoped singleton Retrofit instance (do NOT create per-ViewModel)
- ViewModels expose `StateFlow<UiState>`; Compose collects via `collectAsStateWithLifecycle`
- LiveKit SDK 2.x `Room.events: SharedFlow<RoomEvent>` — use this, NOT the legacy listener API
- All user-facing strings go in `res/values/strings.xml` + `values-zh-rCN/strings.xml`
- LiveKit connection options: `adaptiveStream = true`, `dynacast = true`
- Debug builds only: `usesCleartextTraffic="true"` for local HTTP testing

## MVP Scope Boundaries

Intentionally excluded (do not implement unless explicitly asked):
- AI agent start/stop UI
- Chat (LiveKit data channel)
- Screen sharing
- Token auto-refresh (re-login on expiry)
- Keycloak PKCE login
- Device selector (uses defaults)
- Dark mode polish
