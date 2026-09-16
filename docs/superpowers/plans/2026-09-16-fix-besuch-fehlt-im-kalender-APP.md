# Fix: Ein gelöschter Besuch wird nicht als fehlend erkannt (App)

Stand 2026-09-16. Befund aus dem Handy-Test von 1.5.1. Nur App-Repo.

## Was falsch ist

Wird ein Besuch im Gerätekalender gelöscht, bleibt in der Betriebsansicht
„Im Kalender" stehen. Erwartet wäre „Im Kalender nicht mehr gefunden"
(`Appointment.kt:346`) samt „Termin entfernen" ohne Rückfrage
(`BusinessDetail.kt:828`) und dem Hinweis „Termin entfernt."
(`Appointment.removedHint`). Am Gerät mehrfach reproduziert: Betrieb
mehrfach neu geöffnet, mehrfach synchronisiert, App neu gestartet — der
Zustand ändert sich nie.

## Ursache

`Appointment.reconcile` bricht bei `event == null` **zuerst** ab, wenn kein
gespeicherter Vergleichsstand existiert: `if (seen == null) return NotYetHere`
(`Appointment.kt:580`). `MissingVisit` (`:584`) ist ohne diesen Stand
unerreichbar.

Ein Besuch bekommt diesen Stand aber praktisch nie:

- `saveVisit` (`CallsheetViewModel.kt:1445`) legt kein Event an, setzt keinen
  Link und ruft den Abgleich gar nicht auf — das Event ist das des Servers.
- Der Kalender-Nachlauf überspringt Besuche ausdrücklich (`:1699`, `:1712`).
- `captureMissingUids` filtert auf Rückrufe (`:1740`).
- Bleibt einzig der Zweig `InStep` (`:1565`), also: Der Nutzer öffnet die
  Betriebsansicht **zufällig** in dem Fenster, in dem DAVx5 das Event schon
  gebracht hat, und Titel, Zeit und Ort stimmen exakt überein (`sameSlot`,
  `Appointment.kt:615`).

Dazu eine Sackgasse: Weicht das Event bei der **ersten** Sichtung ab, ist das
Ergebnis `NotYetHere`, es wird nichts gemerkt — und weil `TakeEvent` für einen
Besuch seinerseits `seen != null` verlangt (`:591`), kann der Stand **nie mehr**
nachgeholt werden. Dieser Besuch kann sein Fehlen dauerhaft nicht bemerken.

Warum die Tests das nicht gefangen haben: Sie übergeben den Vergleichsstand als
Vorbedingung (`AppointmentTest.kt:563`) und prüfen damit nur die
Entscheidungstabelle — nie, ob dieser Zustand auf einem Gerät entstehen kann.
Für `reconcileAppointments` und `reconcile` der ViewModel gibt es überhaupt
keine Tests.

## Was sich ändert

Ein Besuch braucht den Vergleichsstand nicht mehr, um sein Fehlen zu bemerken.
Statt dessen muss **belegt** sein, dass dieses Gerät den Kalender überhaupt
bekommt — und dass es ihn **nach** der Bestätigung dieses Besuchs bekommen hat.

Das ist die Lehre aus der Plan-Prüfung: „Nicht auffindbar" allein darf nie
„fehlt" heißen. Auf einem Gerät ohne den geteilten Kalender (zweites Gerät,
Konto entfernt, Kalender in DAVx5 abgewählt) findet die Suche **nie** etwas.
Eine reine Zeitregel würde dort jeden Besuch als verloren melden — und weil
„Termin entfernen" in diesem Zustand ohne Rückfrage durchläuft
(`BusinessDetail.kt:828`), ginge mit einem Fehlgriff der Termin samt Event und
samt Absagen an die Eingeladenen verloren. Ebenso beim frisch angelegten
Besuch: DAVx5 synchronisiert je nach Einstellung nur alle paar Stunden, eine
Karenz von Minuten belegt gar nichts.

### Der Beleg

Gesucht ist ein Nachweis, dass DAVx5 **nach** der Bestätigung dieses Besuchs
geliefert hat. Der Zeitpunkt, zu dem die App irgendein Event **gefunden** hat,
taugt dafür nicht: Ein Event, das schon seit Tagen auf dem Gerät liegt, wird
jederzeit gefunden, ohne dass DAVx5 seither gelaufen wäre.

Deshalb zählt nicht, **wann** gefunden wurde, sondern **welches** Event
gefunden wurde. Die App merkt sich geräteweit die größte
**Server-Bestätigungszeit** unter allen Besuchs-Events, die sie im Kalender
gefunden hat. Wird ein Event gefunden, das **später** bestätigt wurde als der
fragliche Besuch, dann muss DAVx5 nach dessen Bestätigung geliefert haben —
sonst wäre das jüngere Event nicht da. Genau das ist der Beweis.

Diese Bestätigungszeit gibt es auf dem Gerät noch nicht: `calendar_state` gehört
dem Server und wird beim Übernehmen geschrieben, **ohne** `updated_at` zu
bewegen (`Rows.kt:61`, `SERVER_OWNED`). Sie muss also lokal entstehen — als
Zeitpunkt, zu dem dieses Gerät den Zustand `ok` für diesen Besuch **zuerst
gesehen** hat, gesetzt beim Übernehmen im Sync.

Regel für einen Besuch mit Server-Kennung, Status OK und nicht schmutzig
(`readsBack`, `Appointment.kt:319`), der in der **Zukunft** liegt und dessen
Event auf dem Gerät nicht auffindbar ist:

1. Erste vergebliche Suche: Zeitpunkt am Termin merken, Ergebnis bleibt
   „noch nicht da". Anzeige unverändert.
2. `MissingVisit` erst, wenn **alle** dieser Punkte zutreffen:
   - es gab schon eine frühere vergebliche Suche, und sie liegt mindestens die
     Karenzzeit zurück;
   - dieses Gerät hat schon ein Besuchs-Event im Kalender gefunden, das
     **später bestätigt** wurde als dieser Besuch.
3. Event gefunden: der gemerkte Zeitpunkt am Termin wird gelöscht, und der
   geräteweite Beleg wird auf die Bestätigungszeit dieses Besuchs angehoben,
   falls sie größer ist.

Fehlt der geräteweite Beleg, bleibt es dauerhaft bei „noch nicht da". Das ist
gewollt: lieber eine Löschung unbemerkt als ein Termin, den die App fälschlich
zum Löschen anbietet.

### Rückfrage im neuen Pfad

Wurde das Fehlen **nur** über den neuen Weg erkannt (ohne Vergleichsstand),
fragt „Termin entfernen" vor dem Löschen nach — auch ohne Teilnehmende. Der
alte Weg über den Vergleichsstand bleibt wie in Paket 1 festgelegt ohne
Rückfrage.

Grund: Das Entfernen löscht das Event bei Infomaniak und verschickt Absagen.
Der Beleg macht einen Fehlalarm unwahrscheinlich, aber die Folgen eines
Fehlgriffs sind nicht zurückzuholen, und der neue Weg ist der weniger
erprobte. Ein Tipp mehr ist der Preis.

Das bedeutet: `Appointment.removalAsks` (`:378`) braucht die Information,
**woher** die Erkennung kam — der Aufruf in `BusinessDetail.kt:828` gibt heute
nur `missing = true` weiter. Entsprechend muss `detailMissingInCalendar` im
ViewModel (`:1665`) die beiden Fälle unterscheiden, nicht nur die Kennungen
sammeln. Für den Fall mit Rückfrage entfällt der Hinweis „Termin entfernt.",
weil der Dialog es bereits gesagt hat (`CallsheetViewModel.kt:1780`).

Karenzzeit als benannte Konstante. Sie ist mit dem Beleg aus Punkt 2 nur noch
zweite Sicherung, nicht mehr tragendes Kriterium — 30 Minuten genügen.

Der bisherige Weg über den Vergleichsstand bleibt, wie er ist: Wer ihn hat,
bekommt `MissingVisit` sofort. Die neue Regel ergänzt nur den Fall ohne Stand.

### Umsetzung

- **Zwei** neue lokale Spalten an `appointments`, beide Millis und nullable:
  - `calendar_missing_since` — wann zuerst vergeblich gesucht wurde;
  - `calendar_ok_since` — wann dieses Gerät den Zustand `ok` für diesen Besuch
    zuerst gesehen hat. Gesetzt beim Übernehmen im Sync, dort wo die
    server-eigenen Spalten geschrieben werden; einmal gesetzt bleibt der Wert
    stehen, bis der Zustand `ok` verlässt.

  Drei Randfälle, die dabei zu beachten sind:

  - **Leer heißt unbekannt, nicht „vor allem anderen".** Nach der Migration
    steht `calendar_ok_since` bei allen bestehenden Besuchen leer. Leer muss
    „nie fehlend" bedeuten und darf als Beleg nichts zählen. Keinesfalls als 0
    lesen — sonst gälte jeder bestehende Besuch sofort als belegt.
  - **Der Vergleich ist streng „später", nicht „gleich oder später".** Bei einer
    Neuinstallation bekommen alle Besuche im ersten vollständigen Abgleich
    denselben Zeitpunkt. Ein gleichzeitig bestätigtes Event belegt nichts.
  - **Zurücksetzen auf leer**, wenn der Zustand `ok` verlässt oder die
    Server-Kennung wechselt. Kommt `ok` später wieder, gilt ein neuer
    Zeitpunkt — wie bei `calendar_missing_since`.

  Beide gehören in `Rows.LOCAL_ONLY` (`sync/Rows.kt:49`), sonst gehen sie mit
  hoch. Schemaversion steigt von 10 auf 11: `onCreate` und ein Upgrade-Zweig
  `old < 11` in `Database.kt`.
- Der geräteweite Beleg gehört nicht an einen Termin, sondern in `Preferences`
  (eine Zahl, Millis): die größte `calendar_ok_since` unter allen bisher
  gefundenen Besuchs-Events.
- Der Schreibpfad darf weder `updated_at` noch `dirty` anfassen — Muster:
  `setCalendarLink` (`Repository.kt:1033`).
- `Appointment.reconcile` bleibt rein und bekommt die nötigen Werte als
  Parameter. Es gibt **Entscheidung und neuen Zeitpunkt gemeinsam** zurück
  (etwa `Pair<Reconcile, Long?>`), damit das Fortschreiben selbst prüfbar wird
  und nicht nur im ViewModel passiert.
- `CallsheetViewModel.reconcile` schreibt und löscht, analog zu `rememberSeen`
  (`:976`) — und zwar **nach** dem `rowWinsOnly`-Ausstieg (`:1550`), damit die
  Zählung nur im Pfad `rowWinsOnly = false` läuft.

### Zurücksetzen nicht vergessen

`calendar_missing_since` muss gelöscht werden, sobald

- `readsBack` nicht mehr gilt (schmutzig, wartend, Fehler),
- die Server-Kennung des Termins wechselt — nach einem Entfernen und
  Wiederkehren legt der Server ein **neues** Event mit neuer Kennung an,
- der Termin in die Vergangenheit rutscht.

Ohne das löst ein alter Zeitstempel beim ersten Fehlschlag sofort
`MissingVisit` aus.

### Ausdrücklich nicht Teil dieser Änderung

- Den Vergleichsstand für Besuche zuverlässig zu setzen. Vom Nutzer als
  Richtung verworfen.
- Die **Sackgasse bleibt für verschobene Termine bestehen**: Weicht das Event
  von der Zeile ab, ergibt der Abgleich weiterhin dauerhaft „noch nicht da".
  Dieser Plan behebt nur das **Fehlen**, nicht das Abweichen. Eigener Punkt.

## Tests zuerst

In `AppointmentTest.kt` als **Abfolge** statt Einzelaufruf — genau das ist die
Lücke, die den Fehler durchgelassen hat. Weil `reconcile` Entscheidung und
neuen Zeitpunkt gemeinsam zurückgibt, wird der gemerkte Wert im Test
**fortgeschrieben** statt vorgesetzt:

1. Frischer Besuch, nie gesehen, Event noch nicht da → „noch nicht da",
   Zeitpunkt wird gemerkt, **kein** `MissingVisit`.
2. Gleiche Lage, zweiter Aufruf **innerhalb** der Karenzzeit → weiterhin
   „noch nicht da".
3. Gleiche Lage, nach der Karenzzeit, **ohne** geräteweiten Beleg → weiterhin
   „noch nicht da". Das ist der Schutz für Geräte ohne den Kalender.
4. Gleiche Lage, nach der Karenzzeit, Beleg **älter** als die Bestätigung des
   Events → weiterhin „noch nicht da". Schutz für den frisch angelegten Besuch,
   den DAVx5 noch nicht gebracht hat.
5. Gleiche Lage, nach der Karenzzeit, Beleg **neuer** als die Bestätigung →
   `MissingVisit`. Dieser Test scheitert heute.
5a. Der Fall, an dem die erste Fassung der Regel scheiterte: Besuch A liegt
   seit Tagen im Kalender, Besuch B wird angelegt und um 10:01 bestätigt. Um
   10:02 erste vergebliche Suche nach B. Um 10:30 wird A geöffnet und gefunden
   — A wurde aber **vor** B bestätigt, der Beleg steigt also nicht über 10:01.
   Um 10:40 erneut B → weiterhin „noch nicht da", obwohl Karenz und ein Fund
   dazwischenliegen. DAVx5 ist nie gelaufen, und die Regel merkt es.
6. Sackgassen-Fall: Erste Sichtung mit abweichendem Ort, danach Event gelöscht,
   Beleg vorhanden → `MissingVisit`.
7. Event taucht zwischendurch auf → gemerkter Zeitpunkt gelöscht, geräteweiter
   Beleg auf jetzt, Zählung beginnt von vorn.
8. Besuch in der Vergangenheit: keine Zählung, kein `MissingVisit`, gemerkter
   Zeitpunkt wird geleert (`Appointment.kt:582`).
9. Rückrufe verhalten sich unverändert — die Zählung greift nur bei
   `visit = true`.
10. `removalAsks` fragt nach, wenn das Fehlen über den neuen Weg erkannt wurde,
    und fragt nicht, wenn es über den Vergleichsstand erkannt wurde — beides
    jeweils mit und ohne Teilnehmende.

Dazu mit Repository und Datenbank im Speicher, weil die reine Funktion allein
den Fehler nicht gefangen hätte:

- Das ViewModel schreibt den Zeitpunkt wirklich und liest ihn wieder.
- Die neuen Spalten überleben einen Abgleich mit dem Server und gehen nicht mit
  hoch (`Rows.LOCAL_ONLY`).
- `calendar_ok_since` wird beim Übernehmen gesetzt, sobald der Zustand `ok`
  ankommt, und bleibt danach stehen, solange er `ok` bleibt.
- Ein Besuch mit leerem `calendar_ok_since` — etwa direkt nach der Migration —
  wird nie als fehlend gemeldet und taugt auch nicht als Beleg für andere.
- Zwei Besuche mit **demselben** `calendar_ok_since`, wie nach einer
  Neuinstallation, belegen einander nicht.
- `calendar_ok_since` wird geleert, wenn der Zustand `ok` verlässt oder die
  Server-Kennung wechselt.
- `calendar_missing_since` wird geleert, wenn die Server-Kennung wechselt oder
  der Termin bearbeitet wird.
- Das Schreiben rührt weder `updated_at` noch `dirty` an.

## Prüfung am Gerät (nach dem Einbau)

1. Besuch anlegen → es erscheint **kein** falscher Hinweis, solange DAVx5 ihn
   noch nicht gebracht hat. Auch nicht nach längerem Warten und mehrfachem
   Öffnen, solange DAVx5 nicht gelaufen ist.
2. In DAVx5 synchronisieren, warten, bis er im Gerätekalender steht.
3. Betriebsansicht öffnen, solange der Termin im Kalender ist — damit der
   geräteweite Beleg gesetzt wird.
4. Im Gerätekalender löschen, in DAVx5 synchronisieren.
5. Betriebsansicht öffnen → beim ersten Mal darf noch „Im Kalender" stehen.
6. Nach der Karenzzeit erneut öffnen → „Im Kalender nicht mehr gefunden".
7. „Termin entfernen" → **Rückfrage**, weil das Fehlen über den neuen Weg
   erkannt wurde. Nach dem Bestätigen ist der Termin weg.

Der Hinweis „Termin entfernt.", der im Test von 1.5.1 ausblieb, gehört nur zum
alten Weg ohne Rückfrage. Dass er damals fehlte, lag daran, dass dieser Pfad nie
erreicht wurde; ein eigener Fehler ist dort nach jetzigem Stand nicht zu
erwarten. Um ihn zu prüfen, braucht es einen Besuch mit Vergleichsstand — also
Schritt 3 mit passendem Titel, Zeit und Ort.

Gegenprobe auf den Blocker, unbedingt mitprüfen: In DAVx5 den Kalender
abwählen oder das Konto entfernen, dann mehrere Betriebe mit Besuchen öffnen.
Es darf **nirgends** „Im Kalender nicht mehr gefunden" erscheinen.

## Getrennter Punkt: Abgleich-Knopf in der Hauptansicht

Eigener Commit, nicht mit dem Fehler oben vermischen.

„Jetzt abgleichen" gibt es bisher nur in den Einstellungen (`Settings.kt:227`),
dazu den automatischen Abgleich beim Öffnen der App (`MainActivity.kt:229`). In
der Hauptansicht fehlt ein Auslöser.

Wichtig für die Erwartung: Der Knopf stößt den **Server**-Abgleich an
(`vm.syncNow(quiet = false)`), **nicht** DAVx5. Der Gerätekalender aktualisiert
sich dadurch nicht. Die Beschriftung muss das klarmachen, sonst wartet der
Nutzer auf etwas, das nicht passiert. DAVx5 mitanzustoßen wäre ein eigenes
Thema mit Kontoberechtigungen und ist hier nicht vorgesehen.

## Kleiner Punkt aus dem Test: Zeitstempel beim Import

Nach einem Import steht „neu 0, aktualisiert 0" ohne Datum und Uhrzeit. Damit
ist nicht zu unterscheiden, ob gerade ein Import lief oder ein altes Ergebnis zu
sehen ist. Im Test hat das mehrfach für Unsicherheit gesorgt. Ein Zeitstempel
wie in den Einstellungen („Zuletzt abgeglichen", `Settings.kt:166`) genügt.
Eigener Commit.
