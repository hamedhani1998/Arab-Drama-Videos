"""Chain shape per platform + a plugin-shaped timing harness.

Answers: which platforms carry encAuto / a "default" master, how many EXT-X-STREAM-INF
variants each master really holds, and how many seconds the plugin's probe pass takes
with the exact timeouts MosalsalyProvider uses today.
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

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36"
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")
REF = "https://mosalsaly.com/"

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
        return None


def clean(u):
    if not u:
        return None
    for a, b in (("\\u0026", "&"), ("\\/", "/")):
        u = u.replace(a, b)
    return u


def get(url, rng=None, tmo=40):
    h = {"User-Agent": UA, "Referer": REF}
    if rng:
        h["Range"] = rng
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=tmo)
        return r.status, r.headers.get("Content-Type") or "", r.read(), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type") or "", (e.read(400) or b""), time.time() - t0
    except Exception as e:
        return None, type(e).__name__, b"", time.time() - t0


HLS_RE = re.compile(r"/hls(/|\?|$)", re.I)


def probe_media(url, declared, tmo_c=2.5, tmo_r=3.5, rng="bytes=0-65535"):
    """Mirror MosalsalyProvider.probeMedia -> (kind, body, seconds)."""
    idu = url.lower()
    path_hls = bool(HLS_RE.search(url))
    is_direct = (not path_hls) and (declared in ("mp4", "mpd", "dash")
                                    or ".mp4" in idu or ".m4v" in idu or "videoplayback" in idu)
    if is_direct and ".m3u8" not in idu and ".m3u" not in idu:
        return "VIDEO", None, 0.0
    t0 = time.time()
    h = {"User-Agent": UA, "Referer": REF, "Range": rng}
    try:
        req = urllib.request.Request(url, headers=h)
        r = urllib.request.urlopen(req, timeout=max(tmo_c, tmo_r))
        txt = r.read(65536).decode("utf-8", "replace")
        kind = "M3U8" if txt.lstrip().startswith("#EXTM3U") else None
        return kind, txt, time.time() - t0
    except Exception:
        return None, None, time.time() - t0


def probe_quality_http11(url, tmo_c=8.0, tmo_r=12.0):
    """Mirror probeQualityUrlHttp11 -> (kind, seconds)."""
    t0 = time.time()
    h = {"User-Agent": UA, "Referer": REF, "Range": "bytes=0-65535"}
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=max(tmo_c, tmo_r))
        if 200 <= r.status <= 299:
            head = r.read(65536).decode("utf-8", "replace").lstrip()
            return ("M3U8" if head.startswith("#EXTM3U") else "VIDEO"), time.time() - t0
        return None, time.time() - t0
    except Exception:
        return None, time.time() - t0


def descriptor_of(platform):
    r = urllib.request.Request(f"https://mosalsaly.com/masdar/{platform}",
                               headers={"User-Agent": UA, "Referer": REF})
    html = urllib.request.urlopen(r, timeout=40).read().decode("utf-8", "replace")
    m = re.search(r'href="(/mosalsal/[^"]+)"', html)
    slug = m.group(1)
    g = urllib.request.Request("https://mosalsaly.com" + urllib.parse.quote(slug),
                               headers={"User-Agent": UA, "Referer": REF})
    d = urllib.request.urlopen(g, timeout=40).read().decode("utf-8", "replace")
    b = re.search(r'bookId\\?":\\?"?([^"\\<>/\s]{2,})', d)
    book = b.group(1) if b else None
    if not book:
        return None
    u = f"https://mosalsaly.com/api/episode-source/{urllib.parse.quote(book)}/1?lang=ar&refresh=1"
    txt = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA, "Referer": REF}),
                                 timeout=45).read().decode("utf-8", "replace")
    return json.loads(txt).get("descriptor")


def shape(platform):
    out = {"platform": platform}
    t0 = time.time()
    try:
        d = descriptor_of(platform)
    except Exception as e:
        out["err"] = str(e)[:70]
        return out
    out["total_sec"] = round(time.time() - t0, 2)
    if not d:
        out["err"] = "no descriptor"
        return out
    out["nodes"] = len(d.get("chain") or [])
    nodes = []
    for ch in d.get("chain") or []:
        t = (ch.get("type") or "").lower()
        eq = list((ch.get("encByQuality") or {}).keys())
        base = clean(dec(ch.get("enc")))
        auto = clean(dec(ch.get("encAuto")))
        node = {"type": t, "qualities": eq, "has_encAuto": bool(auto),
                "default_in_eq": any(k.lower() == "default" for k in eq)}
        # base == one of the qualities? (the dramabox shortdizilab case)
        urls = {}
        urls[base] = "enc"
        for q, v in (ch.get("encByQuality") or {}).items():
            u = clean(dec(v))
            if u:
                urls[u] = q
        if auto:
            urls[auto] = "encAuto"
        node["uniq_urls"] = len(urls)
        node["raw_urls"] = len(eq) + 2
        nodes.append(node)
        out.setdefault("base_url", base)
    out["chain"] = nodes
    return out


def timing(platform):
    """Whole plugin-shaped probe pass, wall clock, for the first chain node."""
    d = descriptor_of(platform)
    if not d:
        return {"platform": platform, "err": "no descriptor"}
    ch = (d.get("chain") or [{}])[0]
    t = (ch.get("type") or "").lower()
    cands = {}
    base = clean(dec(ch.get("enc")))
    if base:
        cands[base] = ""
    for q, v in (ch.get("encByQuality") or {}).items():
        u = clean(dec(v))
        if u and u not in cands:
            cands[u] = q
    master = None
    for k in list(cands):
        if cands[k].lower() == "default":
            master = k
            del cands[k]
    auto = clean(dec(ch.get("encAuto")))
    res = {"platform": platform, "n_cand": len(cands), "has_default": master is not None,
           "has_encAuto": bool(auto)}
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=8) as ex:
        futs = {u: ex.submit(probe_quality_http11 if q else probe_media, u, t) for u, q in cands.items()}
        got = {u: f.result() for u, f in futs.items()}
    res["probe_sec"] = round(time.time() - t0, 2)
    res["dead"] = [u[:60] for u, v in got.items() if v[0] is None]
    res["kinds"] = {u[-28:]: v[0] for u, v in got.items()}
    if master or auto:
        a = master or auto
        t1 = time.time()
        kind, body, _ = probe_media(a, t)
        res["auto_sec"] = round(time.time() - t1, 2)
        res["auto_kind"] = kind
        res["auto_variants"] = len(re.findall(r"#EXT-X-STREAM-INF", body or ""))
        res["auto_audio"] = len(re.findall(r"#EXT-X-MEDIA", body or ""))
        if master:
            t2 = time.time()
            probe_media(master, t)
            res["master_sec"] = round(time.time() - t2, 2)
    return res


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "shape"
    targets = sys.argv[2:] or PLATFORMS
    fn = timing if mode == "timing" else shape
    for p in targets:
        try:
            print(json.dumps(fn(p), ensure_ascii=False))
        except Exception as e:
            print(json.dumps({"platform": p, "err": "exc " + str(e)[:70]}, ensure_ascii=False))
        sys.stdout.flush()
