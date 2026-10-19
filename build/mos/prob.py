import time, urllib.request
UA="Mozilla/5.0"
REF="https://mosalsaly.com/"
urls = [
  "https://hwztakavideoto.dramaboxdb.com/fa24b6e4ee60e4288d0e7a7afd8118bf/6adb5402/51/8x0/80x9/809x3/80932000024/701439615_1/701439615.720p.narrowv3.mp4",
  "https://hwztakavideoto.dramaboxdb.com/f22adba64383058e905aef4e1502486b/6adb5402/51/8x0/80x9/809x3/80932000024/701439615_1/701439615.1080p.nav2.mp4",
]
for u in urls:
    t0=time.time()
    try:
        h={"User-Agent":UA,"Referer":REF,"Range":"bytes=0-65535"}
        r=urllib.request.urlopen(urllib.request.Request(u,headers=h),timeout=12)
        b=r.read(65536)
        print(r.status, r.headers.get('Content-Type'), len(b), time.time()-t0)
    except Exception as e:
        print("ERR", e, time.time()-t0)
