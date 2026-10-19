"""Simulate the NEW emitDescriptorLinks logic and print the rows a user would see."""
import base64, json, re, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"
REF = "https://mosalsaly.com/"
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")
HLS_PATH_RE = re.compile(r"/hls(/|\?|$)", re.I)
BS = chr(92)

def dec(e):
    if not e: return None
    raw = base64.b64decode(e)
    return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode() if len(raw) > 12 else None

def clean(u):
    if not u: return None
    for a, b in ((BS + "u0026", "&"), (BS + "/", "/"), (BS + "u003c", "<"), (BS + "u003e", ">")):
        u = u.replace(a, b)
    return u

def get(url, rng=None, tmo=10):
    h = {"User-Agent": UA, "Referer": REF}
    if rng: h["Range"] = rng
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=tmo)
        return r.status, r.headers.get("Content-Type") or "", r.read(65536).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type") or "", ""
    except Exception:
        return None, "", ""

def kind_by_name(url, declared):
    if ".m3u8" in url.lower() or ".m3u" in url.lower() or HLS_PATH_RE.search(url) or (declared or "") == "storyreel":
        return "M3U8"
    return "VIDEO"

def probe_media(url, declared):
    """-> (kind, body, retryable)"""
    idu = url.lower()
    direct = (not HLS_PATH_RE.search(url)) and (declared in ("mp4","mpd","dash")
        or ".mp4" in idu or ".m4v" in idu or "videoplayback" in idu)
    if direct and ".m3u8" not in idu and ".m3u" not in idu:
        return "VIDEO", None, False
    code, ct, body = get(url, "bytes=0-65535", 8)
    if body.lstrip().startswith("#EXTM3U"): return "M3U8", body, False
    if ct.lower().startswith("video/"): return "VIDEO", None, False
    if code is None: return None, None, True
    return None, None, False

def probe_quality(url, declared):
    code, ct, body = get(url, "bytes=0-65535", 8)
    if code and 200 <= code < 300:
        if body.lstrip().startswith("#EXTM3U"): return "M3U8", body, False
        if ct.lower().startswith("text/html"): return None, None, False
        return "VIDEO", None, False
    if code is None: return None, None, True
    return None, None, False

def resolve(master, uri):
    u = uri.strip()
    if u.lower().startswith("http"): return u
    if u.startswith("/"):
        m = re.match(r"^https?://[^/]+", master)
        return (m.group(0) if m else "") + u
    base = master.split("?")[0].rsplit("/", 1)[0]
    return base + "/" + u if base else u

RES = re.compile(r"RESOLUTION=\d+x(\d+)", re.I)
def master_variants(master, body):
    if "#EXT-X-STREAM-INF" not in body: return []
    out, h = [], None
    for raw in body.splitlines():
        line = raw.strip()
        if not line: continue
        if line.upper().startswith("#EXT-X-STREAM-INF"):
            m = RES.search(line); h = m.group(1) if m else None; continue
        if line.startswith("#"): continue
        hh, h = h, None
        if hh: out.append((hh, resolve(master, line)))
    return out

AUDIO = re.compile(r'#EXT-X-MEDIA:TYPE=AUDIO[^>]*?NAME="([^"]*)"[^>]*?URI="([^"]+)"')

def simulate(platform, book, serial=1):
    u = "https://mosalsaly.com/api/episode-source/%s/%d?lang=ar&refresh=1" % (book, serial)
    d = json.loads(get(u, None, 45)[2]).get("descriptor")
    if not d: return {"platform": platform, "err": "no descriptor"}
    seen, alive_urls, alive, auto_only = set(), set(), [], []
    t0 = time.time()
    for ch in d.get("chain") or []:
        t = (ch.get("type") or "").lower()
        cands = {}
        b = clean(dec(ch.get("enc")))
        if b: cands[b] = ""
        for q, v in (ch.get("encByQuality") or {}).items():
            uu = clean(dec(v))
            if uu: cands[uu] = q
        mk = next((k for k in cands if cands[k] and cands[k].lower() == "default"), None)  # value lookup
        master_url = mk
        if mk: cands.pop(mk)
        au = clean(dec(ch.get("encAuto")))
        to_probe = [(u2, q) for u2, q in cands.items()]
        fresh = [(u2, q) for u2, q in to_probe if not (u2 in seen or seen.add(u2))]
        with ThreadPoolExecutor(max_workers=8) as ex:
            got = list(ex.map(lambda p: (p[0], p[1]) + (probe_quality(p[0], t) if p[1] else probe_media(p[0], t)), fresh))
        for url, q, kind, body, retry in got:
            if kind is None: continue
            alive_urls.add(url); alive.append((url, q, kind, body))
            if not q and body and "#EXT-X-STREAM-INF" in body: auto_only.append((url, body))
        auto = master_url or au
        if auto and auto not in seen and auto not in alive_urls:
            kind, body, retry = probe_media(auto, t)
            if kind is None:
                pass
            else:
                variants = [(q2, u2) for u2, q2 in cands.items() if q2]
                alive.append((auto, "تلقائي", kind, body))
                if not variants: auto_only.append((auto, body or ""))
    hidden = {}
    for murl, mbody in auto_only:
        for h, u2 in master_variants(murl, mbody):
            if u2 not in alive_urls and u2 not in seen and u2 not in hidden: hidden[u2] = h
    with ThreadPoolExecutor(max_workers=8) as ex:
        v = list(ex.map(lambda p: (p[0], p[1]) + probe_media(p[0], "hls"), list(hidden.items())[:12]))
    for u2, h, kind, body, retry in v:
        if kind is None and not retry: continue
        if u2 not in alive_urls:
            alive_urls.add(u2); alive.append((u2, h, kind or "M3U8", body))
    alive.sort(key=lambda a: a[2] != "VIDEO")
    rows = []
    for url, q, kind, body in alive:
        is_master = bool(body and "#EXT-X-STREAM-INF" in body)
        qq = q if q else ("تلقائي" if is_master else "")
        rows.append("%s %d · %s%s" % (platform, serial, "m3u8" if kind == "M3U8" else "mp4", " · " + qq if qq else ""))
    return {"platform": platform, "sec": round(time.time() - t0, 2), "rows": rows}

for plat, book in [("petadrama", "6a7451608cdb9c36e3f4866f"), ("moboreels", "12840415"),
                   ("dramabox", "42000023908"), ("playlet", "10045"), ("shorttv", "21958")]:
    try:
        r = simulate(plat, book)
    except Exception as e:
        r = {"platform": plat, "err": "exc " + str(e)[:90]}
    print(json.dumps(r, ensure_ascii=False)); sys.stdout.flush()
