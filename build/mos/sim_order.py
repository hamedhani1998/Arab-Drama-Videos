"""Final emission order with REAL Qualities ints — mirrors sortWith + qualityValue."""
import sys, json, re, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0,'.')
import desc, recon
UA=dict(desc.UA)
Q={144:0,240:2,360:3,480:4,720:5,1080:6,1440:7,2160:8}
UNK=4
def is_expired(u):
    m=re.search(r'expires=(\d+)',u,re.I); return bool(m and int(m.group(1))<time.time())
def qval(label):
    m=re.search(r'(\d{3,4})',label or "")
    if not m: return UNK
    d=int(m.group(1))
    for th in (2160,1440,1080,720,480,360,240):
        if d>=th: return Q[th]
    return Q[144]
def resolve(m,u):
    u=u.strip()
    if u.startswith("http",True): return u
    if u.startswith("/"):
        o=re.match(r'^https?://[^/]+',m); return (o.group(0)+u) if o else u
    b=m.split('?')[0].rsplit('/',1)[0]; return f"{b}/{u}" if b else u
def probe(url):
    if is_expired(url): return (None,None)
    try:
        r=urllib.request.urlopen(urllib.request.Request(url,headers={**UA,"Range":"bytes=0-65535"}),timeout=12)
        head=r.read(65536); ct=r.headers.get("Content-Type","")
        if head[:7]==b'#EXTM3U': return ("M3U8",head.decode("utf-8","replace"))
        if ct.startswith("video/"): return ("VIDEO",None)
        return (None,None)   # non-media body
    except urllib.error.HTTPError: return (None,None)   # 4xx decisive
    except Exception: return (None,None)                 # Kotlin: kind==null -> skip
def sep_audio(b): 
    if "#EXT-X-MEDIA:TYPE=AUDIO" in b.upper(): return True
    return any(l.upper().startswith("#EXT-X-STREAM-INF") and "AUDIO=" in l.upper() for l in b.splitlines())
def variants(m,b):
    if "#EXT-X-STREAM-INF" not in b: return []
    out=[];h=None
    for raw in b.splitlines():
        l=raw.strip()
        if not l: continue
        if l.upper().startswith("#EXT-X-STREAM-INF"):
            mm=re.search(r'RESOLUTION=\d+x(\d+)',l,re.I); h=mm.group(1) if mm else None; continue
        if l.startswith("#"): continue
        u=resolve(m,l); hh=h; h=None
        if hh and u not in [x[1] for x in out]: out.append((hh,u))
    return out
def maxh(b):
    hs=[int(x.group(1)) for x in re.finditer(r'RESOLUTION=\d+x(\d+)',b,re.I)]
    return str(max(hs)) if hs else None
def sim(slug,serial=1):
    book,_,_=recon.book_of(slug)
    if not book: return {"p":slug,"err":"nobook"}
    d,_=recon.descriptor(book,serial)
    if not d: return {"p":slug,"err":"nodesc"}
    seen=set(); alive=[]   # (isMaster, kindOrd, qLabel, quality)
    for ch in d.get("chain") or []:
        cands={}
        base=desc.clean(desc.dec(ch.get("enc"))) if ch.get("enc") else None
        if base: cands[base]=""
        for q,v in (ch.get("encByQuality") or {}).items():
            u=desc.clean(desc.dec(v))
            if u: cands[u]=q
        mk=next((k for k,v in cands.items() if v.lower()=="default"),None)
        murl=cands.pop(mk) if mk else None
        for u in [k for k in cands if is_expired(k)]: cands.pop(u)
        if murl and is_expired(murl): murl=None
        auto=murl
        fresh=[(u,q) for u,q in cands.items() if u not in seen]
        for u,_ in fresh: seen.add(u)
        elig = auto is not None and auto not in seen
        auto_body=None; auto_var=[]
        with ThreadPoolExecutor(16) as ex:
            fr=[ex.submit(probe,u) for u,_ in fresh]
            af=ex.submit(probe,auto) if elig else None
            res=[f.result() for f in fr]; ares=af.result() if af else None
        for (u,q),(k,body) in zip(fresh,res):
            if k is None: continue
            if body and "#EXT-X-STREAM-INF" in body:
                auto_body=body
                # Kotlin: isMaster = masterBody contains STREAM-INF (covers blank-q base)
                hh=maxh(body)
                alive.append((True, 0 if k=="VIDEO" else 1, q or "تلقائي",
                              qval(hh) if hh else qval(q)))
                continue
            alive.append((False, 0 if k=="VIDEO" else 1, q or "", qval(q)))
        if ares and ares[0] is not None and elig:
            k,body=ares; auto_var=[(q,u) for u,q in cands.items() if q]
            if body and "#EXT-X-STREAM-INF" in body: auto_body=body
            h = maxh(body) if body else None
            ism = bool(body and "#EXT-X-STREAM-INF" in body)
            alive.append((ism, 0 if k=="VIDEO" else 1, "تلقائي", qval(h) if ism and h else qval("تلقائي")))
        if auto_body and not auto_var and not sep_audio(auto_body):
            for h,u in variants(auto or "",auto_body)[:12]:
                if u not in seen:
                    seen.add(u); alive.append((False,1,h,qval(h)))
    # sortWith(compareBy({!isMaster}, {kind!=VIDEO}))  then player re-sorts by -quality (stable)
    alive.sort(key=lambda r:(not r[0], r[1]))
    player=sorted(alive,key=lambda r:-r[3])   # stable -> preserves emission among ties
    return {"p":slug,"rows":[{"master":r[0],"q":r[2],"quality":r[3]} for r in player]}
if __name__=="__main__":
    for s in sys.argv[1:]:
        print(json.dumps(sim(s),ensure_ascii=False),flush=True)
