"""Run the NEW emit logic against all 23 platforms: rows + wall clock + dead-link leaks."""
import base64, json, re, sys, time, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"
REF = "https://mosalsaly.com/"
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")
HLS_PATH_RE = re.compile(r"/hls(/|\?|$)", re.I)
BS = chr(92)
PLATFORMS = ["bibishort","bilitv","dotdrama","dramabite","dramabox","flickreels","goodshort",
             "happyshort","idrama","joyreels","kalostv","moboreels","moreshort","mydramawave",
             "netshort","petadrama","pinedrama","playlet","reelshort","shorttv","shortwave",
             "stardust","storyreel"]

def dec(e):
    if not e: return None
    try:
        raw = base64.b64decode(e)
        return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode() if len(raw) > 12 else None
    except Exception: return None

def clean(u):
    if not u: return None
    for a, b in ((BS+"u0026","&"),(BS+"/","/"),(BS+"u003c","<"),(BS+"u003e",">")): u = u.replace(a,b)
    return u

def get(url, rng=None, tmo=8, cap=65536):
    h = {"User-Agent": UA, "Referer": REF}
    if rng: h["Range"] = rng
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=tmo)
        return r.status, r.headers.get("Content-Type") or "", r.read(cap).decode("utf-8","replace")
    except urllib.error.HTTPError as e: return e.code, e.headers.get("Content-Type") or "", ""
    except Exception: return None, "", ""

def kind_by_name(url, declared):
    if ".m3u8" in url.lower() or ".m3u" in url.lower() or HLS_PATH_RE.search(url) or (declared or "") == "storyreel":
        return "M3U8"
    return "VIDEO"

def probe_media(url, declared):
    idu = url.lower()
    direct = (not HLS_PATH_RE.search(url)) and (declared in ("mp4","mpd","dash")
        or ".mp4" in idu or ".m4v" in idu or "videoplayback" in idu)
    if direct and ".m3u8" not in idu and ".m3u" not in idu: return "VIDEO", None, False
    code, ct, body = get(url, "bytes=0-65535")
    if body.lstrip().startswith("#EXTM3U"): return "M3U8", body, False
    if ct.lower().startswith("video/"): return "VIDEO", None, False
    if code is None: return None, None, True
    return None, None, False

def probe_quality(url, declared):
    code, ct, body = get(url, "bytes=0-65535")
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
        m = re.match(r"^https?://[^/]+", master); return (m.group(0) if m else "") + u
    base = master.split("?")[0].rsplit("/",1)[0]
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

def book_of(platform):
    _, _, html = get("https://mosalsaly.com/masdar/%s" % platform, None, 40, 4_000_000)
    m = re.search(r'href="(/mosalsal/[^"]+)"', html)
    if not m: return None
    _, _, d = get("https://mosalsaly.com" + urllib.parse.quote(urllib.parse.unquote(m.group(1))), None, 40, 4_000_000)
    b = re.search(r'bookId\?":\?"?([^"\<>/\s]{2,})', d)
    return b.group(1) if b else None

def simulate(platform):
    t0 = time.time()
    book = book_of(platform)
    if not book: return {"platform": platform, "err": "no bookId"}
    u = "https://mosalsaly.com/api/episode-source/%s/1?lang=ar&refresh=1" % urllib.parse.quote(book)
    try:
        d = json.loads(get(u, None, 45, 4_000_000)[2]).get("descriptor")
    except Exception as e:
        return {"platform": platform, "err": "desc " + str(e)[:50]}
    if not d: return {"platform": platform, "err": "no descriptor"}
    api_t = time.time() - t0
    seen, alive_urls, alive, auto_only = set(), set(), [], []
    for ch in d.get("chain") or []:
        t = (ch.get("type") or "").lower()
        cands = {}
        b = clean(dec(ch.get("enc")))
        if b: cands[b] = ""
        for q, v in (ch.get("encByQuality") or {}).items():
            uu = clean(dec(v))
            if uu: cands[uu] = q
        mk = next((k for k in cands if cands[k] and cands[k].lower() == "default"), None)
        master_url = mk
        if mk: cands.pop(mk)
        au = clean(dec(ch.get("encAuto")))
        fresh = [(u2, q) for u2, q in cands.items() if not (u2 in seen or seen.add(u2))]
        auto = master_url or au
        auto_eligible = bool(auto) and auto not in seen and all(f[0] != auto for f in fresh)
        with ThreadPoolExecutor(max_workers=8) as ex:
            fj = [ex.submit((lambda p: lambda: (p[0], p[1]) + (probe_quality(p[0], t) if p[1] else probe_media(p[0], t)))(p), None) for p in fresh]
            aj = ex.submit(lambda: (auto, "تلقائي") + probe_media(auto, t)) if auto_eligible else None
            got = [f.result() for f in fj]
            if aj: got.append(aj.result())
        for url, q, kind, body, retry in got:
            if kind is None: continue
            if q == "تلقائي":
                if auto_eligible:
                    variants = [(qq, u2) for u2, qq in cands.items() if qq]
                    alive_urls.add(url); alive.append((url, "تلقائي", kind, body))
                    if not variants: auto_only.append((url, body or ""))
                continue
            alive_urls.add(url); alive.append((url, q, kind, body))
            if not q and body and "#EXT-X-STREAM-INF" in body: auto_only.append((url, body))
    hidden = {}
    for murl, mbody in auto_only:
        for h, u2 in master_variants(murl, mbody):
            if u2 not in alive_urls and u2 not in seen and u2 not in hidden: hidden[u2] = h
    inner = list(hidden.items())[:12]
    with ThreadPoolExecutor(max_workers=8) as ex:
        v = list(ex.map(lambda p: (p[0], p[1]) + probe_media(p[0], "hls"), inner))
    for u2, h, kind, body, retry in v:
        if kind is None and not retry: continue
        if u2 not in alive_urls:
            alive_urls.add(u2); alive.append((u2, h, kind or "M3U8", body))
    alive.sort(key=lambda a: a[2] != "VIDEO")
    rows, masters = [], 0
    for url, q, kind, body in alive:
        is_master = bool(body and "#EXT-X-STREAM-INF" in body)
        if is_master: masters += 1
        qq = q if q else ("تلقائي" if is_master else "")
        rows.append("%s·%s%s" % ("m3u8" if kind=="M3U8" else "mp4", qq if qq else "-", "/M" if is_master else ""))
    aud = len(AUDIO.findall(alive[0][3] or "")) if alive else 0
    return {"platform": platform, "api": round(api_t,1), "total": round(time.time()-t0,1),
            "n_rows": len(rows), "masters": masters, "rows": rows}

for p in PLATFORMS:
    try: r = simulate(p)
    except Exception as e: r = {"platform": p, "err": "exc " + str(e)[:70]}
    print(json.dumps(r, ensure_ascii=False)); sys.stdout.flush()
