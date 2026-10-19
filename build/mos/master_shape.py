"""Verify the new master-body path: does one GET yield the quality list + audio tracks?"""
import base64, json, re, sys, time, urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"
REF = "https://mosalsaly.com/"
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")

def dec(e):
    raw = base64.b64decode(e)
    return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode() if len(raw) > 12 else None

def clean(u):
    if not u: return None
    for a, b in ((chr(92) + "u0026", "&"), (chr(92) + "/", "/")): u = u.replace(a, b)
    return u

def probe(url):
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers={
            "User-Agent": UA, "Referer": REF, "Range": "bytes=0-65535"}), timeout=8)
        body = r.read(65536).decode("utf-8", "replace")
        ct = r.headers.get("Content-Type") or ""
        kind = "M3U8" if body.lstrip().startswith("#EXTM3U") else ("VIDEO" if ct.startswith("video/") else None)
        return kind, body, time.time() - t0, ""
    except Exception as e:
        return None, "", time.time() - t0, "%s: %s" % (type(e).__name__, str(e)[:60])

RES = re.compile(r"RESOLUTION=\d+x(\d+)", re.I)
def variants(url, body):
    out = []; h = None
    for raw in body.splitlines():
        line = raw.strip()
        if not line: continue
        if line.upper().startswith("#EXT-X-STREAM-INF"):
            m = RES.search(line); h = m.group(1) if m else None; continue
        if line.startswith("#"): continue
        hh, h = h, None
        if hh: out.append((hh, line))
    return out

AUDIO = re.compile(r'#EXT-X-MEDIA:TYPE=AUDIO[^>]*?NAME="([^"]*)"[^>]*?URI="([^"]+)"')

def descriptor(book):
    u = "https://mosalsaly.com/api/episode-source/%s/1?lang=ar&refresh=1" % book
    txt = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA, "Referer": REF}), timeout=45).read()
    return json.loads(txt).get("descriptor")

for book, plat in [("6a7451608cdb9c36e3f4866f", "petadrama"), ("6737164105e9c81ffdcf1905", "shortwave"), ("10045", "playlet")]:
    try:
        d = descriptor(book)
    except Exception as e:
        print("=== %s descriptor EXC %s" % (plat, e)); continue
    if not d:
        print("=== %s descriptor None" % plat); continue
    for n, ch in enumerate(d.get("chain") or []):
        cands = {}
        if ch.get("enc"): cands[clean(dec(ch["enc"]))] = ""
        for q, v in (ch.get("encByQuality") or {}).items(): cands[clean(dec(v))] = q
        eq = list((ch.get("encByQuality") or {}).keys())
        mk = next((k for k in cands if cands[k] and cands[k].lower() == "default"), None)
        master = mk if mk else None
        if mk: cands.pop(mk, None)
        named = [k for k in cands if cands[k]]
        print("=== %s node%d eq=%s named=%d master=%s" % (plat, n, eq, len(named), bool(master)))
        for url, q in list(cands.items()):
            k, b, dt, err = probe(url)
            print("    cand q=%-8r -> %-5s dt=%.2fs %s %s" % (q, k, dt, url[:60], err))
        if master:
            k, b, dt, err = probe(master)
            print("    MASTER probe=%s dt=%.2fs bytes=%d err=%s" % (k, dt, len(b), err))
            print("    inner=%s" % variants(master, b))
            print("    audio=%s" % AUDIO.findall(b)[:4])
    sys.stdout.flush()
