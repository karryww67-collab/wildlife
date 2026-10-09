import time
from ultralytics import YOLO

W = "/models/test-coco-yolo11n/best.pt"
IMGS = ["/tmp/tests/deer.jpg", "/tmp/tests/elephant.jpg", "/tmp/tests/tiger.jpg"]

m = YOLO(W)
print("model loaded")

t0 = time.perf_counter()
m.predict([IMGS[0]], verbose=False, imgsz=640)
print("COLD batch1 = %.1f ms" % ((time.perf_counter() - t0) * 1000))

t0 = time.perf_counter()
m.predict([IMGS[0]], verbose=False, imgsz=640)
print("WARM batch1 = %.1f ms" % ((time.perf_counter() - t0) * 1000))

t0 = time.perf_counter()
m.predict(IMGS, verbose=False, imgsz=640)
t1 = time.perf_counter()
print("WARM batch3 = %.1f ms total / %.1f ms per img" % ((t1 - t0) * 1000, (t1 - t0) * 1000 / 3))