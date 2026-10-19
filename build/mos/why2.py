import base64, json, time, urllib.parse, urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
UA="Mozilla/5.0"; REF="https://mosalsaly.com/"
KEY=base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")
def dec(e):
    raw=base64.b64decode(e)
    if len(raw)<=12: return None
    return AESGCM(KEY).decrypt(raw[:12],raw[12:],None).decode()
d=json.loads(urllib.request.urlopen(urllib.request.Request("https://mosalsaly.com/api/episode-source/42000023908/1?lang=ar&refresh=1",headers={"User-Agent":UA,"Referer":REF}),45).read())["descriptor"]
ch=d["chain"][0]
for q,v in (ch.get("encByQuality") or {}).items():
    url=dec(v); print(q); print(url)
