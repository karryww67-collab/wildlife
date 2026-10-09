import json
from ultralytics import YOLO

W = "/models/wildlife-v1.0/best.pt"
IMGS = ["/tmp/tests/deer.jpg", "/tmp/tests/elephant.jpg", "/tmp/tests/tiger.jpg"]
KW = dict(imgsz=640, conf=0.25, iou=0.5, max_det=300, verbose=False)

m = YOLO(W)
names = m.names

def dets(res):
    out = []
    b = getattr(res, "boxes", None)
    if b is None:
        return out
    for i in range(len(b)):
        cid = int(b.cls[i].item())
        out.append({"cls": names.get(cid, str(cid)),
                    "conf": round(float(b.conf[i].item()), 4),
                    "xyxy": [round(float(v), 1) for v in b.xyxy[i].tolist()]})
    return out

single = {}
for p in IMGS:
    single[p] = dets(m.predict([p], **KW)[0])

grouped = {}
for p, r in zip(IMGS, m.predict(IMGS, **KW)):
    grouped[p] = dets(r)

print("=== batch=1 vs batch=3 ===")
mc = 0.0
mb = 0.0
for p in IMGS:
    a = single[p]; c = grouped[p]
    ca = {d["cls"]: d["conf"] for d in a}
    cc = {d["cls"]: d["conf"] for d in c}
    print("")
    print("img %s" % p.split("/")[-1])
    print("  b1 %s" % (json.dumps(ca, ensure_ascii=False) if ca else "(none)"))
    print("  b3 %s" % (json.dumps(cc, ensure_ascii=False) if cc else "(none)"))
    print("  boxes b1=%d b3=%d" % (len(a), len(c)))
    for k in set(ca) & set(cc):
        d = abs(ca[k] - cc[k]); mc = max(mc, d)
        print("    %s conf diff %.4f" % (k, d))
    if set(ca) != set(cc):
        print("    ** class set differs: only_b1=%s only_b3=%s" % (sorted(set(ca) - set(cc)), sorted(set(cc) - set(ca))))
    for d1 in a:
        for d2 in c:
            if d1["cls"] == d2["cls"]:
                for u, v in zip(d1["xyxy"], d2["xyxy"]):
                    mb = max(mb, abs(u - v))

print("")
print("max conf diff = %.4f" % mc)
print("max box diff  = %.1f px" % mb)