# OmaTV – einfache IPTV-App für die Android-Box

Box einschalten, Fernsehbild läuft. Bedienung nur mit dem Steuerkreuz der Fernbedienung,
große Schrift, keine sichtbaren Einstellungen. Wer möchte, kann aus rund 260 frei
empfangbaren deutschen Sendern seine eigene Senderliste zusammenstellen.

Ohne Internet (z. B. wenn die Box schneller startet als das WLAN) steht
**„Warte auf Internet …“** da. Sobald die Verbindung steht, holt die App die aktuellen
Listen von GitHub und das Bild startet von selbst.

Es gibt zwei Varianten aus demselben Code:

| | **OmaTV** (`omatv-X.apk`) | **OmaTV Lite** (`omatv-lite-X.apk`) |
|---|---|---|
| Startbildschirm | ersetzt den Startbildschirm der Box | normale App |
| Zurück (kurz) | zeigt den Sendernamen | fragt „Fernsehen beenden?“ |
| Zurück 3 s halten | „Fernsehen verlassen?“ → Übersicht der anderen Apps | „Fernsehen beenden?“ |

## Bedienung

**Fernsehbild**

| Taste | Wirkung |
|---|---|
| Hoch / Runter | nächster bzw. vorheriger Sender aus „Meine Sender“ |
| OK, Links oder Rechts | Liste „Meine Sender“ öffnen |
| Zurück 3 Sekunden halten | „Fernsehen verlassen?“ |
| Home | immer zurück zum Fernsehen |

**Meine Sender** – OK schaltet um. Ganz unten steht „Alle Sender …“.
Die Liste schließt sich nach 10 Sekunden ohne Tastendruck von selbst.

**Bearbeiten** – in „Meine Sender“ **OK 3 Sekunden halten**:

| Taste | Wirkung |
|---|---|
| OK | Sender anheben (grün), dann mit Hoch/Runter verschieben, OK legt ihn ab |
| Links | Sender entfernen (mit Rückfrage) |
| „＋ Sender hinzufügen …“ | öffnet „Alle Sender“ |
| „Auf Vorgabe zurücksetzen“ | wieder die Senderliste aus dem Internet (sender.m3u) |
| Zurück | fertig |

**Alle Sender** – links die Gruppen (Hauptsender, Nachrichten, Regional, Dokumentation,
Filme & Serien, Kinder, Musik, Sport, Religion, Lokal & Offene Kanäle, ganz unten
„Alle A–Z“), rechts deren Sender. Mit Rechts in die Senderliste, mit Links zurück.
OK auf einem Sender: „Ansehen“ oder „Zu ‚Meine Sender‘ hinzufügen“. Ein ✓ zeigt Sender,
die schon in „Meine Sender“ sind.

Beim Öffnen einer Gruppe prüft die Box im Hintergrund, welche Sender erreichbar sind.
Nicht erreichbare werden grau angezeigt; wer an 3 verschiedenen Tagen nicht erreichbar
war, wird ausgeblendet (und nach einer Woche erneut geprüft).

Fällt ein Sender aus, probiert der Player automatisch seine Ersatzadressen, bevor die
Meldung „nicht erreichbar“ kommt. Danach versucht er es selbst alle 5–30 Sekunden erneut.

## Die Senderlisten

| Datei | Wozu | Wer ändert sie |
|---|---|---|
| `sender.m3u` | Vorgabe für „Meine Sender“ (Reihenfolge = Programmplätze) | du, im Browser |
| `alle-sender.json` | Gesamtliste aller deutschen Sender, mit Gruppen | GitHub, jeden Morgen automatisch |
| `filter.txt` | Sender sperren (`-`) oder ausdrücklich erlauben (`+`) | du, im Browser |

**Wer hat das letzte Wort?** Solange an der Box nichts bearbeitet wurde, gilt `sender.m3u`
aus dem Repo – Änderungen kommen automatisch an (beim Einschalten, alle 3 Stunden und
beim Öffnen der Liste). Sobald an der Box etwas verschoben, hinzugefügt oder entfernt
wird, gilt die Liste der Box. „Auf Vorgabe zurücksetzen“ macht das rückgängig.
Neue Sender landen nie automatisch in „Meine Sender“, und es wird dort nie etwas
automatisch gelöscht.

**Täglicher Listenbau** (`.github/workflows/senderliste.yml`, Skript `tools/senderliste_bauen.py`):
holt alle Quellen, führt doppelte Sender zusammen (josxha hat Vorrang, die übrigen
Adressen werden Ersatzadressen), sortiert aus und entfernt eindeutig tote Streams.
Bei jeder Änderung steht in der Commit-Nachricht, welche Sender neu dazukamen oder
wegfielen. Von Hand starten: **Actions → Senderliste aktualisieren → Run workflow**.

Aussortiert werden:
- Sender aus anderen Ländern
- Streams von nackten IP-Adressen und bekannten Weiterverbreitern (unerlaubt
  weitergegebene Programme, die ständig ausfallen)
- YouTube, Twitch, Dailymotion (nicht abspielbar)
- Erwachsenen-Sender, eingestellte Sender
- Teleshopping und fremdsprachige Sender (`filter.txt`)

Die Gruppen kommen aus den Angaben der Quellen (josxha-Gruppen, iptv-org-Kategorien)
und aus Namensregeln im Skript. Was keiner Gruppe zugeordnet werden kann, landet unter
„Lokal & Offene Kanäle“ – das sind fast immer Lokalsender.

Quellen:
[josxha/german-tv-m3u](https://github.com/josxha/german-tv-m3u) ·
[Free-TV/IPTV](https://github.com/Free-TV/IPTV) ·
[iptv-org](https://github.com/iptv-org/iptv) ·
[Famelack](https://github.com/famelack/famelack-channels) ·
[FreetuxTV](https://database.freetuxtv.net)

## Einmalige Einrichtung

### 1. Signaturschlüssel (einmalig, wichtig für Updates)
```bash
sudo apt install openjdk-17-jdk-headless
keytool -genkeypair -keystore fernsehen.jks -alias fernsehen -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 fernsehen.jks > keystore.txt
```
`fernsehen.jks` und das Passwort gut aufheben. Im Repo unter
**Settings → Secrets and variables → Actions** zwei Secrets anlegen:
`KEYSTORE_BASE64` (Inhalt von keystore.txt) und `KEYSTORE_PASSWORD`.

### 2. APK bauen lassen
Jede Änderung am Code baut automatisch neu. Die APKs liegen unter
**Actions → APK bauen → (Lauf) → Summary → Artifacts**.

### 3. Auf der Box installieren (USB-Stick)
APK auf einen FAT32-Stick, mit dem Dateimanager der Box öffnen, beim ersten Mal
„unbekannte Quellen“ erlauben. Danach Home drücken, **OmaTV** und **Immer** wählen.

**Update:** Zurück 3 s halten → „Ja, andere Apps öffnen“ → Dateimanager → neue APK.
„Meine Sender“ und der zuletzt gesehene Sender bleiben erhalten.

## Technik
Kotlin, Media3/ExoPlayer (HLS, DASH, MPEG-TS), RecyclerView, Coil (Logos).
Ideen aus den Projekten
[tv_viewer](https://github.com/tv-viewer-app/tv_viewer), [Prysm](https://github.com/dereferencex/prysm)
und [SlaxxTV](https://github.com/IT-DUMMKOPFEN/SurfTV) (nur Ideen, kein Code übernommen).
