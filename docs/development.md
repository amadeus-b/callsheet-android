# Development

## Toolchain

| Tool | Version in this project |
|---|---|
| JDK | 17 or newer (developed against Temurin 25) |
| Android SDK | compileSdk 37, minSdk 30 |
| Gradle | 9.7.1 (through the wrapper) |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.20 |

Everything can be set up inside the user's home directory without `sudo` — no
system-wide install is needed, and Android Studio is not required.

### JDK

Unpack Temurin from an archive, say into `~/jdk`:

```bash
curl -s 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux' \
  | grep -o '"link":"[^"]*jdk[^"]*\.tar\.gz"' | head -1
export JAVA_HOME=~/jdk/<unpacked-directory>
export PATH="$JAVA_HOME/bin:$PATH"
```

### Android SDK

Fetch the command line tools from
<https://developer.android.com/studio#command-line-tools-only> and unpack them.
The `cmdline-tools/latest/` nesting is mandatory — without it `sdkmanager` fails
with a misleading message:

```bash
mkdir -p ~/android-sdk/cmdline-tools
# unpack the archive into ~/android-sdk/cmdline-tools, then:
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37" "build-tools;37.0.0"
```

Put those three `export` lines into `~/.bashrc`, otherwise they are gone next
session.

### Project

Create `local.properties` — the file is in `.gitignore` and does not belong in
the repository:

```
sdk.dir=/home/<user>/android-sdk
```

## Building and testing

```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # app/build/outputs/apk/release/, optimised by R8
```

The release build signs with the debug key as long as no release key is
configured. That is fine for trying things out, but not for published releases,
because Android only accepts an update when the signature stays the same.

The app reaches a device via `adb install -r <apk>`, or by copying the file over
and opening it in a file manager.

## Releasing

`tools/release.sh patch|minor|major|x.y.z` raises the version, runs the tests,
builds and signs the APK, tags the commit and publishes the GitHub release.

Write the `CHANGELOG.md` section for the new version first and commit it. The
script reads that section and uses it as the release notes; without it the
release is refused, so a published version always says what changed in it.

## Language in the codebase

Code, identifiers and comments are English. Three things stay German, and they
are data or presentation rather than code:

- **UI strings.** The app is used in German.
- **Values that come out of the import file**, above all the `(kein Ziel)`
  suffix the target rule keys on, and the JSON field names the importer reads
  (`gewerk`, `herkunft`, `kontakt`, `alle_kategorien`).
- **Domain terms** whose German wording is the established one: `Sperre`
  (a recorded objection to being contacted), `Wiedervorlage` (follow-up).

## Tests

The unit tests run under Robolectric and cover the logic that earns it: import
and field mapping, the target rule, number normalisation, filter logic, follow-up
computation, evaluating the call log and the contact merge. No test acrobatics
for screen layouts.

Robolectric otherwise downloads its Android runtime itself, which fails in
sandboxed environments. This project has Gradle resolve the JAR and runs
Robolectric offline — see the end of `app/build.gradle.kts`.

**Test data is always made up.** Real business data belongs in no test, no
repository and no APK.

## Pitfalls

Every item here has already cost somebody time.

### The call log appears with a delay

The entry in `CallLog.Calls` is **not necessarily** there the moment the user
returns to the app after hanging up. Querying straight from `onResume` sometimes
turns up the previous call, or nothing at all.

The app therefore tries three times, a second apart. If it finds nothing, it
offers the status buttons without a duration rather than storing a wrong one.

Both conditions must be checked when matching: same number **and** a time after
the remembered dial attempt. Searching by number alone turns up older calls to
the same business.

### Duration 0 does not always mean "no answer"

Somebody can pick up and hang up immediately. The `no_answer` proposal is
therefore overridable and never set automatically and finally.

### Phone numbers

The source delivers them in varying shapes: `+49 30 1234567`, `0301234567`, with
and without spaces. `phoneUnformatted` is usually cleaner than `phone`, but is
not always present.

The import normalises to E.164 (`+49…`) and **stores the normalised form**,
otherwise matching against the call log finds nothing — Android logs the number
as dialled.

Comparing against the log needs tolerance: the last digits are compared, not the
string. `+49301234567` and `0301234567` are the same number.

### The source's categories are unreliable

The same business shows up as "Elektriker", "Ingenieur" or "Handwerk" depending
on the search context, under the same `placeId`. Hence `alle_kategorien`.

Filtering and display use **`gewerk`**, which is pre-classified; `categoryName`
only as a fallback. During the design an electrical contractor turned up as
"Informationsservice" — no logic may lean on that.

### A repeated import must not destroy work

Research continues, businesses get added. On a second import, status, note,
follow-up and call history must **under no circumstances** be overwritten; only
master data is updated.

That is the most expensive mistake imaginable in this app — weeks of phone calls
would be gone. There is a test for it.

### Permissions must not block

If `READ_CALL_LOG` is refused, the app has to keep working in full, just without
automatic durations. No loop of repeated prompts, no locked screen. The same goes
for the contacts permissions.

### Large lists in Compose

A few thousand records are no problem as long as `LazyColumn` works with stable
keys (`key = place_id`) and filtering happens in the database query rather than
in memory.

### Sorting and searching with umlauts

SQLite only lower-cases ASCII on its own. Without the extra `search_text` column,
searching for „müller" would not find „Müller".

### One `Database` helper for the whole process

`Repository` and `SyncStore` both write to the same file, and a sync can start
right after a call is logged while the user is already editing the next
business — a 500-row transaction and a user edit genuinely overlap in time.
Two separate `SQLiteOpenHelper` instances would each open their own connection
and contend on file locks instead of sharing the in-process lock a single
connection gets for free. `Database.instance(context)` hands out one shared
helper; neither class constructs `Database` directly.

### Downgrading must not destroy the database

`SQLiteOpenHelper.onDowngrade` throws by default. Sideloading an older build
over a newer one — routine for anyone using Obtainium — would otherwise leave
the database unable to open at all; recovery means reinstalling, which wipes
every business and every call ever logged. `Database.onDowngrade` is a
deliberate no-op: an older build just loses synchronisation, never the stock.

## Open questions, answerable only on a device

1. **The call log under GrapheneOS** — whether `CallLog.Calls` fills fast enough
   after hanging up.
2. **The R8-optimised build** — it has not run on a device yet. If it behaves
   oddly, comparing against the debug build helps.
3. **Scrolling** through a few thousand entries on real hardware.
