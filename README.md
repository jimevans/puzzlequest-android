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

So far players can sign in and out (the session is kept between launches),
see their quests, kept up to date live as they start, pause and change, and
open one: the stop they're on first, then the stops they've solved, each with
its times and solution, and once they finish, the end of the line and, in a
race, the standings. The stop they're on opens to its puzzle: they activate
it by scanning its QR code or typing its code, read it (text, a picture, a
recording or a film), reveal hints, answer, and go on to the next stop. The
puzzle master's messages pop up over the quest as they arrive, each once on
this phone; a challenge is answered there, and a right answer shows the
confirmation to show the venue's staff, kept at the top of the quest until
the player hides it. Everything the puzzle master has sent is in the quest's
history. Push notifications tell them when a quest starts, is paused or
resumed, when a teammate solves a stop, of the puzzle master's messages, and
how a race ends, even with the app closed. It never says how many stops a
quest has.
Staff and administrators are pointed to the website, where their tools are.
Options, from the sign-in screen or the account menu, chooses which
PuzzleQuest server the app uses (below).

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
app's own libraries are Jetpack Compose with Material 3 and Navigation
Compose, kotlinx.coroutines and kotlinx.serialization, OkHttp (for the API
client, the live-update websocket and puzzles' pictures),
[commonmark-java](https://github.com/commonmark/commonmark-java) (reading text
puzzles' Markdown, with GitHub's tables, strikethrough and task lists), and
Google Play services' [code scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner)
(reading a puzzle's QR code). Recordings and films play with Android's own
`VideoView`.

## Push notifications

Notifications come through [Firebase Cloud
Messaging](https://firebase.google.com/docs/cloud-messaging): the server sends
them (with the Firebase project's service account; see its README), and the
app registers the phone's token with the player's session (`PUT
/sessions/current/push-target`), so signing out ends them.
`app/google-services.json` is the Firebase project's settings for this app
(Firebase console > Project settings > Your apps); its values identify the
project rather than grant access, so it's in the repository, and its API key
is restricted in Google Cloud to the Android app and Firebase's APIs.

- Android 13 and later ask the player before an app may notify. The app asks
  only when the player chooses **Turn on** on a quest's screen (never right
  after signing in), and offers it there until they answer, or say **Not
  now** on that quest. Earlier versions allow notifications unless they're
  turned off in the phone's settings.
- The phone is registered only once notifications are allowed: until then,
  the puzzle master's messages are texted, as to a player without the app.
- Firebase shows a notification that arrives while the app is closed, in the
  app's "Quests" channel. While it's open, the app shows it, unless it's about
  the quest on screen, whose live updates have already shown what changed.
  Tapping one opens its quest.
- The UI tests give the app a token of their own (`PQ_FAKE_PUSH_TOKEN`, which
  only debug builds read), since an emulator on CI can't get one from
  Firebase, and grant the permission rather than tap Android's question. That
  Firebase gives a phone a token, and delivers, is checked on a phone against
  a server with the service account.

## Scanning a puzzle's QR code

The code scanner is Google Play services' own screen: it opens over the app,
and hands the app only the code it read, so the app asks for no camera
permission and no picture leaves the phone. The code on a puzzle's card is
just its activation code, which is sent as if typed. Google Play installs the
scanner with the app (the `com.google.mlkit.vision.DEPENDENCIES` entry in the
manifest). A phone without a camera or Google Play services isn't offered
scanning, and if the scanner fails the player is asked to type the code;
typing always works. The UI tests type codes, since they can't drive Google's
screen.

## Running the app

Open the folder in Android Studio and run the **app** configuration on an
emulator or a phone, or from the command line:

```sh
./gradlew :app:installDebug
```

Every build starts out using <https://thepuzzlequest.com>. To use a server on
your computer, tap **Options** (the gear) on the sign-in screen and choose
**This computer** (<http://localhost:3000>; debug builds only). To run a
server locally, follow the server repository's README ("Local setup"):
`npm run dev`, then `npm run seed` for sample users (such as `alex`, password
`puzzlequest sample`) and quests. **Custom** takes any other address; a debug
build allows any http or https address, a release build only
thepuzzlequest.com and its subdomains, over HTTPS. The phone's localhost is
not the computer's: forward the port to it each time the emulator starts or
the phone is plugged in:

```sh
adb reverse tcp:3000 tcp:3000
```

Android 17 keeps apps that target it off the local network, so the
emulator's usual address for the computer, 10.0.2.2, is refused, as are other
addresses on the local network; a forwarded port on the phone's own localhost
isn't. To point a debug build at a server for one run without changing
Options, start it with the intent extra `PQ_SERVER_URL`, as the UI tests do:

```sh
adb shell am start -n com.thepuzzlequest.puzzlequest/.MainActivity --es PQ_SERVER_URL http://localhost:3970
```

## Choosing the server

The server is a setting, kept in the app's preferences; a debug build's
`PQ_SERVER_URL` overrides it for that run without changing it.
`ServerAddressPolicy` decides which addresses a build may use: release builds
an allowlist (https, thepuzzlequest.com or a subdomain, no other port; no user
name, path, query or fragment), debug builds any http or https address (their
network security configuration, in `src/debug/`, allows plain HTTP). A custom
address is reduced to its origin, then checked to be a PuzzleQuest server
(`ServerCheck` fetches `/api/v1/openapi.json` and reads its title and
version) before the app switches to it. Each server's session token is kept
apart (`KeystoreTokenVault`, one entry for each server's origin), so one
server's token is never sent to another. Switching servers
(`ServerConnection.switchTo`) asks first when someone is signed in, then
forgets the old server's token, tells that server the session has ended, and
makes a new session and API client for the new one; the app rebuilds its
screens for it. If servers outside PuzzleQuest's domain are wanted later, a
signed statement of the server's origin could be accepted alongside the
allowlist (see `ServerAddressPolicy.admit`).

## Layout

- `app/`: the app itself, kept thin: `MainActivity`, how it chooses its server
  (`AppServer`), its screens (Compose, in `ui/`), each server's token kept
  with the Android Keystore (`KeystoreTokenVault`), and its words in
  `res/values/strings.xml` (English only for now).
  - `ui/theme/Theme.kt`: the brand's colors and display face (below).
  - `src/debug/`: what only debug builds have: plain HTTP, to any server.
  - `src/androidTest/`: UI tests (Compose) of whole journeys, and tests that
    need a phone, such as the Keystore's.
- `core/`: a Kotlin library holding everything that can be tested without a
  phone or emulator.
  - The API client, generated as it builds from `core/openapi.json` by
    [OpenAPI Generator](https://openapi-generator.tech), into
    `core/build/generated/openapi`. Nothing in it is written by hand.
  - `src/main/kotlin`: what the screens use: `PuzzleQuestApi` (the calls,
    returning the app's own types and throwing `ApiError`), `SessionStore`
    (who is signed in, and its token), `ServerConnection` (the chosen server
    and its session), `LiveUpdateFollower` (following the
    API's live updates over its websocket, reconnecting after a drop), and
    models such as `SignInModel`, `HomeModel`, `QuestModel` (with the puzzle
    master's messages and challenges), `PuzzleModel` and `OptionsModel`,
    which publish their state as Kotlin flows; and
    `MarkdownBlock`, which reads a text puzzle's Markdown into blocks the
    app draws itself (HTML in it shows as plain text, as on the web).
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
`scripts/client-openapi.jq` explains it. Two more things are set up in
`core` itself:
- `ApiJson` leaves optional fields that aren't set out of requests: the
  generated client would send them as `null`, which the API takes to mean
  "set it to null", and refuses where that isn't allowed.
- The generator can't tell the live-update messages apart (`LiveUpdate` is
  one of several shapes), so `LiveUpdates.kt` reads each message's `message`
  field and decodes the generated class it names, such as
  `LiveUpdatePuzzleSolved`. A message it doesn't know is ignored.

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

These run on the computer: reading newer responses, explaining errors,
restoring and ending sessions, the home screen's list and clocks,
following live updates, reading a player's quest and its standings, what's
announced to TalkBack as a quest changes, the quest screen's model, reading
a puzzle, the puzzle screen's model (activating, scanning, guessing, hints,
going on to the next stop), reading Markdown, the puzzle master's messages
(each shown once on this phone) and challenges (answering, reopening,
hiding), push notifications (reading one, offering them, registering the
phone, which are shown), which
server addresses each build allows, checking
a server is PuzzleQuest's, keeping each server's token apart, switching
servers, and the Options screen's model, against a fake server
([MockWebServer](https://github.com/square/okhttp/tree/master/mockwebserver))
and a fake websocket, on the coroutine test library's virtual clock.

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
sends for a wrong password, an invalid request, and too many attempts; make
quests for new players as the administrator and check they're listed once
started, and heard of through live updates over the real websocket; and send
the verification email again, all through the generated client; and check
fetch a quest before and after it starts, solve its stop, race two players to
the standings, reset a quest, and follow a quest's live updates; activate a
puzzle (refusing a wrong code), reveal its hints, answer it wrongly and then
rightly, act while the quest is paused, and look for a puzzle after a reset;
answer the puzzle master's challenge wrongly and then rightly, and check a
player who can't be texted is reached once their phone is registered; check
that the check before switching servers accepts a real server, and finds
nothing where nothing listens. Without
`PQ_TEST_SERVER_URL` they're skipped.

### UI tests, and everything on a phone

With the test server's variables loaded, and an emulator running or a phone
connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The build passes the test server's variables on to the tests, and first
forwards the server's port to the phone (`adb reverse`; with more than one
phone or emulator connected, set `ANDROID_SERIAL` to choose). The UI tests
launch the app signed out of every server, pointed at the test server (with
the intent extras `PQ_SIGNED_OUT`, `PQ_SERVER_URL` and `PQ_UI_TESTING`, which
only debug builds read), and go through whole journeys: signing in and out, a session kept
between launches, a wrong password, a quest appearing on the home screen as
the administrator starts it, sending the verification email again, and
staff being pointed to the website. On a quest's screen, they see the current
stop, a pause and a resume, finishing a race (the finish, the standings with
the player's own entry marked, and a solved stop's solution), and a reset,
each happening through the API while the screen is open, so it can only show
through live updates. On a puzzle's screen, they activate it (a wrong code,
then the right one), reveal a hint and decline the one that gives the solution
away, answer wrongly and then rightly, and go on to the next stop; see a pause
and a resume, and a solve by a teammate, through to the finish; and see a
reset, which the quest screen beneath hears of too. Notifications are
offered on a quest until the player says not now (while the emulator hasn't
yet allowed them), and once allowed, the phone is registered, so the puzzle
master can reach a player who can't be texted. A challenge from the
puzzle master pops up as it's sent; they answer it wrongly and then rightly,
see the confirmation, put it away and reopen it, hide it, and find it in the
history. Only a player with the app or a phone number can be sent a message,
so the tests first register a pretend phone for them (`PUT
/sessions/current/push-target`); the test server sends nothing to it. Without `PQ_SERVER_URL` (and with
`PQ_RESET_SERVER`, so Options is as a new install has it), they check
Options starts with Production, choose the test server as a custom one and
sign in to it (the sign-in screen naming it), and switch back to Production
from the account menu, which asks first and signs out; they never ask
Production anything. The Keystore tests check each server's token is kept
apart, and encrypted. The tests set up players and quests through the
generated client. Without the variables, they're skipped. They find controls
by their test tags (such as `sign-in.user-name`), so those stay put when
wording changes.

## Continuous integration

`.github/workflows/ci.yml` runs on GitHub's Linux runners: the check of the
generated-from description, formatting, the unit tests, lint, the builds,
the test server, the contract tests, and the UI tests in an Android 17
emulator. When a run fails, the reports, test results and the server's logs
are kept as an artifact.

## Releasing

Google Play takes an Android App Bundle (`.aab`), signed with an upload key.
With Play App Signing, Google keeps the key that signs the app for phones; the
upload key only proves an upload is yours, and can be replaced through Play
Console if it's lost.

Make the upload key once, and keep it (and its passwords) somewhere safe,
outside the repository:

```sh
keytool -genkeypair -v -keystore ~/keys/puzzlequest-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Then name it in `~/.gradle/gradle.properties` (your own, not the
repository's), which the build reads:

```properties
pq.upload.storeFile=/Users/you/keys/puzzlequest-upload.jks
pq.upload.storePassword=…
pq.upload.keyAlias=upload
pq.upload.keyPassword=…
```

Without all four, release builds are left unsigned, as on CI. To build the
bundle for Play Console:

```sh
./gradlew :app:bundleRelease
```

It's at `app/build/outputs/bundle/release/app-release.aab`. The version people
see is `versionName` in `app/build.gradle.kts` (1.0.0 for the first release);
`versionCode` must be higher for every upload, so raise it each time.

## Copyright

Copyright © 2026 jimevans. All rights reserved. The source is public so it can
be read and reviewed, but no licence to use, copy, modify, or distribute it is
granted for now. Barlow Condensed is © 2017 The Barlow Project Authors, under
the SIL Open Font License 1.1.
