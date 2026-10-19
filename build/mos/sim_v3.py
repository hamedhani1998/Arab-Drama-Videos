"""Mirror of the NEW MosalsalyProvider emit logic — live verification."""
import sys, json, re, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0,'.')
import desc, recon

UA = dict(desc.UA)

def is_expired(u):
    m = re.search(r'expires=(\d+)', u, re.I)
    return bool(m and int(m.group(1)) < time.time())

def resolve(master, uri):
    u = uri.strip()
    if u.startswith("http", True): return u
    if u.startswith("/"):
        o = re.match(r'^https?://[^/]+', master)
        return (o.group(0) + u) if o else u
    base = master.split('?')[0].rsplit('/',1)[0]
    return f"{base}/{u}" if base else u

def probe(url, declared=None):
    """probeQualityUrlHttp11 shape: Range GET, #EXTM3U => M3U8, video/* => VIDEO, 4xx decisive."""
    if is_expired(url): return ("EXPIRED", None)
    req = urllib.request.Request(url, headers={**UA, "Range":"bytes=0-65535"})
    try:
        r = urllib.request.urlopen(req, timeout=12)
        head = r.read(65536)
        ct = r.headers.get("Content-Type","")
        if head[:7] == b'#EXTM3U': return ("M3U8", head.decode("utf-8","replace"))
        if ct.startswith("video/"): return ("VIDEO", None)
        return ("DEAD", None)
    except urllib.error.HTTPError as e:
        return ("DEAD", None)            # decisive
    except Exception:
        return ("UNKNOWN", None)         # retryable

def has_sep_audio(body):
    if "#EXT-X-MEDIA:TYPE=AUDIO" in body.upper(): return True
    return any(l.upper().startswith("#EXT-X-STREAM-INF") and "AUDIO=" in l.upper()
               for l in body.splitlines())

def variants_of(murl, body):
    if "#EXT-X-STREAM-INF" not in body: return []
    out=[]; h=None
    for raw in body.splitlines():
        l=raw.strip()
        if not l: continue
        if l.upper().startswith("#EXT-X-STREAM-INF"):
            m=re.search(r'RESOLUTION=\d+x(\d+)',l,re.I); h=m.group(1) if m else None; continue
        if l.startswith("#"): continue
        u=resolve(murl,l); hh=h; h=None
        if hh and u not in [x[1] for x in out]: out.append((hh,u))
    return out

def max_height(body):
    hs=[int(m.group(1)) for m in re.finditer(r'RESOLUTION=\d+x(\d+)',body,re.I)]
    return str(max(hs)) if hs else None

def sim(slug, serial=1):
    book,_,_ = recon.book_of(slug)
    if not book: return {"p":slug,"err":"nobook"}
    d,_ = recon.descriptor(book, serial)
    if not d: return {"p":slug,"err":"nodesc"}
    seen=set(); rows=[]
    for ch in d.get("chain") or []:
        cands={}
        base = desc.clean(desc.dec(ch.get("enc"))) if ch.get("enc") else None
        if base: cands[base]=""
        eq = ch.get("encByQuality") or {}
        for q,v in eq.items():
            u=desc.clean(desc.dec(v))
            if u: cands[u]=q
        # ★ master is the VALUE "default", not a key
        mk = next((k for k,v in cands.items() if v.lower()=="default"), None)
        murl = cands.pop(mk) if mk else None
        # ★ expired-sign links dropped locally, before any probe
        for u in [k for k in cands if is_expired(k)]:
            cands.pop(u); rows.append({"drop":"expired","url":u[:60]})
        if murl and is_expired(murl):
            rows.append({"drop":"expired-master","url":murl[:60]}); murl=None
        auto = murl
        fresh=[(u,q) for u,q in cands.items() if u not in seen]
        for u,_ in fresh: seen.add(u)
        auto_elig = auto is not None and auto not in seen
        jobs=[]
        with ThreadPoolExecutor(16) as ex:
            futs=[]
            for u,q in fresh: futs.append(ex.submit(probe,u))
            af = ex.submit(probe,auto) if auto_elig else None
            results=[f.result() for f in futs]
            ares = af.result() if af else None
        auto_body=None; auto_variants=[]
        for (u,q),(kind,body) in zip(fresh,results):
            if kind=="DEAD": rows.append({"skip":"dead","q":q,"url":u[:60]}); continue
            if kind=="EXPIRED": continue
            if body and "#EXT-X-STREAM-INF" in body: auto_body=body
            rows.append({"q":q or "-","kind":kind,"url":u[:60]})
        if ares and ares[0]!="DEAD" and auto_elig:
            kind,body = ares
            auto_variants=[(q,u) for u,q in cands.items() if q]
            if body and "#EXT-X-STREAM-INF" in body:
                auto_body=body
                if not auto_variants: pass
            rows.append({"q":"تلقائي","kind":kind,"variants":len(auto_variants),
                         "sep_audio": has_sep_audio(body) if body else None})
        # ★ inner variants — SKIPPED when master declares separate audio
        if auto_body and not auto_variants and not has_sep_audio(auto_body):
            for h,u in variants_of(auto or "", auto_body)[:12]:
                if u not in seen:
                    seen.add(u); rows.append({"inner":h,"url":u[:60]})
        elif auto_body and has_sep_audio(auto_body):
            rows.append({"inner":"SKIPPED-video-only-audio"})
    return {"p":slug,"src":d.get("source"),"rows":rows}

if __name__=="__main__":
    for slug in sys.argv[1:]:
        print(json.dumps(sim(slug), ensure_ascii=False), flush=True)
