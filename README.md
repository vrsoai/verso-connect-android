# VersoConnect for Android

Lets your users connect their ChatGPT account to [Verso](https://tryverso.ai)
from inside your app. The provider's login opens in a screen that behaves like
Chrome (native keyboard, autofill, Google sign-in), and the captured session
goes straight to Verso. No hosted browser is involved.

Requirements: Android 7.0+ (API 24), Kotlin, AndroidX.

Documentation: [docs.tryverso.ai/guides/mobile-apps](https://docs.tryverso.ai/guides/mobile-apps).

## Install

The library is served by JitPack. Add the repository once, in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the dependency, in your app module:

```kotlin
dependencies {
    implementation("com.github.vrsoai:verso-connect-android:0.1.0")
}
```

## Workspace

- `versoconnect/`: the library, package `ai.tryverso.connect`.
- `demo/`: a one-screen app that stands in for a partner app. Build with
  `./gradlew :demo:installDebug`, tap **Connect ChatGPT**: the app asks the
  example partner backend (`../examples/partner-backend`) for a link, then opens
  the flow. Put the backend's address and demo key in `local.properties`
  (`DEMO_BACKEND_URL=…`, `DEMO_KEY=…`) or in the environment; they reach the
  app through `BuildConfig`. Debug bypass, skipping the backend:
  `adb shell am start -n ai.tryverso.connect.demo/.MainActivity --es link '<url>'`.

## Use

Your backend signs a connect link with `signLink()` from `@versoai/core` and
returns its `url` to the app. The app secret never ships in the app.

```kotlin
import ai.tryverso.connect.VersoConnect
import ai.tryverso.connect.VersoConnectException

// From an Activity, after fetching `link` from your backend
VersoConnect.present(this, Uri.parse(link)) { result ->
    result.onSuccess { connection ->
        // connection.connectionId; your backend also receives connection.created
    }.onFailure { e ->
        when (e) {
            is VersoConnectException.Cancelled -> { /* the user closed the screen */ }
            is VersoConnectException.Rejected -> { /* e.status, e.message: sign a new link */ }
            else -> { /* network */ }
        }
    }
}
```

Or with the Activity Result API:

```kotlin
private val connect = registerForActivityResult(VersoConnectContract()) { result -> … }
connect.launch(Uri.parse(link))
```

The screen closes itself in every case. The link is single use and valid
15 minutes: fetch a fresh one each time the user taps your button. The login
itself must complete within one hour.

## Errors

| `VersoConnectException` | Meaning |
|---|---|
| `InvalidLink` | The URL carries no token. |
| `Cancelled` | The user closed the screen. |
| `Rejected(status, message)` | Verso refused: an expired link (401), a link already used (403), or a ChatGPT account already connected by another user of your app (409). Sign a new link. |
| `TimedOut` | The provider session never became usable. |
| `Network(cause)` | The request to Verso failed. Retry with a new link. |

## What happens

1. The SDK sends the link's token to Verso, which verifies and consumes it
   and answers with the provider's login URL and what to watch for.
2. The login opens in a `WebView` presenting itself as Chrome, with cookies
   and storage cleared before and after. Nothing is left on the device.
3. When the provider's session cookie is present and the session is usable,
   the SDK sends it to Verso, once, over TLS. Verso encrypts it with a key that
   exists only for this connection, creates the connection, sends
   `connection.created` to your backend and starts the first sync.

The SDK never sees your app secret or API key.

## License

MIT.
