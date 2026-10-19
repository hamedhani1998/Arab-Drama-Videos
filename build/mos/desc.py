"""Fetch and decrypt a Mosalsaly episode descriptor, printing every field."""
import base64
import json
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36",
      "Referer": "https://mosalsaly.com/"}
KEY = base64.b64decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=")


def dec(enc):
    if not enc:
        return None
    raw = base64.b64decode(enc)
    if len(raw) <= 12:
        return None
    try:
        return AESGCM(KEY).decrypt(raw[:12], raw[12:], None).decode("utf-8")
    except Exception as e:
        return "<FAIL %s>" % e


def clean(u):
    if not u:
        return None
    for a, b in (("\\u0026", "&"), ("\\/", "/"), ("\\u003c", "<"), ("\\u003e", ">")):
        u = u.replace(a, b)
    return u


def fetch(url, timeout=40):
    t0 = time.time()
    req = urllib.request.Request(url, headers=UA)
    try:
        body = urllib.request.urlopen(req, timeout=timeout).read()
        return body.decode("utf-8", "replace"), time.time() - t0, None
    except Exception as e:
        return None, time.time() - t0, e


def probe(url):
    """Player-shaped GET: what does the CDN answer right now?"""
    h = dict(UA)
    h["Range"] = "bytes=0-1023"
    req = urllib.request.Request(url, headers=h)
    t0 = time.time()
    try:
        r = urllib.request.urlopen(req, timeout=25)
        b = r.read(512)
        return r.status, r.headers.get("Content-Type"), len(b), time.time() - t0, b[:80]
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type"), 0, time.time() - t0, (e.read(160) or b"")
    except Exception as e:
        return None, None, 0, time.time() - t0, str(e)[:80].encode()


if __name__ == "__main__":
    book = sys.argv[1]
    serial = sys.argv[2] if len(sys.argv) > 2 else "1"
    url = f"https://mosalsaly.com/api/episode-source/{urllib.parse.quote(book)}/{serial}?lang=ar&refresh=1"
    txt, dt, err = fetch(url)
    print(f"GET {url}\n  -> {dt:.2f}s err={err} len={len(txt) if txt else 0}")
    if not txt:
        sys.exit(1)
    node = json.loads(txt)
    d = node.get("descriptor")
    if not d:
        print("descriptor null:", txt[:200])
        sys.exit(0)
    print("descriptor keys:", list(d.keys()))
    print("source:", d.get("source"), "| chain len:", len(d.get("chain") or []))
    for n, ch in enumerate(d.get("chain") or []):
        print(f"--- chain[{n}] type={ch.get('type')} refresh={ch.get('refresh')} "
              f"refreshAfter={ch.get('refreshAfter')} keys={list(ch.keys())}")
        for k in ("enc", "encAuto"):
            u = clean(dec(ch.get(k)))
            if u:
                print(f"    {k}: {u}")
        eq = ch.get("encByQuality") or {}
        for q, v in eq.items():
            u = clean(dec(v))
            print(f"    q[{q}]: {u}")
            if u:
                for label, val in (("status", None),):
                    pass
        for k in ("url", "videoUrl", "hls", "mp4"):
            if ch.get(k):
                print(f"    raw {k}: {str(ch[k])[:200]}")
    sub = d.get("subtitle")
    if sub:
        print("subtitle:", json.dumps(sub, ensure_ascii=False)[:300])
        print("  sub url:", clean(dec(sub.get("enc"))))
    else:
        print("subtitle: none")
