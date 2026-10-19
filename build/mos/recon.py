"""Per-platform recon: descriptor shape, every link, and how long each phase takes.

Mirrors what MosalsalyProvider.loadLinks does (fetch descriptor -> decrypt ->
probe each candidate) so the measured delay matches what the user sees.
"""
import base64
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"}
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")

PLATFORMS = ["bibishort", "bilitv", "dotdrama", "dramabite", "dramabox", "flickreels",
             "goodshort", "happyshort", "idrama", "joyreels", "kalostv", "moboreels",
             "moreshort", "mydramawave", "netshort", "petadrama", "pinedrama", "playlet",
             "reelshort", "shorttv", "shortwave", "stardust", "storyreel"]


def dec(enc):
    if not enc:
        return None
    try:
        raw = base64.b64decode(enc)
        if len(raw) <= 12:
            return None
        return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode("utf-8")
    except Exception as e:
        return "<FAIL %s>" % e


def clean(u):
    if not u:
        return None
    for a, b in (("\\u0026", "&"), ("\\/", "/"), ("\\u003c", "<"), ("\\u003e", ">")):
        u = u.replace(a, b)
    return u


def get(url, referer=None, rng=None, tmo=30):
    h = dict(UA)
    if referer:
        h["Referer"] = referer
    if rng:
        h["Range"] = rng
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=tmo)
        b = r.read()
        return {"code": r.status, "ct": r.headers.get("Content-Type"),
                "len": len(b), "dt": time.time() - t0, "body": b}
    except urllib.error.HTTPError as e:
        return {"code": e.code, "ct": e.headers.get("Content-Type"), "len": 0,
                "dt": time.time() - t0, "body": (e.read(200) or b"")}
    except Exception as e:
        return {"code": None, "ct": None, "len": 0, "dt": time.time() - t0,
                "body": str(e)[:90].encode()}


def book_of(platform):
    r = get(f"https://mosalsaly.com/masdar/{platform}", referer="https://mosalsaly.com/")
    if not r["body"]:
        return None, None, r
    html = r["body"].decode("utf-8", "replace")
    m = re.search(r'href="(/mosalsal/[^"]+)"', html)
    if not m:
        return None, None, r
    slug = urllib.parse.unquote(m.group(1))
    g = get("https://mosalsaly.com" + urllib.parse.quote(slug), referer="https://mosalsaly.com/")
    d = g["body"].decode("utf-8", "replace")
    b = re.search(r'bookId\\?":\\?"?([^"\\<>/\s]{2,})', d)
    return (b.group(1) if b else None), slug, {"section_dt": r["dt"], "detail_dt": g["dt"],
                                               "detail_len": g["len"]}


def descriptor(book, serial=1):
    u = f"https://mosalsaly.com/api/episode-source/{urllib.parse.quote(book)}/{serial}?lang=ar&refresh=1"
    r = get(u, referer="https://mosalsaly.com/", tmo=45)
    if not r["body"]:
        return None, r
    try:
        node = json.loads(r["body"].decode("utf-8", "replace"))
    except Exception as e:
        return None, {**r, "parse": str(e)}
    return node.get("descriptor"), r


def report(platform):
    book, slug, sec = book_of(platform)
    out = {"platform": platform, "book": book, "slug": slug, "section_dt": sec.get("section_dt"),
           "detail_dt": sec.get("detail_dt")}
    if not book:
        out["err"] = "no bookId"
        return out
    d, r = descriptor(book)
    out["api_dt"] = round(r["dt"], 2)
    if not d:
        out["err"] = f"descriptor null ({r['body'][:60]})"
        return out
    out["source"] = d.get("source")
    links = []           # (url, quality, type)
    for ch in d.get("chain") or []:
        t = (ch.get("type") or "").lower()
        u = clean(dec(ch.get("enc")))
        if u:
            links.append((u, "", t, "enc"))
        for q, v in (ch.get("encByQuality") or {}).items():
            uu = clean(dec(v))
            if uu:
                links.append((uu, q, t, "encByQuality"))
        au = clean(dec(ch.get("encAuto")))
        if au:
            links.append((au, "auto", t, "encAuto"))
    # dedup by url, keep first
    seen, uniq = set(), []
    for u, q, t, src in links:
        if u in seen:
            continue
        seen.add(u)
        uniq.append((u, q, t, src))
    out["n_raw"] = len(links)
    out["n_uniq"] = len(uniq)

    # probe in parallel, the way the plugin does (Range on media, plain on playlists)
    def probe(item):
        u, q, t, src = item
        plain = ".m3u8" in u or "/hls" in u
        pr = get(u, referer="https://mosalsaly.com/",
                 rng=None if plain else "bytes=0-65535", tmo=25)
        body = pr["body"]
        is_hls = body[:7] == b"#EXTM3U"
        return {"q": q, "type": t, "src": src, "code": pr["code"], "ct": pr["ct"],
                "dt": round(pr["dt"], 2), "hls": is_hls,
                "n_variant": len(re.findall(rb"#EXT-X-STREAM-INF", body)) if is_hls else 0,
                "n_media": len(re.findall(rb"#EXT-X-MEDIA", body)) if is_hls else 0,
                "url": u[:120]}

    with ThreadPoolExecutor(max_workers=8) as ex:
        out["links"] = list(ex.map(probe, uniq))
    sub = d.get("subtitle")
    if sub:
        su = clean(dec(sub.get("enc")))
        out["sub"] = {"lang": sub.get("language"), "format": sub.get("format"),
                      "url": (su or "")[:110]}
        if su:
            sr = get(su, referer="https://mosalsaly.com/", tmo=20)
            out["sub"]["code"] = sr["code"]
            out["sub"]["dt"] = round(sr["dt"], 2)
            out["sub"]["head"] = sr["body"][:40].decode("utf-8", "replace")
    return out


if __name__ == "__main__":
    targets = sys.argv[1:] or PLATFORMS
    for p in targets:
        try:
            r = report(p)
        except Exception as e:
            r = {"platform": p, "err": "exc " + str(e)[:80]}
        print(json.dumps(r, ensure_ascii=False))
        sys.stdout.flush()
