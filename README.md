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


## Auf der Box installieren (USB-Stick)
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
