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
- **Appointment on site** with a time, a length and an address, mirrored into
  the device's calendar
- **"Today"** with the day's appointments above follow-ups that are due *and*
  overdue
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

Calendar access is asked for only when you switch appointments on in the
settings. Refuse it, or leave it off, and appointments still work — they simply
stay in the app, and the picker says it cannot show you which hours are taken.

How the data gets in and what the daily flow looks like is covered in the
[usage guide](docs/usage.md).

## Permissions

| Permission | What for |
|---|---|
| `READ_CALL_LOG` | call duration after hanging up; optional, the app runs without it |
| `READ_CONTACTS`, `WRITE_CONTACTS` | writing contacts into the phone book; optional |
| `READ_CALENDAR`, `WRITE_CALENDAR` | mirroring appointments into the device calendar; optional |
| `INTERNET` | used for synchronisation when a server is configured |

That is the complete list from the manifest. Dialling goes through `ACTION_DIAL`,
which needs no permission: the dialler opens with the number filled in and you
press call yourself.

## Building it yourself

You need a JDK 17 or newer and the Android SDK; how to set both up without root
is described in [docs/development.md](docs/development.md).

```bash
./gradlew testDebugUnitTest      # 149 unit tests
./gradlew assembleRelease        # app/build/outputs/apk/release/
```

Without a release key of your own the build signs with the debug key — fine for
trying it out, not for published releases.

## Documentation

| Document | Contents |
|---|---|
| [Usage](docs/usage.md) | import, daily flow, blocking, follow-ups, appointments |
| [Architecture](docs/architecture.md) | screens, call flow, technical decisions |
| [Data model](docs/data-model.md) | import format, tables, phone book merge |
| [Development](docs/development.md) | toolchain, tests, known pitfalls |

## Technology

Kotlin and Jetpack Compose, `minSdk` 30. Storage in SQLite through
`SQLiteOpenHelper` — no Room, no dependency injection, no JSON library
(`org.json` from the framework does), no network stack. The dependencies are
Compose, Lifecycle and `core-ktx`, and that is all.

The restraint is deliberate: every library has to pay for itself.

## Synchronisation

Off by default. Enter a server address and access key in the settings screen
and the app keeps businesses, contacts, calls and follow-ups in step with that
server; leave both fields empty and nothing changes about how the app behaves —
it works only on the device, as before.

Once configured, a sync runs on its own — when the app starts, when it returns
to the foreground, and right after a call is logged. There is no background
service and no schedule to configure. A failed attempt is silent: the app
tries again the next time one of those moments comes around. The settings
screen is where the truth lives — when the last full sync went through, and
how many changes are still waiting to go up.

Only an address starting with `https://` is accepted; Android blocks plain
`http://` outright, and a bearer token has no business travelling
unencrypted anyway.

The settings screen also carries an **„Alles erneut hochladen"** action,
behind a confirmation. It marks the entire local stock as unsent, for the two
situations a normal sync cannot recover from on its own: the server was
restored from a backup older than this device's own watermark, or the app is
being pointed at a server that has never seen this device's data at all.

The server itself is a separate project; this repository contains only the
client side.

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
