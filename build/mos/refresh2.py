"""refresh=0 vs refresh=1 on the token-bearing platforms: same links or stale?"""
import base64, hashlib, json, time, urllib.parse, urllib.request
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
def get(u, tmo=45):
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA, "Referer": REF}), timeout=tmo)
        return r.status, r.read(), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, (e.read(300) or b""), time.time() - t0
    except Exception as e:
        return None, str(e).encode(), time.time() - t0
CASES = [("2089923876184969218", "netshort"), ("31888", "storyreel"),
         ("210000643223", "idrama"), ("21958", "shorttv"), ("12840415", "moboreels")]
for bid, name in CASES:
    rows = []
    for rv in ("1", "0", "0"):
        u = f"https://mosalsaly.com/api/episode-source/{urllib.parse.quote(bid)}/1?lang=ar&refresh={rv}"
        code, body, dt = get(u)
        try:
            d = json.loads(body.decode("utf-8", "replace")).get("descriptor")
        except Exception:
            d = None
        if not d:
            rows.append((rv, code, dt, None, "no descriptor " + body[:60].decode("utf-8", "replace")))
            continue
        links = {}
        for ch in d.get("chain") or []:
            b = dec(ch.get("enc"))
            if b: links.setdefault(b, "base")
            for q, v in (ch.get("encByQuality") or {}).items():
                x = dec(v)
                if x: links.setdefault(x, q)
        key = hashlib.md5(("|".join(sorted(links))).encode()).hexdigest()[:10]
        first = sorted(links)[0][:78]
        rows.append((rv, code, round(dt, 2), key, first))
    print(name)
    for r in rows:
        print("   refresh=%s code=%s %.2fs md5=%s %s" % r)
