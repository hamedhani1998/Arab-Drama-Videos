import base64, json, urllib.parse, urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
UA="Mozilla/5.0"
REF="https://mosalsaly.com/"
KEY=base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")
def dec(e):
    raw=base64.b64decode(e)
    if len(raw)<=12: return None
    return AESGCM(KEY).decrypt(raw[:12],raw[12:],None).decode()
req = urllib.request.Request("https://mosalsaly.com/api/episode-source/42000023908/1?lang=ar&refresh=1", headers={"User-Agent": UA, "Referer": REF})
with urllib.request.urlopen(req, timeout=45) as r:
    d = json.loads(r.read())
ch = d["descriptor"]["chain"][0]
for q,v in ch["encByQuality"].items():
    print(q, dec(v))
