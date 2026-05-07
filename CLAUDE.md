# CLAUDE.md — We Meet Android

## Project Overview

We Meet Android is a native Android MVP client for the We Meet video conferencing platform. It connects to an existing Django REST backend + self-hosted LiveKit server (in sibling repo `../jusi_meet_suite1.9/`).

Core flow: SMS OTP login → join room by slug/UUID → LiveKit audio/video → leave.

This project is the mobile client counterpart of `../jusi_meet_suite1.9/src/frontend` (React + TypeScript web frontend). The frontend codebase is highly valuable as a reference for UI patterns, LiveKit integration, API usage, and feature behavior.

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
WE_MEET_BASE_URL=https://meet.jusiai.com
WE_MEET_LIVEKIT_URL_OVERRIDE=
```

For local development (emulator): set `WE_MEET_BASE_URL=http://10.0.2.2:8071` in `local.properties`.

## Backend Contract

API docs are in the sibling repo:
- `../jusi_meet_suite1.9/docs/mobile-integration-guide.md` — API endpoints, base URL, error codes
- `../jusi_meet_suite1.9/docs/mobile-integration-auth.md` — OTP send/verify request/response spec

Key endpoints:
- `POST /api/mobile/auth/send-otp/` — no auth required
- `POST /api/mobile/auth/verify-otp/` — returns access_token, refresh_token, expires_in
- `GET /api/v1.0/rooms/{idOrSlug}/` — returns room info with `livekit: {url, room, token}`

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
