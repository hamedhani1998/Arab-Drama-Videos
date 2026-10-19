"""Why did the plugin's probe drop dramabox's live 720/1080 mp4 links?"""
import base64, json, time, urllib.parse, urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"
REF = "https://mosalsaly.com/"
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")
def dec(e):
    raw = base64.b64decode(e)
    return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode() if len(raw) > 12 else None

u = "https://mosalsaly.com/api/episode-source/42000023908/1?lang=ar&refresh=1"
d = json.loads(urllib.request.urlopen(urllib.request.Request(
    u, headers={"User-Agent": UA, "Referer": REF}), timeout=45).read())["descriptor"]
links = {}
for ch in d["chain"]:
    if ch.get("enc"):
        links["enc"] = dec(ch["enc"])
    for q, v in (ch.get("encByQuality") or {}).items():
        links[q] = dec(v)

for name, url in links.items():
    # exactly what probeQualityUrlHttp11 does
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers={
            "User-Agent": UA, "Referer": REF, "Range": "bytes=0-65535"}), timeout=12)
        buf = r.read(65536)
        print(f"{name:6} code={r.status} read={len(buf)} ct={r.headers.get('Content-Type')} dt={time.time()-t0:.2f}s  -> {('M3U8' if buf[:7]==b'#EXTM3U' else 'VIDEO')}")
    except Exception as e:
        print(f"{name:6} EXC {type(e).__name__}: {str(e)[:80]} dt={time.time()-t0:.2f}s  b64={base64.b64encode(url.encode()).decode()[:40]}")
    time.sleep(0.4)
