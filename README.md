# Fernsehen – einfache IPTV-App für die Android-Box

Eine bewusst schlichte Fernseh-App: Box einschalten, Fernsehbild läuft. Bedienung nur mit dem Steuerkreuz.

## Bedienung

| Taste | Wirkung |
|---|---|
| Hoch / Runter | nächster bzw. vorheriger Sender (nach dem letzten kommt wieder Platz 1) |
| OK, Links oder Rechts | Senderliste öffnen, mit OK umschalten |
| Zurück (kurz) | Liste schließen bzw. Sendername anzeigen |
| Zurück **3 Sekunden halten** | Abfrage „Fernsehen verlassen?“ → Übersicht der anderen Apps |
| Home | immer zurück zum Fernsehen |

Die Liste schließt sich nach 10 Sekunden ohne Tastendruck von selbst. Fällt ein Sender aus, versucht die App es automatisch alle 5–30 Sekunden erneut.

## Einmalige Einrichtung

### 1. GitHub-Repository anlegen
1. Konto auf https://github.com anlegen (kostenlos).
2. Oben rechts **+ → New repository**. Name z. B. `fernsehen`, Sichtbarkeit **Public** (die Senderliste muss öffentlich abrufbar sein; Geheimes steht darin nicht).
3. Auf der leeren Repo-Seite **„uploading an existing file“** wählen und den *Inhalt* dieses Ordners hineinziehen, dann **Commit changes**.

> Der Ordner `.github` ist unter Linux versteckt. Im Dateimanager mit **Strg+H** einblenden, damit er mit hochgeladen wird. Falls er trotzdem fehlt: im Repo **Add file → Create new file**, als Namen `.github/workflows/build.yml` eintippen und den Inhalt der Datei hineinkopieren.

### 2. Signaturschlüssel erzeugen (einmalig, wichtig für Updates)
Unter Linux Mint im Terminal:
```bash
sudo apt install openjdk-17-jdk-headless
keytool -genkeypair -keystore fernsehen.jks -alias fernsehen -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 fernsehen.jks > keystore.txt
```
Bei `keytool` ein Passwort vergeben (mindestens 6 Zeichen) und die Fragen beliebig beantworten.
**`fernsehen.jks` und das Passwort gut aufheben** (z. B. auf einem zweiten Stick). Ohne diesen Schlüssel lassen sich später keine Updates mehr drüberinstallieren.

### 3. Schlüssel bei GitHub hinterlegen
Im Repo: **Settings → Secrets and variables → Actions → New repository secret**
- `KEYSTORE_BASE64` – kompletter Inhalt von `keystore.txt`
- `KEYSTORE_PASSWORD` – das Passwort von oben

### 4. APK bauen lassen
Im Repo auf **Actions → APK bauen → Run workflow**. Nach ein paar Minuten erscheint ein grüner Haken. Unten auf der Seite unter **Artifacts** die Datei herunterladen und das ZIP entpacken: darin liegt `fernsehen-<Nummer>.apk`.
Danach wird bei jeder Änderung am Code automatisch neu gebaut.

### 5. Auf der Box installieren (USB-Stick)
1. APK auf einen FAT32-Stick kopieren.
2. Stick an die Box, mit dem Dateimanager der Box die APK öffnen.
3. Beim ersten Mal „Installation aus unbekannten Quellen“ für den Dateimanager erlauben.
4. Nach der Installation die **Home-Taste** drücken, **Fernsehen** wählen und **Immer**.

**Update später:** Zurück-Taste 3 s halten → „Ja, andere Apps öffnen“ → Dateimanager → neue APK öffnen. Einstellungen und letzter Sender bleiben erhalten.

## Senderliste ändern (aus der Ferne)

Die Datei `sender.m3u` im Repo ist die Senderliste. Reihenfolge = Programmplätze.
Im Browser auf die Datei klicken → Stift-Symbol → ändern → **Commit changes**. Beim nächsten Einschalten der Box (bzw. Rückkehr ins Fernsehen) lädt die App die neue Liste; GitHub braucht dafür manchmal bis zu 5 Minuten.

Ein Eintrag besteht aus zwei Zeilen:
```
#EXTINF:-1 tvg-logo="https://…/logo.png",Name in der Liste
https://adresse-des-streams/master.m3u8
```
Neue Adressen findest du in der Quelle https://github.com/josxha/german-tv-m3u. Funktioniert ein Sender nicht mehr, dort nachsehen, ob sich die Adresse geändert hat.

Ist kein Internet da, nutzt die App die zuletzt geladene Liste bzw. die eingebaute Liste.

## Technik
Kotlin, Media3/ExoPlayer (HLS), RecyclerView, Coil für Senderlogos. Ideen u. a. aus den MIT-lizenzierten Projekten
[tv_viewer](https://github.com/tv-viewer-app/tv_viewer) und [Prysm](https://github.com/dereferencex/prysm).
