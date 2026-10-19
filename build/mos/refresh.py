"""Is `refresh=1` needed? Compare latency and link liveness for refresh=0 vs 1."""
import base64, json, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"
REF = "https://mosalsaly.com/"
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")

def dec(e):
    try:
        raw = base64.b64decode(e)
        return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode() if len(raw) > 12 else None
    except Exception:
        return None

def get(u, rng=None, tmo=45):
    h = {"User-Agent": UA, "Referer": REF}
    if rng: h["Range"] = rng
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers=h), timeout=tmo)
        return r.status, r.read(), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, (e.read(300) or b""), time.time() - t0
    except Exception as e:
        return None, str(e).encode(), time.time() - t0

CASES = [("42000023908", "dramabox"), ("6a7451608cdb9c36e3f4866f", "petadrama"),
         ("190158", "happyshort"), ("26604", "kalostv")]
for bid, name in CASES:
    for rv in ("1", "0"):
        u = f"https://mosalsaly.com/api/episode-source/{urllib.parse.quote(bid)}/1?lang=ar&refresh={rv}"
        code, body, dt = get(u)
        try:
            d = json.loads(body.decode("utf-8", "replace")).get("descriptor")
        except Exception:
            d = None
        if not d:
            print(f"{name} refresh={rv} {code} {dt:.2f}s -> no descriptor {body[:80]}"); continue
        urls = []
        for ch in d.get("chain") or []:
            b = dec(ch.get("enc"))
            if b: urls.append(b)
            for q, v in (ch.get("encByQuality") or {}).items():
                x = dec(v)
                if x: urls.append(f"{q}:{x}")
        seenh = [u for u in urls if "/v1/" in u]
        print(f"{name} refresh={rv} {code} {dt:.2f}s node0_links={len(urls)} shortdizilab={seenh[:1]}")
    print()
