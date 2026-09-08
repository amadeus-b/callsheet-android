# Callsheet

An Android app for working through a list of businesses on the phone,
one call at a time. Not a CRM — a work list.

![License](https://img.shields.io/badge/License-MIT-blue)
![Android](https://img.shields.io/badge/Android-11%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7f52ff)

Cold calling does not need a customer management system. It needs a list that
hands you one business at a time: dial, record what happened, next. That is what
this app does — offline, on the device, without an account and without a server.

The user interface is German; the code and documentation are English.

## What it does

- **Imports** a business database from a JSON file, repeatedly, without ever
  overwriting your own work
- **Work list** filtered by industry, city and status, with full-text search
- **Dials at a tap** via `ACTION_DIAL` — the app never places a call itself
- **Call duration** read automatically from the Android call log
- **Status, note and follow-up** in one go after hanging up
- **"Today"** with follow-ups that are due *and* overdue
- **Contacts** per business, merged with the device's phone book
- **Hand-entered businesses**, for the referral that arrives over the phone
- **Do-not-call** enforced as a hard exclusion in the database query, not a filter

## Installation

The app is not in the Play Store. There are two ways in.

### Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) fetches apps straight from
their source and tells you about updates.

1. In Obtainium, **add an app**
2. Enter this URL:

   ```
   https://github.com/amadeus-b/callsheet-android
   ```

3. Confirm. Obtainium finds the latest release and installs the APK; it will
   report future versions by itself.

### APK by hand

Download the APK from the
[latest release](https://github.com/amadeus-b/callsheet-android/releases/latest), open
it on the device in a file manager and confirm the install. Android asks once
whether that file manager may install unknown apps.

### After installing

On first start the app asks for access to the **call log**. That is how it reads
the duration after you hang up. Refuse it and everything still works — the status
is then set by hand.

How the data gets in and what the daily flow looks like is covered in the
[usage guide](docs/usage.md).

## Permissions

| Permission | What for |
|---|---|
| `READ_CALL_LOG` | call duration after hanging up; optional, the app runs without it |
| `READ_CONTACTS`, `WRITE_CONTACTS` | writing contacts into the phone book; optional |
| `INTERNET` | declared but unused — reserved for a later sync |

That is the complete list from the manifest. Dialling goes through `ACTION_DIAL`,
which needs no permission: the dialler opens with the number filled in and you
press call yourself.

## Building it yourself

You need a JDK 17 or newer and the Android SDK; how to set both up without root
is described in [docs/development.md](docs/development.md).

```bash
./gradlew testDebugUnitTest      # 92 unit tests
./gradlew assembleRelease        # app/build/outputs/apk/release/
```

Without a release key of your own the build signs with the debug key — fine for
trying it out, not for published releases.

## Documentation

| Document | Contents |
|---|---|
| [Usage](docs/usage.md) | import, daily flow, blocking, follow-ups |
| [Architecture](docs/architecture.md) | screens, call flow, technical decisions |
| [Data model](docs/data-model.md) | import format, tables, phone book merge |
| [Development](docs/development.md) | toolchain, tests, known pitfalls |

## Technology

Kotlin and Jetpack Compose, `minSdk` 30. Storage in SQLite through
`SQLiteOpenHelper` — no Room, no dependency injection, no JSON library
(`org.json` from the framework does), no network stack. The dependencies are
Compose, Lifecycle and `core-ktx`, and that is all.

The restraint is deliberate: every library has to pay for itself.

## No synchronisation yet

The data model already carries what a sync would need — `updated_at` on every
record, `calls` as an append-only table with device-generated UUIDs. The logic
itself does not exist: there is no server, and no code in this repository talks
to one.

## Privacy

The app sends nothing. All data stays on the device. The imported business
database contains personal data and belongs neither in this repository nor in the
APK — it is loaded at runtime through the file picker.

## Credits

The launcher icon is adapted from
[Material Symbols](https://github.com/google/material-design-icons) by Google,
licensed under the Apache License 2.0. See [NOTICE](NOTICE).

## License

[MIT](LICENSE) — © 2026 Callsheet contributors
