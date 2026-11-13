# PuzzleQuest for Android

The native Android app for [PuzzleQuest](https://thepuzzlequest.com), the city
puzzle experiences: players travel to stops around a city, activate each stop,
and solve its puzzle, alone or in teams racing each other. This app is written
in Kotlin with Jetpack Compose against the PuzzleQuest API (version 1), whose
server and web app live in
[therapypub-puzzlequest](https://github.com/jimevans/therapypub-puzzlequest).
Why there is a native app, and how it relates to that repository, is in its
decision record `docs/decisions/0021-native-apps.md`. Its companion is the iOS
app, [puzzlequest-ios](https://github.com/jimevans/puzzlequest-ios), which
this app follows screen for screen.

So far it signs in and out, and keeps its session between launches.

## Requirements

- **Android Studio** (it brings the Android SDK, an emulator and a JDK), or
  the SDK and a JDK 17 or later. The app runs on Android 10 (API 29) and
  later, and is built against Android 17 (API 37). Gradle itself comes from
  the wrapper (`./gradlew`), which downloads the version the project names.
  From the command line, set `JAVA_HOME` to a JDK (Android Studio's is in
  `/Applications/Android Studio.app/Contents/jbr/Contents/Home`) and either
  `ANDROID_HOME` or `sdk.dir` in `local.properties` to the SDK; Android Studio
  writes `local.properties` itself.
- **Node.js 24, through [fnm](https://github.com/Schniz/fnm)**, only for the
  contract and UI tests, which run the real API server, and for
  `scripts/brand-drawables.mjs`. Install it as the server repository's README
  says; the scripts here use `fnm exec --using=24`, or the `node` on your
  `PATH` if fnm isn't installed.
- **jq** and the **GitHub CLI** (`gh`), only for moving to a new API release.
  macOS includes jq.

Every library and build plugin is pinned to an exact version in
`gradle/libs.versions.toml`, each out at least two weeks when chosen. The
app's own libraries are Jetpack Compose with Material 3, kotlinx.coroutines
and kotlinx.serialization, and OkHttp (for the API client).

## Running the app

Open the folder in Android Studio and run the **app** configuration on an
emulator or a phone, or from the command line:

```sh
./gradlew :app:installDebug
```

A debug build uses the server at <http://localhost:3000>; a release build uses
<https://thepuzzlequest.com>. To run a server locally, follow the server
repository's README ("Local setup"): `npm run dev`, then `npm run seed` for
sample users (such as `alex`, password `puzzlequest sample`) and quests. The
phone's localhost is not the computer's: forward the port to it each time
the emulator starts or the phone is plugged in:

```sh
adb reverse tcp:3000 tcp:3000
```

Android 17 keeps apps that target it off the local network, so the
emulator's usual address for the computer, 10.0.2.2, is refused; a forwarded
port on the phone's own localhost isn't. To point a debug build at another
server, start it with the intent extra `PQ_SERVER_URL`, as the UI tests do:

```sh
adb shell am start -n com.thepuzzlequest.puzzlequest/.MainActivity --es PQ_SERVER_URL http://localhost:3970
```

## Layout

- `app/`: the app itself, kept thin: `MainActivity`, its screens (Compose, in
  `ui/`), the token kept with the Android Keystore (`KeystoreTokenStore`), and
  its words in `res/values/strings.xml` (English only for now).
  - `ui/theme/Theme.kt`: the brand's colors and display face (below).
  - `src/debug/`: what only debug builds have: plain HTTP to localhost.
  - `src/androidTest/`: UI tests (Compose) of whole journeys, and tests that
    need a phone, such as the Keystore's.
- `core/`: a Kotlin library holding everything that can be tested without a
  phone or emulator.
  - The API client, generated as it builds from `core/openapi.json` by
    [OpenAPI Generator](https://openapi-generator.tech), into
    `core/build/generated/openapi`. Nothing in it is written by hand.
  - `src/main/kotlin`: what the screens use: `PuzzleQuestApi` (the calls,
    returning the app's own types and throwing `ApiError`), `SessionStore`
    (who is signed in, and its token), and models such as `SignInModel`,
    which publish their state as Kotlin flows.
  - `src/test`: unit tests. `src/contractTest`: tests against a real server.
- `api/`: the pinned API release: its description (`openapi.json`), its
  live-update messages (`live-updates.json`), what push notifications carry
  (`push-notifications.json`), and its tag (`VERSION`).
- `scripts/`: moving to a new API release, running the test server, and
  making the brand's drawables.

## The brand

The app wears PuzzleQuest's brand, described in the server repository's
`brand/README.md`: Material's own controls in the brand's colors (purple
buttons and links; lavender in dark mode, where filled buttons have
aubergine text), with the brand's display face, Barlow Condensed Bold, for
headings and clocks. Body text, fields and navigation keep Android's own, so
the app feels at home on a phone. It doesn't take Android 12's colors from
the wallpaper.

- The font is `app/src/main/res/font/barlow_condensed_bold.ttf`, from
  [Google Fonts](https://github.com/google/fonts), under the SIL Open Font
  License, which ships with the app (`assets/licenses/`).
- The logo (`drawable/logo.xml`) and the launcher icon's layers
  (`drawable/ic_launcher_foreground.xml`, and the single-color
  `ic_launcher_monochrome.xml` for themed icons) are vector drawables made
  from the brand's `logo.svg` by `scripts/brand-drawables.mjs`. Rerun it if
  the logo changes:

  ```sh
  node scripts/brand-drawables.mjs   # reads ../therapypub-puzzlequest/brand
  ```

  The icon's background, and the launch screen's, is aubergine.

## The API and moving to a new release

The app is written against the API description published with a release of
the server repository (decision 0023 there describes its conventions). The
copy in `api/` is exactly the release's; `scripts/update-api.sh` downloads a
release and writes the copy the client is generated from:

```sh
scripts/update-api.sh v1.2.0   # move to that release
scripts/update-api.sh          # re-apply the changes to the pinned one
```

Version 1 of the API only grows: it may add fields, enumerated values,
problem types and live-update messages at any time. The generated client
copes with those as `core/build.gradle.kts` sets it up:
- Responses ignore fields they don't know.
- An unknown value of an enumeration decodes as its `unknown_default_open_api`
  case.
- A value that may be `null` is nullable.
- Whole numbers are `Long`s: a quest's clock, in milliseconds, passes `Int`'s
  limit after 24 days.

The script changes only what the generator can't turn into working code;
`scripts/client-openapi.jq` explains it.

CI checks that the generated-from copy matches what the script writes, and
the unit tests in `ToleranceTest.kt` check that the client reads such
responses. Moving to a new release is a deliberate change: run the script,
build, run every test, and commit `api/` with the regenerated copy.

## Checks and tests

### Formatting and lint

```sh
./gradlew spotlessCheck        # spotlessApply fixes it
./gradlew :app:lintDebug
```

Kotlin is formatted in [ktlint](https://pinterest.github.io/ktlint/)'s
standard style (`.editorconfig`), through
[Spotless](https://github.com/diffplug/spotless). Android Lint fails on any
warning; `app/lint.xml` lists what's set aside, and why.

### Unit tests

```sh
./gradlew :core:test
```

These run on the computer: reading newer responses, explaining errors, and
restoring and ending sessions against a fake server
([MockWebServer](https://github.com/square/okhttp/tree/master/mockwebserver)).

### The test server

The contract and UI tests run against a real server: the server repository
at the release in `api/VERSION`, on an in-memory database, with a test user.

```sh
scripts/start-test-server.sh    # clones, builds and starts it in the background
set -a; source .test-server/env; set +a
# … run the tests …
scripts/stop-test-server.sh
```

The first start clones the server into `.test-server/` (from
`../therapypub-puzzlequest` if you have it, otherwise from GitHub; set
`PQ_SERVER_REPOSITORY` to choose), installs its packages and builds it;
later starts reuse that. It listens on port 3970 (`PQ_TEST_SERVER_PORT`).
`.test-server/env` names the server and test user. The server's log is
`.test-server/server.log`.

### Contract tests

With the test server's variables loaded:

```sh
./gradlew :core:contractTest
```

They sign in, check the session, sign out, and check the problems the server
sends for a wrong password, an invalid request, and too many attempts, all
through the generated client. Without `PQ_TEST_SERVER_URL` they're skipped.

### UI tests, and everything on a phone

With the test server's variables loaded, and an emulator running or a phone
connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The build passes the test server's variables on to the tests, and first
forwards the server's port to the phone (`adb reverse`; with more than one
phone or emulator connected, set `ANDROID_SERIAL` to choose). The UI tests
launch the app pointed at the test server, sign in, relaunch to check the
session is kept, sign out, and check a wrong password is explained. Without
the variables, they're skipped. They find controls by their test tags (such
as `sign-in.user-name`), so those stay put when wording changes.

## Continuous integration

`.github/workflows/ci.yml` runs on GitHub's Linux runners: the check of the
generated-from description, formatting, the unit tests, lint, the builds,
the test server, the contract tests, and the UI tests in an Android 17
emulator. When a run fails, the reports, test results and the server's logs
are kept as an artifact.

## Copyright

Copyright © 2026 jimevans. All rights reserved. The source is public so it can
be read and reviewed, but no licence to use, copy, modify, or distribute it is
granted for now. Barlow Condensed is © 2017 The Barlow Project Authors, under
the SIL Open Font License 1.1.
