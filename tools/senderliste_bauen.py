#!/usr/bin/env python3
"""
Baut die Gesamtliste aller deutschen Sender (alle-sender.json) für OmaTV.

Quellen (Reihenfolge = Vorrang bei doppelten Sendern):
  1. josxha/german-tv-m3u   (gepflegte deutsche Liste)
  2. Free-TV/IPTV
  3. iptv-org               (über die öffentliche API)
  4. Famelack (ex tv.garden)
  5. FreetuxTV-Datenbank

Aussortiert werden:
  - Streams von nackten IP-Adressen und von bekannten Weiterverbreitern
    (unerlaubte Weitergabe fremder Programme, fallen ständig aus)
  - YouTube/Twitch/Dailymotion (nicht abspielbar), Nicht-HTTP-Adressen
  - Erwachsenen-Sender, eingestellte Sender, iptv-org-Sperrliste
  - Teleshopping
  - fremdsprachige Sender
  - Sender aus anderen Ländern
  - alles, was in filter.txt mit "-" gesperrt ist

Jeder Sender bekommt eine Gruppe (Hauptsender, Nachrichten, Regional, …),
nach der die App die Liste "Alle Sender" gliedert.

Aufruf:
  python3 tools/senderliste_bauen.py            # baut alle-sender.json
  python3 tools/senderliste_bauen.py --pruefen  # zusätzlich eindeutig tote Streams entfernen
"""
import concurrent.futures as cf
import datetime
import html
import ipaddress
import json
import os
import pathlib
import re
import socket
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "alle-sender.json"
FILTER = ROOT / "filter.txt"

RAW = "https://raw.githubusercontent.com"
JOSXHA = f"{RAW}/josxha/german-tv-m3u/main/german-tv.m3u"
FREE_TV = f"{RAW}/Free-TV/IPTV/master/playlist.m3u8"
IPTV_ORG = f"{RAW}/iptv-org/api/gh-pages"
FAMELACK = f"{RAW}/famelack/famelack-channels/main/tv/categories/all.json"
FREETUX = ("https://database.freetuxtv.net/WebStream/index"
           "?WebStreamSearchForm%5BType%5D=1&WebStreamSearchForm%5BStatus%5D=2")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

# Server, die fremde Programme ohne Erlaubnis weiterverbreiten
BAD_HOSTS = ("antik.sk", "hopslan.com", "anixa.tv", "iptvnetwork.net", "cdn-redge.media",
             "iptv-24.net", "iptvperu.tv", "stmify.com", "mangeinde4.lat", "vip-acasatv.online",
             "team-tv.co", "thestream.cyou", "iptv2022.com", "wonvt.st", "bozztv.com",
             "mcquack.net", "protokolldns.xyz", "tvorb.net", "dyndns.org",
             # Gratis-Hoster, über die fremde Streams "durchgeschleust" werden
             "workers.dev", "onrender.com", "vercel.app", "github.io")
BAD_TLDS = (".cfd", ".lat", ".cyou", ".xyz")
UNPLAYABLE = ("youtube.com", "youtu.be", "youtube-nocookie.com", "twitch.tv", "dailymotion.com")
SHOP_RE = re.compile(r"\b(shop|shopping|teleshop)", re.I)
MAX_URLS = 4

GROUPS = ["Hauptsender", "Nachrichten", "Regional", "Dokumentation", "Filme & Serien",
          "Kinder", "Musik", "Sport", "Religion", "Lokal & Offene Kanäle", "Weitere Sender"]

JOSXHA_GROUPS = {"vollprogramm": "Hauptsender", "spartenprogramm": "Hauptsender",
                 "nachrichten": "Nachrichten", "kinder / jugend": "Kinder", "religion": "Religion",
                 "regional": "Regional", "lokal": "Lokal & Offene Kanäle"}

CATEGORY_GROUPS = [  # iptv-org-Kategorien, erste passende gewinnt
    ({"news", "legislative", "business"}, "Nachrichten"),
    ({"kids"}, "Kinder"),
    ({"music"}, "Musik"),
    ({"sports", "auto"}, "Sport"),
    ({"religious"}, "Religion"),
    ({"documentary", "education", "culture", "science", "travel", "outdoor"}, "Dokumentation"),
    ({"movies", "series", "comedy", "entertainment", "classic", "animation", "family"}, "Filme & Serien"),
]

REGIONAL_RE = re.compile(r"^(NDR|WDR|MDR|BR|SWR|SR|hr|rbb|Radio Bremen)\b", re.I)
LOCAL_RE = re.compile(r"^OK\s?\d|^OK\b|\bOK-TV\b|Offener Kanal", re.I)
MUSIC_RE = re.compile(r"music|musik|deluxe|schlager|vevo|xite|antenne|\bhits\b|\bfm\b|radio|rock", re.I)
RELIGION_RE = re.compile(r"bibel|jerusalem|salve|ewtn|hope channel|k-tv|gospel|kirche|gott", re.I)
ADULT_RE = re.compile(r"adult|south park", re.I)
# Gratis-Kanäle mit Werbung (Pluto TV, Samsung TV Plus, Rakuten, Amagi …)
FAST_HOSTS = ("jmp2.uk", "pluto.tv", "amagi.tv", "rakuten", "samsung", "wurl.com", "mediatailor")


def group_for(entry):
    name = entry.get("name") or ""
    if entry.get("group"):
        return entry["group"]
    if REGIONAL_RE.search(name):
        return "Regional"
    if LOCAL_RE.search(name):
        return "Lokal & Offene Kanäle"
    cats = set(entry.get("cats") or [])
    for keys, group in CATEGORY_GROUPS:
        if cats & keys:
            if group == "Kinder" and ADULT_RE.search(name):
                return "Filme & Serien"
            return group
    if RELIGION_RE.search(name):
        return "Religion"
    if MUSIC_RE.search(name):
        return "Musik"
    # iptv-org: Sendegebiet ist ein Bundesland oder eine Stadt -> Lokalsender
    if any(a.startswith(("s/", "ct/")) for a in (entry.get("area") or [])):
        return "Lokal & Offene Kanäle"
    host = host_of(entry.get("url") or "")
    if name.lower().startswith(("pluto tv", "rakuten tv")) or any(h in host for h in FAST_HOSTS):
        return "Filme & Serien"
    return None


STOP_WORDS = {"hd", "fhd", "uhd", "4k", "sd", "tv", "fernsehen", "television",
              "deutschland", "germany", "german", "de", "live", "channel"}


# ---------------------------------------------------------------- Hilfen

def log(*a):
    print(*a, flush=True)


def get(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def get_json(url):
    return json.loads(get(url))


def norm_name(name):
    s = clean_display_name(name)
    s = re.sub(r"\[.*?\]", " ", s).replace("ß", "ss")
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower()
    s = re.sub(r"([aou])e(?=[^aeiouy]|$)", r"\1", s)   # Sued -> Sud, Moenchen -> Monchen
    words = [w for w in re.split(r"[^a-z0-9]+", s) if w and w not in STOP_WORDS]
    return "".join(words) or re.sub(r"[^a-z0-9]+", "", s)


def clean_display_name(name):
    name = re.sub(r"[ⓎⓉⒹⓈⒼ]", "", name or "")
    name = re.sub(r"\s*\((\d{3,4}[pi]|SD|HD)\)\s*", " ", name)
    name = re.sub(r"\s*\[(Geo-blocked|Not 24/7)\]\s*", " ", name)
    return re.sub(r"\s+", " ", name).strip()


def country_code(cc):
    cc = (cc or "").strip().upper()
    if cc == "UK":
        cc = "GB"
    return cc if re.fullmatch(r"[A-Z]{2}", cc) else "XX"


def host_of(url):
    try:
        return (urllib.parse.urlparse(url).hostname or "").lower()
    except ValueError:
        return ""


def is_ip(host):
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return False


def m3u_entries(text):
    """Liefert (attribute, anzeigename, url) aus einer M3U-Datei."""
    attr_re = re.compile(r'([\w-]+)="([^"]*)"')
    info = None
    for raw in text.splitlines():
        line = raw.strip()
        if line.upper().startswith("#EXTINF"):
            info = line
            continue
        if not line or line.startswith("#") or info is None:
            continue
        attrs = {k.lower(): v for k, v in attr_re.findall(info)}
        rest = attr_re.sub("", info)
        name = rest.split(",", 1)[1].strip() if "," in rest else ""
        yield attrs, name, line
        info = None


# ---------------------------------------------------------------- Filter

class Filter:
    """Regeln aus filter.txt. Enthält eine Regel einen Punkt oder Schrägstrich, gilt sie
    für die Stream-Adresse, sonst für den Sendernamen (passend ab Wortanfang)."""

    def __init__(self, path):
        self.block, self.allow = [], []
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line[:1] in "-+" and line[1:].strip():
                    (self.block if line[0] == "-" else self.allow).append(self._rule(line[1:].strip()))

    @staticmethod
    def _rule(text):
        if "." in text or "/" in text:
            return ("url", text.lower())
        return ("name", re.compile(r"(?<![\w])" + re.escape(text), re.I))

    @staticmethod
    def _hit(rule, name, url):
        kind, pattern = rule
        return pattern in url.lower() if kind == "url" else bool(pattern.search(name))

    def allowed(self, name, url):
        if any(self._hit(r, name, url) for r in self.allow):
            return True
        return not any(self._hit(r, name, url) for r in self.block)


def technically_ok(url):
    low = url.lower()
    if not (low.startswith("http://") or low.startswith("https://")):
        return False
    host = host_of(url)
    if not host or is_ip(host):
        return False
    if any(host == h or host.endswith("." + h) for h in BAD_HOSTS) or host.endswith(BAD_TLDS):
        return False
    if any(host == h or host.endswith("." + h) for h in UNPLAYABLE):
        return False
    return True


# ---------------------------------------------------------------- Quellen
# Jede Quelle liefert Einträge: dict(name, country, url, logo, id, ref, ua, langs)

def src_josxha():
    out = []
    for attrs, name, url in m3u_entries(get(JOSXHA)):
        first_group = (attrs.get("group-title") or "").split(";")[0].strip().lower()
        out.append(dict(name=clean_display_name(attrs.get("tvg-name") or name),
                        country="DE", url=url, logo=attrs.get("tvg-logo") or None,
                        id=attrs.get("tvg-id") or None, langs=None,
                        group=JOSXHA_GROUPS.get(first_group, "Weitere Sender")))
    return out


def src_free_tv():
    out = []
    for attrs, name, url in m3u_entries(get(FREE_TV)):
        if "Ⓨ" in name:
            continue
        out.append(dict(name=clean_display_name(name) or attrs.get("tvg-name"),
                        country=country_code(attrs.get("tvg-country")), url=url,
                        logo=attrs.get("tvg-logo") or None,
                        id=attrs.get("tvg-id") or None, langs=None))
    return out


def src_iptv_org():
    channels = {c["id"]: c for c in get_json(f"{IPTV_ORG}/channels.json")}
    blocked = {b["channel"] for b in get_json(f"{IPTV_ORG}/blocklist.json")}
    feeds = {}
    for f in get_json(f"{IPTV_ORG}/feeds.json"):
        feeds[(f["channel"], f["id"])] = f
        if f.get("is_main"):
            feeds[(f["channel"], None)] = f
    logos = {}
    for l in get_json(f"{IPTV_ORG}/logos.json"):
        url, ch = l.get("url"), l.get("channel")
        if not url or not ch:
            continue
        if (l.get("format") or "").upper() == "SVG" or url.lower().endswith(".svg"):
            continue
        if ch not in logos or l.get("feed") is None:
            logos[ch] = url
    out = []
    for s in get_json(f"{IPTV_ORG}/streams.json"):
        ch_id = s.get("channel")
        ch = channels.get(ch_id) if ch_id else None
        if ch_id and ch_id in blocked:
            continue
        if ch and (ch.get("is_nsfw") or ch.get("closed")):
            continue
        if ch and "shop" in (ch.get("categories") or []):
            continue
        feed = feeds.get((ch_id, s.get("feed"))) or feeds.get((ch_id, None))
        name = (ch or {}).get("name") or s.get("title") or ""
        country = (ch or {}).get("country")
        if not country and ch_id and "." in ch_id:
            country = ch_id.rsplit(".", 1)[1].split("@")[0]
        out.append(dict(name=clean_display_name(name), country=country_code(country),
                        url=(s.get("url") or "").strip(), logo=logos.get(ch_id),
                        id=ch_id, ref=s.get("referrer"), ua=s.get("user_agent"),
                        langs=(feed or {}).get("languages"),
                        cats=(ch or {}).get("categories") or [],
                        area=(feed or {}).get("broadcast_area") or []))
    return out


def src_famelack():
    out = []
    for m in get_json(FAMELACK):
        streams = [u for u in ((m.get("sources") or {}).get("streams") or []) if isinstance(u, str)]
        for url in streams[:2]:
            out.append(dict(name=clean_display_name(m.get("name")), country=country_code(m.get("country")),
                            url=url.strip(), logo=None, id=None, langs=m.get("languages")))
    return out


def src_freetuxtv():
    out = []
    td_re = re.compile(r"<td[^>]*>(.*?)</td>", re.S)
    for page in range(1, 80):
        page_html = get(FREETUX + (f"&WebStream_page={page}" if page > 1 else ""), timeout=60)
        found = 0
        for row in re.split(r"<tr[\s>]", page_html)[1:]:
            cells = td_re.findall(row)
            if len(cells) < 3:
                continue
            links = [html.unescape(h) for h in re.findall(r'href="(https?://[^"]+)"', cells[0])
                     if "freetuxtv.net" not in h]
            if not links:
                continue
            title = re.split(r"<br\s*/?>|=&gt;|=>", cells[0])[0]
            title = html.unescape(re.sub(r"<[^>]*>", "", title)).strip()
            flag = re.search(r'alt="([^"]*)"', cells[2])
            out.append(dict(name=clean_display_name(title) or links[0],
                            country=country_code(flag.group(1) if flag else ""),
                            url=links[0], logo=None, id=None, langs=None))
            found += 1
        if not found or f"WebStream_page={page + 1}" not in page_html:
            break
    return out


SOURCES = [("josxha", src_josxha), ("Free-TV", src_free_tv), ("iptv-org", src_iptv_org),
           ("Famelack", src_famelack), ("FreetuxTV", src_freetuxtv)]


# ---------------------------------------------------------------- Zusammenführen

def german_ok(entry):
    """Nur deutschsprachige Sender."""
    langs = entry.get("langs")
    return not langs or "deu" in langs or "gsw" in langs


def merge(entries_by_source, flt):
    groups, by_id, by_name = [], {}, {}
    dropped = {"andere Länder": 0, "Herkunft/Technik": 0, "Teleshopping": 0, "fremdsprachig": 0,
               "filter.txt": 0}
    for prio, (source, entries) in enumerate(entries_by_source):
        for order, e in enumerate(entries):
            url, name = e["url"], e["name"]
            if not url or not name:
                continue
            if e["country"] != "DE":
                dropped["andere Länder"] += 1
                continue
            if not technically_ok(url):
                dropped["Herkunft/Technik"] += 1
                continue
            if not flt.allowed(name, url):
                dropped["filter.txt"] += 1
                continue
            if SHOP_RE.search(name):
                dropped["Teleshopping"] += 1
                continue
            if not german_ok(e):
                dropped["fremdsprachig"] += 1
                continue
            nkey = (e["country"], norm_name(name))
            g = None
            if e.get("id") and e["id"] in by_id:
                g = by_id[e["id"]]
            elif nkey in by_name:
                g = by_name[nkey]
            if g is None:
                g = dict(k=e.get("id") or f"{e['country']}:{nkey[1]}", n=name, c=e["country"],
                         l=e.get("logo"), u=[], prio=prio, order=order, g=None)
                groups.append(g)
            if not g.get("g"):
                g["g"] = group_for(e)
            if e.get("id"):
                by_id.setdefault(e["id"], g)
            by_name.setdefault(nkey, g)
            if not g.get("l") and e.get("logo"):
                g["l"] = e["logo"]
            if url not in [x if isinstance(x, str) else x["u"] for x in g["u"]]:
                if e.get("ref") or e.get("ua"):
                    item = {"u": url}
                    if e.get("ref"):
                        item["r"] = e["ref"]
                    if e.get("ua"):
                        item["a"] = e["ua"]
                    g["u"].append(item)
                else:
                    g["u"].append(url)
    return groups, dropped


# ---------------------------------------------------------------- Prüfen (optional)

def definitely_dead(url):
    """True nur bei eindeutigem Tod (Name unbekannt, 404/410). Ländersperren zählen nicht."""
    host = host_of(url)
    try:
        socket.getaddrinfo(host, None)
    except socket.gaierror:
        return True
    except Exception:
        return False
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=12) as r:
            r.read(256)
        return False
    except urllib.error.HTTPError as e:
        return e.code in (404, 410)
    except Exception:
        return False


def prune(groups):
    urls = sorted({x if isinstance(x, str) else x["u"] for g in groups for x in g["u"]})
    log(f"Prüfe {len(urls)} Adressen …")
    with cf.ThreadPoolExecutor(128) as ex:
        dead = {u for u, d in zip(urls, ex.map(definitely_dead, urls)) if d}
    if len(dead) > 0.6 * len(urls):
        log(f"WARNUNG: {len(dead)} von {len(urls)} tot – vermutlich Netzproblem, es wird nichts entfernt.")
        return groups, 0
    kept, removed = [], 0
    for g in groups:
        g["u"] = [x for x in g["u"] if (x if isinstance(x, str) else x["u"]) not in dead]
        if g["u"]:
            kept.append(g)
        else:
            removed += 1
    return kept, removed


# ---------------------------------------------------------------- Hauptteil

def main():
    flt = Filter(FILTER)
    collected = []
    for name, fn in SOURCES:
        try:
            entries = fn()
            log(f"{name}: {len(entries)} Einträge")
        except Exception as e:
            entries = []
            log(f"{name}: FEHLER {e} – Quelle wird diesmal übersprungen")
        collected.append((name, entries))

    if not any(entries for name, entries in collected if name in ("josxha", "iptv-org")):
        log("Wichtige Quellen fehlen – alte Liste bleibt unverändert.")
        return 1

    groups, dropped = merge(collected, flt)
    log("Aussortiert:", ", ".join(f"{k} {v}" for k, v in dropped.items()))

    if "--pruefen" in sys.argv:
        groups, removed = prune(groups)
        log(f"Eindeutig tot und entfernt: {removed} Sender")

    for g in groups:
        # Was keiner Gruppe zugeordnet werden konnte, sind fast immer Lokalsender
        g["g"] = g.get("g") or "Lokal & Offene Kanäle"
    groups.sort(key=lambda g: (GROUPS.index(g["g"]), g["prio"] != 0,
                               g["order"] if g["prio"] == 0 else 0, g["n"].lower()))
    channels = []
    for g in groups:
        ch = {"k": g["k"], "n": g["n"], "c": g["c"], "g": g["g"], "u": g["u"][:MAX_URLS]}
        if g.get("l"):
            ch["l"] = g["l"]
        channels.append(ch)

    old = None
    if OUT.exists():
        try:
            old = json.loads(OUT.read_text(encoding="utf-8"))
        except Exception:
            old = None
    if old and old.get("channels") == channels:
        log("Keine Änderungen.")
        return 0

    data = {"version": 1,
            "updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "channels": channels}
    OUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    old_names = {c["n"] for c in (old or {}).get("channels", [])}
    new_names = {c["n"] for c in channels}
    added = sorted(new_names - old_names, key=str.lower)
    gone = sorted(old_names - new_names, key=str.lower)
    per_group = ", ".join(f"{name} {sum(1 for c in channels if c['g'] == name)}" for name in GROUPS)
    summary = [f"Senderliste: {len(channels)} deutsche Sender ({per_group})",
               f"neu: {len(added)}, weggefallen: {len(gone)}", ""]
    if old:
        summary += [f"+ {n}" for n in added[:60]]
        summary += [f"- {n}" for n in gone[:60]]
    text = "\n".join(summary)
    log(text)
    out_msg = os.environ.get("AENDERUNGEN")
    if out_msg:
        pathlib.Path(out_msg).write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
