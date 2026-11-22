# OmaTV – einfache IPTV-App für die Android-Box

Box einschalten, Fernsehbild läuft. Bedienung nur mit dem Steuerkreuz der Fernbedienung,
große Schrift, keine sichtbaren Einstellungen. Die Programmplätze steuerst du aus der
Ferne über die Datei `sender.m3u` in diesem Repo.

**Ablauf beim Start von OmaTV**
1. Kein Internet? → „Warte auf Internet …“ (z. B. wenn die Box schneller startet als das WLAN)
2. „Senderliste wird geladen …“ → `sender.m3u` wird frisch von GitHub geholt.
   Ist GitHub nicht erreichbar, nimmt OmaTV die zuletzt geladene Kopie,
   beim allerersten Start die Notfall-Kopie, die beim Bau in die App gepackt wurde.
3. Erst dann startet das Bild – mit dem zuletzt gesehenen Sender.

Es gibt zwei Varianten aus demselben Code:

| | **OmaTV** (`OmaTV-X.apk`) | **OmaTV Lite** (`OmaTV-Lite-X.apk`) |
|---|---|---|
| Startbildschirm | ersetzt den Startbildschirm der Box | normale App |
| Zurück (kurz) | zeigt den Sendernamen | fragt „OmaTV verlassen?“ |
| Zurück 3 s halten | „OmaTV verlassen?“ → Übersicht der anderen Apps | „OmaTV verlassen?“ |

## Bedienung

**Fernsehbild**

| Taste | Wirkung |
|---|---|
| Hoch / Runter | nächster bzw. vorheriger Sender aus „Meine Sender“ |
| OK, Links oder Rechts | Liste „Meine Sender“ öffnen |
| Zurück 3 Sekunden halten | „OmaTV verlassen?“ |
| Home | immer zurück zu OmaTV |

**Meine Sender** – genau die Sender aus `sender.m3u`, in derselben Reihenfolge.
OK schaltet um. Die Liste schließt sich nach 10 Sekunden ohne Tastendruck von selbst.
An der Box lässt sich die Liste nicht ändern – das machst du über GitHub.

**Alle Sender** – ganz unten in „Meine Sender“ steht „Alle Sender …“.
Geöffnet wird die Gesamtliste nur mit **OK 3 Sekunden halten**, damit niemand
versehentlich hineingerät. Links die Gruppen (Hauptsender, Nachrichten, Regional,
Dokumentation, Filme & Serien, Kinder, Musik, Sport, Religion, Lokal & Offene Kanäle,
ganz unten „Alle A–Z“), rechts deren Sender. OK schaltet auf den Sender um;
Hoch/Runter führt danach wieder zurück zu „Meine Sender“. Ein ✓ zeigt Sender,
die schon in „Meine Sender“ sind.

Beim Öffnen einer Gruppe prüft die Box im Hintergrund, welche Sender erreichbar sind.
Nicht erreichbare werden grau angezeigt; wer an 3 verschiedenen Tagen nicht erreichbar
war, wird ausgeblendet (und nach einer Woche erneut geprüft).

**Ausfälle und Puffer** – OmaTV lädt 3 Sekunden vor, bevor das Bild startet (nach
einem Stocker 5 Sekunden), und hält im laufenden Betrieb bis zu 50 Sekunden im Voraus.
Fällt ein Sender aus, probiert der Player automatisch seine Ersatzadressen aus der
Gesamtliste, danach versucht er es selbst alle 5–30 Sekunden erneut. Bricht das Internet
weg, steht „Warte auf Internet …“ da und das Bild kommt von selbst zurück.

## Die Senderlisten

| Datei | Wozu | Wer ändert sie |
|---|---|---|
| `sender.m3u` | „Meine Sender“ (Reihenfolge = Programmplätze) | du, im Browser |
| `alle-sender.json` | Gesamtliste aller deutschen Sender, mit Gruppen | GitHub, jeden Morgen automatisch |
| `filter.txt` | Sender sperren (`-`) oder ausdrücklich erlauben (`+`) | du, im Browser |

**`sender.m3u` hat immer das letzte Wort.** OmaTV holt sie bei jedem Start (auch beim
Zurückkehren mit der Home-Taste), alle 3 Stunden und beim Öffnen der Liste, wenn das
letzte Mal länger als 15 Minuten her ist. Änderungen sind ohne Verzögerung da.
Für jeden Sender daraus probiert der Player zuerst deine Adresse; die Gesamtliste
liefert nur Ersatzadressen, falls deine Adresse ausfällt.

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
Jede Änderung am Code baut automatisch neu. Die APKs (`OmaTV-X.apk`, `OmaTV-Lite-X.apk`)
liegen unter **Actions → APK bauen → (Lauf) → Summary → Artifacts**.

### 3. Auf der Box installieren (USB-Stick)
APK auf einen FAT32-Stick, mit dem Dateimanager der Box öffnen, beim ersten Mal
„unbekannte Quellen“ erlauben. Danach Home drücken, **OmaTV** und **Immer** wählen.

**Update:** Zurück 3 s halten → „Ja, andere Apps öffnen“ → Dateimanager → neue APK.
Der zuletzt gesehene Sender bleibt erhalten.

## Technik
Kotlin, Media3/ExoPlayer (HLS, DASH, MPEG-TS), RecyclerView, Coil (Logos).
Ideen aus den Projekten
[tv_viewer](https://github.com/tv-viewer-app/tv_viewer), [Prysm](https://github.com/dereferencex/prysm)
und [SlaxxTV](https://github.com/IT-DUMMKOPFEN/SurfTV) (nur Ideen, kein Code übernommen).
