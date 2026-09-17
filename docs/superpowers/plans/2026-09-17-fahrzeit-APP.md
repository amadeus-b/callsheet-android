# Fahrzeit in Übersicht und Betriebsmaske – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die vom Server synchronisierte Entfernung und Fahrzeit pro Adresse speichern, bei Adressänderung leeren und als `23 km · 21 min` in der Übersicht (Hauptadresse) und unter jeder Anschrift in der Betriebsmaske anzeigen.

**Architecture:** Schema 12 fügt `drive_meters`/`drive_seconds` an `business_addresses`; der Sync nimmt sie über das Schema mit. Ein einmaliges Refetch-Flag holt die Werte nach dem Update. Formatierung als reine Funktion in `Addresses`, Anzeige in `Components.kt` und `BusinessDetail.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, SQLiteOpenHelper, Robolectric/JUnit4.

**Spec:** liegt im privaten Server-Repo (`server/docs/superpowers/specs/2026-09-17-fahrzeit-design.md`), weil sie den Startpunkt enthält. Die für die App nötigen Teile stehen vollständig hier.

## Global Constraints

- Dieses Repo ist **öffentlich**: keine Startadresse, keine echten Betriebsdaten, keine Hostnamen. Testdaten erfunden.
- Neue Spalten nullable; NULL heißt unbekannt und zeigt nichts.
- Die App berechnet nie selbst eine Fahrzeit, sie leert die Werte nur.
- Anzeigeformat: km auf ganze Kilometer gerundet, unter 1 km `< 1 km`; Minuten gerundet, mindestens `1 min`; ab 60 min `1 h 05 min`; Trenner ` · `.
- Version 1.7.0, `versionCode` 13 (setzt `tools/release.sh`). Release/Push nur mit Freigabe des Users.
- Tests: `./gradlew testDebugUnitTest`.

---

### Task 1: Schema 12 und Modell

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt` (`TABLE_ADDRESSES`, `onUpgrade`, `VERSION`, neue Konstante)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt:62-75` (`BusinessAddress`)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt:1277-1287` (`addressFromCursor`)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt`

**Interfaces:**
- Produces: Spalten `business_addresses.drive_meters INTEGER`, `drive_seconds INTEGER`; `BusinessAddress.driveMeters: Int? = null`, `BusinessAddress.driveSeconds: Int? = null`; `Database.VERSION = 12`

- [ ] **Step 1: Failing tests in `MigrationTest.kt`** (nach dem Block „from version 10“)

```kotlin
    // --- from version 11, the road 1.6.0 devices are on -----------------------

    private fun createVersionEleven() {
        createVersionTen()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL("ALTER TABLE appointments ADD COLUMN calendar_missing_since INTEGER")
        db.execSQL("ALTER TABLE appointments ADD COLUMN calendar_ok_since INTEGER")
        db.version = 11
        db.close()
    }

    @Test
    fun `an upgrade from version eleven adds the drive time empty and marks nothing`() {
        createVersionEleven()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT drive_meters, drive_seconds, dirty FROM business_addresses", null).use { c ->
            assertTrue(c.moveToFirst())
            do {
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
            } while (c.moveToNext())
        }
    }

    @Test
    fun `a fresh database and one upgraded from version eleven have the same address columns`() {
        createVersionEleven()
        val upgraded = Database(context).readableDatabase.let { db -> columnsOf("business_addresses", db).also { db.close() } }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = columnsOf("business_addresses", Database(context).readableDatabase)

        assertTrue("drive_seconds" in fresh)
        assertEquals(fresh, upgraded)
    }
```

Prüfen, ob `createVersionTen()` (über die Kette ab Version 7) eine Zeile in `business_addresses` hat. Wenn nicht, im ersten Test vor `Database(context)` eine Adresszeile per `openOrCreateDatabase` einfügen (`INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) VALUES ('A1', 'alt-1', 'Musterstadt', 0, '2026-09-07T10:00:00+02:00', 0)`) und `dirty` auf 0 prüfen.

- [ ] **Step 2: Laufen lassen, muss scheitern**

Run: `./gradlew testDebugUnitTest --tests '*MigrationTest*'`
Expected: FAIL (`no such column: drive_meters`)

- [ ] **Step 3: Implementieren**

In `TABLE_ADDRESSES` nach `position INTEGER,`:

```kotlin
                drive_meters  INTEGER,
                drive_seconds INTEGER,
```

Neue Konstante neben `COLUMNS_APPOINTMENTS_11`:

```kotlin
        /** Schema 12: road distance and driving time from the office, written by the server. */
        private val COLUMNS_ADDRESSES_12 = listOf(
            "ALTER TABLE business_addresses ADD COLUMN drive_meters INTEGER",
            "ALTER TABLE business_addresses ADD COLUMN drive_seconds INTEGER",
        )
```

In `onUpgrade` nach `if (old < 11)`:

```kotlin
        if (old < 12) {
            // Nothing to carry over and nothing to mark: the server fills the
            // values, and a sync after this version fetches them (see
            // Preferences.refetchedForDriveTimes).
            for (sql in COLUMNS_ADDRESSES_12) db.execSQL(sql)
        }
```

`const val VERSION = 12`.

`BusinessAddress` nach `position`:

```kotlin
    /** Road distance from the office in metres, from the server. Cleared when the address changes. */
    val driveMeters: Int? = null,
    /** Driving time from the office in seconds, from the server. Cleared when the address changes. */
    val driveSeconds: Int? = null,
```

`addressFromCursor` ergänzen:

```kotlin
        driveMeters = c.int("drive_meters"),
        driveSeconds = c.int("drive_seconds"),
```

- [ ] **Step 4: Tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (auch `SyncSchemaTest`, bestehende Migrationstests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt
git commit -m "Schema 12: Entfernung und Fahrzeit pro Adresse"
```

### Task 2: Sync – Lücke füllen und einmal neu abholen

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt` (Flag + Konstante)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt:118-122`
- Test: `app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt`
- Test: die bestehenden Tests für die Refetch-Flags (per `grep -rn refetchedForAttendees app/src/test` finden) um das neue Flag ergänzen

**Interfaces:**
- Consumes: Spalten aus Task 1
- Produces: `Preferences.refetchedForDriveTimes: Boolean`

- [ ] **Step 1: Failing tests in `SyncStoreTest.kt`**

```kotlin
    private fun eineAdresse(id: String, zeit: String, dirty: Int) = schreibe(
        "INSERT INTO business_addresses (id, place_id, street, postal_code, city, position, updated_at, dirty) " +
            "VALUES ('$id', 'P1', 'Musterweg 1', '85049', 'Musterstadt', 0, '$zeit', $dirty)"
    )

    private fun adresseJson(id: String, zeit: String) = JSONObject().apply {
        put("id", id); put("place_id", "P1"); put("street", "Musterweg 1"); put("postal_code", "85049")
        put("city", "Musterstadt"); put("position", 0); put("updated_at", zeit)
    }

    @Test
    fun `a standstill fills an address's drive time`() {
        eineAdresse("A1", "2026-09-07T10:00:00+02:00", dirty = 0)
        val incoming = adresseJson("A1", "2026-09-07T10:00:00+02:00").put("drive_meters", 23400).put("drive_seconds", 1260)

        store.apply(leereAntwort().put("business_addresses", JSONArray(listOf(incoming))))

        assertEquals(listOf("23400", "1260", "0"), zeile("SELECT drive_meters, drive_seconds, dirty FROM business_addresses WHERE id = 'A1'"))
    }

    @Test
    fun `a drive time never overwrites a newer address here`() {
        eineAdresse("A1", "2026-09-07T11:00:00+02:00", dirty = 1)
        val incoming = adresseJson("A1", "2026-09-07T10:00:00+02:00").put("drive_meters", 23400).put("drive_seconds", 1260)

        store.apply(leereAntwort().put("business_addresses", JSONArray(listOf(incoming))))

        assertEquals(listOf(null, null, "1"), zeile("SELECT drive_meters, drive_seconds, dirty FROM business_addresses WHERE id = 'A1'"))
    }
```

Für das Flag: im bestehenden Test, der `refetchedForAttendees` prüft, dieselbe Erwartung für `refetchedForDriveTimes` ergänzen (Watermark 0 beim ersten Sync, Flag danach `true`).

- [ ] **Step 2: Laufen lassen**

Run: `./gradlew testDebugUnitTest --tests '*SyncStoreTest*' --tests '*SyncEngine*'`
Expected: Die beiden SyncStore-Tests PASS (Sync liest das Schema), der Flag-Test FAIL (`refetchedForDriveTimes` unbekannt). Scheitert ein SyncStore-Test, ist `Rows`/`SyncStore` nicht schemagetrieben: anhalten und melden.

- [ ] **Step 3: Flag implementieren**

`Preferences.kt` nach `refetchedForAttendees`:

```kotlin
    /**
     * Whether this device has fetched everything once since schema 12. The
     * server writes the drive times with their updated_at unchanged; a row this
     * device already fetched before the update comes down again at a
     * standstill and the store fills the gap. A flag for the reason
     * [refetchedForAppointments] gives.
     */
    var refetchedForDriveTimes: Boolean
        get() = store.getBoolean(REFETCHED_FOR_DRIVE_TIMES, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_DRIVE_TIMES, value).apply()
```

Konstante: `const val REFETCHED_FOR_DRIVE_TIMES = "refetched_for_drive_times"`.

`SyncEngine.kt` nach dem `refetchedForAttendees`-Block:

```kotlin
            // Once more, on the first sync that runs on schema 12 — see
            // Preferences.refetchedForDriveTimes.
            if (!prefs.refetchedForDriveTimes) {
                prefs.watermark = 0
                prefs.refetchedForDriveTimes = true
            }
```

- [ ] **Step 4: Tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt app/src/test
git commit -m "Sync: Fahrzeit als Lücke füllen, nach Schema 12 einmal neu abholen"
```

### Task 3: Fahrzeit leeren, wenn sich die Anschrift ändert

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt` (`saveAddresses` ca. Zeile 914-925, `importAddress` ca. Zeile 207-225)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt`

**Interfaces:**
- Consumes: Spalten und `BusinessAddress.driveMeters/driveSeconds` aus Task 1

- [ ] **Step 1: Failing tests** (nach `twoAddresses()`-Tests)

```kotlin
    @Test
    fun `changing the street clears the drive time, changing only the label keeps it`() = runTest {
        val (head, branch) = twoAddresses()
        execute("UPDATE business_addresses SET drive_meters = 23400, drive_seconds = 1260")

        repo.saveAddresses(
            "P1",
            Addresses.drafts(listOf(head, branch)).let { drafts ->
                listOf(drafts[0].copy(label = "Hauptsitz"), drafts[1].copy(street = "Hafenstraße 7"))
            },
        )

        val after = repo.addresses("P1")
        assertEquals(23400, after[0].driveMeters)
        assertEquals(1260, after[0].driveSeconds)
        assertNull(after[1].driveMeters)
        assertNull(after[1].driveSeconds)
    }

    @Test
    fun `a re-import that moves the main address clears its drive time`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE business_addresses SET drive_meters = 23400, drive_seconds = 1260 WHERE id = 'main-P1'")

        import(FIRST_IMPORT.replace("Musterweg 1", "Musterweg 3"))

        val main = repo.addresses("P1").single { it.id == "main-P1" }
        assertEquals("Musterweg 3", main.street)
        assertNull(main.driveMeters)
        assertNull(main.driveSeconds)
    }

    @Test
    fun `an unchanged re-import keeps the drive time`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE business_addresses SET drive_meters = 23400, drive_seconds = 1260 WHERE id = 'main-P1'")

        import(FIRST_IMPORT)

        assertEquals(1260, repo.addresses("P1").single { it.id == "main-P1" }.driveSeconds)
    }
```

- [ ] **Step 2: Laufen lassen, muss scheitern**

Run: `./gradlew testDebugUnitTest --tests '*RepositoryTest*'`
Expected: FAIL bei den ersten beiden Tests (Werte bleiben stehen)

- [ ] **Step 3: Implementieren**

In `saveAddresses`, im `if (moved)`-Block:

```kotlin
                        if (moved) {
                            putNull("latitude")
                            putNull("longitude")
                            // Distance and time belonged to the old address too.
                            putNull("drive_meters")
                            putNull("drive_seconds")
                        }
```

KDoc von `saveAddresses` ergänzen: „Changing street, postal code or city clears the coordinates and the drive time — they belonged to the old address.“

In `importAddress` nur für das Update einer vorhandenen Zeile (nach `if (same) return false`):

```kotlin
            // The drive time belonged to the address that was there.
            values.putNull("drive_meters")
            values.putNull("drive_seconds")
            db.update("business_addresses", values, "id = ?", arrayOf(id))
```

- [ ] **Step 4: Tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Adresse geändert: Fahrzeit leeren wie die Koordinaten"
```

### Task 4: Formatierung und Werte der Hauptadresse für die Liste

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Addresses.kt` (neue Funktion `driveLine`)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt` (`Business`)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt` (`MAIN_CITY_SUBQUERY`, `fromCursor`)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/AddressesTest.kt`, `RepositoryTest.kt`

**Interfaces:**
- Produces:
  - `Addresses.driveLine(meters: Int?, seconds: Int?): String?`
  - `Business.driveMeters: Int? = null`, `Business.driveSeconds: Int? = null`

- [ ] **Step 1: Failing tests**

`AddressesTest.kt`:

```kotlin
    @Test
    fun `the drive line rounds kilometres and minutes`() {
        assertEquals("23 km · 21 min", Addresses.driveLine(23_400, 1_260))
        assertEquals("24 km · 22 min", Addresses.driveLine(23_500, 1_290))
    }

    @Test
    fun `the drive line keeps short trips readable`() {
        assertEquals("< 1 km · 1 min", Addresses.driveLine(400, 20))
        assertEquals("1 km · 1 min", Addresses.driveLine(999, 59))
    }

    @Test
    fun `the drive line shows hours from sixty minutes on`() {
        assertEquals("98 km · 1 h 05 min", Addresses.driveLine(98_000, 3_900))
        assertEquals("80 km · 1 h 00 min", Addresses.driveLine(80_000, 3_590))
    }

    @Test
    fun `no drive line without both values`() {
        assertNull(Addresses.driveLine(null, 1_260))
        assertNull(Addresses.driveLine(23_400, null))
    }
```

`RepositoryTest.kt`:

```kotlin
    @Test
    fun `the list carries the main address's drive time`() = runTest {
        twoAddresses()
        execute("UPDATE business_addresses SET drive_meters = 23400, drive_seconds = 1260 WHERE id = 'main-P1'")
        execute("UPDATE business_addresses SET drive_meters = 99000, drive_seconds = 5000 WHERE id <> 'main-P1'")

        val listed = repo.businesses(Filter()).single { it.placeId == "P1" }

        assertEquals(23400, listed.driveMeters)
        assertEquals(1260, listed.driveSeconds)
        assertEquals(1260, repo.business("P1")!!.driveSeconds)
    }
```

Den Namen der Listenfunktion und den Filter-Konstruktor aus `Repository.kt:239-245` bzw. den bestehenden Filter-Tests übernehmen, falls sie anders heißen.

- [ ] **Step 2: Laufen lassen, muss scheitern**

Run: `./gradlew testDebugUnitTest --tests '*AddressesTest*' --tests '*RepositoryTest*'`
Expected: FAIL (`driveLine`/`driveMeters` unbekannt)

- [ ] **Step 3: Implementieren**

`Addresses.kt`:

```kotlin
    /**
     * Distance and driving time from the office on one line, „23 km · 21 min".
     * Null unless both are known.
     */
    fun driveLine(meters: Int?, seconds: Int?): String? {
        if (meters == null || seconds == null) return null
        val km = Math.round(meters / 1000.0)
        val distance = if (meters < 500) "< 1 km" else "$km km"
        val minutes = maxOf(1L, Math.round(seconds / 60.0))
        val time = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
        return "$distance · $time"
    }
```

Hinweis: `999 m` rundet auf `1 km`, `400 m` ergibt `< 1 km` (Grenze 500 m, damit nie `0 km` erscheint). `3590 s` = 59,8 min → 60 → `1 h 00 min`.

`Business` nach `editedFields`:

```kotlin
    /** Distance and driving time of the main address, for the lists. See Addresses.driveLine. */
    val driveMeters: Int? = null,
    val driveSeconds: Int? = null,
```

`Repository.kt`, `MAIN_CITY_SUBQUERY` erweitern (ein Ausdruck mit drei Unterabfragen; der Name bleibt, damit alle fünf Aufrufer unverändert funktionieren):

```kotlin
        private const val MAIN_ADDRESS_ORDER =
            "FROM business_addresses ba WHERE ba.place_id = b.place_id ORDER BY ba.position IS NULL, ba.position, ba.id LIMIT 1"

        /** The main address's city, distance and time — see fromCursor. */
        const val MAIN_CITY_SUBQUERY =
            "(SELECT ba.city $MAIN_ADDRESS_ORDER) AS main_city, " +
                "(SELECT ba.drive_meters $MAIN_ADDRESS_ORDER) AS main_drive_meters, " +
                "(SELECT ba.drive_seconds $MAIN_ADDRESS_ORDER) AS main_drive_seconds"
```

`fromCursor` ergänzen:

```kotlin
        driveMeters = c.int("main_drive_meters"),
        driveSeconds = c.int("main_drive_seconds"),
```

Prüfen, dass `c.int` bei fehlender Spalte nicht wirft (alle Abfragen mit `fromCursor` nutzen `MAIN_CITY_SUBQUERY`; per `grep -n "fromCursor" Repository.kt` bestätigen).

- [ ] **Step 4: Tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data app/src/test
git commit -m "Fahrzeit: Formatierung und Werte der Hauptadresse für die Listen"
```

### Task 5: Anzeige in Übersicht und Betriebsmaske

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt:123-126`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt:580-588`

**Interfaces:**
- Consumes: `Addresses.driveLine`, `Business.driveMeters/driveSeconds`, `BusinessAddress.driveMeters/driveSeconds`

- [ ] **Step 1: Übersicht**

```kotlin
            val secondLine = listOfNotNull(
                business.industry?.takeIf { it.isNotBlank() },
                business.city?.takeIf { it.isNotBlank() },
                Addresses.driveLine(business.driveMeters, business.driveSeconds),
            ).joinToString(" · ")
```

Import `io.github.amadeusb.callsheet.data.Addresses` ergänzen, falls nicht vorhanden.

- [ ] **Step 2: Betriebsmaske**

```kotlin
        Addresses.ordered(addresses).forEach { address ->
            address.oneLine?.let { line ->
                DataRow(address.label?.trim()?.ifEmpty { null } ?: "Anschrift", line) {
                    geoUri(business.name, address)?.let(onOpenUrl)
                }
                // Not clickable: the address above opens the map.
                Addresses.driveLine(address.driveMeters, address.driveSeconds)?.let { drive ->
                    Text(
                        text = drive,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp)
                            .offset(y = (-6).dp),
                    )
                }
            }
        }
```

`offset` rückt die Zeile an die Anschrift heran (`DataRow` hat unten 8 dp Innenabstand). Import `androidx.compose.foundation.layout.offset` ergänzen. Optik in Step 4 prüfen und Abstände dort nachziehen.

- [ ] **Step 3: Bauen und Tests**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Sichtprüfung**

Mit Emulator oder Gerät (Skill `run`): Betrieb mit gesetzter Fahrzeit (per `adb shell`/Testdaten) in Übersicht und Maske ansehen, Screenshot dem User zeigen. Ohne Wert: keine Leerzeile, kein Trenner am Ende.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui
git commit -m "Übersicht und Betriebsmaske zeigen Entfernung und Fahrzeit"
```

### Task 6: CHANGELOG und Release (Release nur mit Freigabe)

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Abschnitt anlegen** (oberhalb von `## 1.6.0`)

```markdown
## 1.7.0

- **Distance and driving time.** The list shows „Branche · Ort · 23 km · 21 min"
  for the main address, the detail view a line under every address. The
  values come from the server, computed once for every address with
  coordinates; an address without them shows nothing.
- **Changing an address clears its values**, as it clears the coordinates.
  An address added later has none.
- The first sync after the update fetches everything once more, so the values
  arrive on this phone.
```

- [ ] **Step 2: Tests und Commit**

```bash
./gradlew testDebugUnitTest
git add CHANGELOG.md
git commit -m "CHANGELOG 1.7.0: Entfernung und Fahrzeit"
```

- [ ] **Step 3: Freigabe einholen**, dann Release über `tools/release.sh` wie in der Übergabe beschrieben (pusht `main` + Tag, GitHub-Release, Obtainium holt es).
