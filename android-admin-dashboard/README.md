# ShareKhan Admin Dashboard (Android)

This module provides a native Android client for the existing ShareKhan admin dashboard. It mirrors the core workflows available in the web UI—user management, trading requests, executed trades, broker management, and the order entry form—while reusing the same Spring Boot backend APIs.

## Highlights

- Kotlin + Jetpack Compose UI with Material 3.
- OkHttp networking client with full session + CSRF management (mirrors the browser flow).
- Modular architecture (Repository + ViewModel + Compose UI).
- Supports triggering requests, cancelling, prefilling new orders, recording manual executions, and managing brokers.
- Base URL, username, and session information persist locally via Jetpack DataStore.

## Project layout

```
android-admin-dashboard/
├── build.gradle.kts           # Gradle settings for the Android project
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts       # Application module
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/sharekhan/admin/
│       │   ├── data/...       # Networking, models, repository
│       │   ├── ui/...         # Compose screens & view models
│       │   └── MainActivity.kt
│       └── res/               # Material 3 theme + resources
└── README.md (this file)
```

## Prerequisites

1. **Android Studio Hedgehog (or newer)** with the Android Gradle Plugin 8.2+ and Kotlin 1.9+ support.
2. **Backend server** reachable from the device/emulator.  
   - When using the Android emulator with a backend running on the same machine, keep the default base URL `http://10.0.2.2:8080`.
   - Otherwise, enter the backend URL on the login screen (e.g., `https://staging.example.com`).
3. A ROLE_ADMIN user configured on the backend (the app performs the same form-login flow as the web dashboard, including CSRF token handling).

## Getting started

1. Open Android Studio and select **File ▸ Open…**, then choose the `android-admin-dashboard` folder.
2. When prompted, let Android Studio generate a local `gradle-wrapper` (or run `gradle wrapper` once if you prefer the CLI).
3. Sync the project to download dependencies.
4. Launch the app on an emulator or device:
   - On first launch, the login screen pre-fills the last used base URL/username (defaults to `http://10.0.2.2:8080` and `admin`).
   - Enter credentials and tap **Sign In** to establish a session. The app verifies the login by calling `/admin/dashboard-ping`.
5. After login you will land on the native dashboard with four tabs:
   - **Place Order** – Compose version of the order entry form with dynamic instrument/strike/expiry lookups.
   - **Requests** – Pending trading requests with actions to trigger, cancel, or prefill the order form.
   - **Executed** – Paginated executed trades list with status filters.
   - **Brokers** – Broker credential management (add/edit/delete) per app user.

## Feature notes & parity with the web UI

| Web feature                                      | Android status | Notes |
|--------------------------------------------------|----------------|-------|
| Admin login via Spring Security form             | ✓              | Reuses the HTML form flow, handles CSRF + JSESSIONID cookies. |
| Load / create app users                          | ✓              | Add user card in the left panel. |
| Trading request list + trigger/cancel/prefill    | ✓              | Prefill copies fields into the Place Order form. |
| Executed trades list with filters + pagination   | ✓              | Uses the same `/api/orders/executed` endpoint. |
| Broker list / add / edit / delete                | ✓              | Full CRUD with encrypted fields handled server-side. |
| Place order form (options + spot toggles)        | ✓              | Dynamic instrument/strike/expiry fetching; resolves active broker automatically. |
| Real-time LTP WebSocket                          | ✗ (todo)       | The current release relies on periodic refresh; WebSocket streaming can be added later. |
| Request/execution inline editing dialogs         | Partial        | Prefill + trigger supported; inline edit dialogs will be added in a follow-up. |

### Known limitations / TODOs

- WebSocket streaming for live LTP updates is not yet implemented (planned as a future enhancement).
- Advanced editing dialogs for requests/executions are scoped for a subsequent iteration.
- The project currently expects Android Studio to generate the Gradle Wrapper; if you need CLI-only support, run `gradle wrapper` yourself from within the module.

## Useful configuration

- Update the default backend URL in `app/build.gradle.kts` (`BuildConfig.DEFAULT_BASE_URL`) if your backend host differs.
- To run against HTTPS endpoints with self-signed certificates, install the certificate on the device/emulator or place a trusted proxy in front of the backend.
- Logging is enabled via OkHttp’s `HttpLoggingInterceptor` at BASIC level; increase the level if deeper diagnostics are needed during development.

## Testing & verification

- **Manual**: Log in, navigate each tab, and verify CRUD operations mirror the web UI outcomes.
- **Backend**: Because the app reuses the same endpoints, existing integration tests for the web dashboard also cover the Android client.

## License / attribution

This module follows the same license as the parent repository. Review the repository’s primary LICENSE file for details.

