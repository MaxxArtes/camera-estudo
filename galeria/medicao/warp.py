"""Mede o warp por pontos de controle (estilo 'afinar rosto' / 'esticar altura') antes de escrever Kotlin."""
import time, sys
import numpy as np
from PIL import Image

def remap(img, mapx, mapy):
    """Amostragem bilinear de img nas coordenadas (mapx,mapy)."""
    h, w = img.shape[:2]
    x0 = np.floor(mapx).astype(np.int32); y0 = np.floor(mapy).astype(np.int32)
    tx = (mapx - x0)[..., None]; ty = (mapy - y0)[..., None]
    x0 = np.clip(x0, 0, w - 1); y0 = np.clip(y0, 0, h - 1)
    x1 = np.clip(x0 + 1, 0, w - 1); y1 = np.clip(y0 + 1, 0, h - 1)
    a = img[y0, x0] * (1 - tx) + img[y0, x1] * tx
    b = img[y1, x0] * (1 - tx) + img[y1, x1] * tx
    return (a * (1 - ty) + b * ty)

def empurra(gx, gy, cx, cy, dx, dy, raio):
    """Deslocamento local tipo 'liquify': dentro do raio, o pixel puxa da origem deslocada."""
    d = np.sqrt((gx - cx) ** 2 + (gy - cy) ** 2)
    peso = np.clip(1 - d / raio, 0, 1) ** 2          # cai a zero na borda, suave
    return gx - dx * peso, gy - dy * peso

def mede(caminho, lado=None):
    im = Image.open(caminho).convert("RGB")
    if lado: im.thumbnail((lado, lado), Image.LANCZOS)
    a = np.asarray(im).astype(np.float32)
    h, w = a.shape[:2]
    gy, gx = np.mgrid[0:h, 0:w].astype(np.float32)
    t0 = time.time()
    # afinar rosto: dois pontos nas bochechas empurrando para dentro
    r = w * 0.22; forca = w * 0.020
    gx2, gy2 = empurra(gx, gy, w * 0.33, h * 0.52, forca, 0, r)
    gx2, gy2 = empurra(gx2, gy2, w * 0.67, h * 0.52, -forca, 0, r)
    saida = remap(a, gx2, gy2)
    ms = (time.time() - t0) * 1000
    return im.size, ms, np.clip(saida, 0, 255).astype(np.uint8), a.astype(np.uint8)

for f in ["fotos/pessoa1.jpg", "fotos/pessoa3.jpg"]:
    try:
        tam, ms, out, orig = mede(f)
        Image.fromarray(out).save("saida/warp_" + f.split("/")[-1])
        dif = np.abs(out.astype(np.int16) - orig.astype(np.int16)).mean()
        print(f"{f:22s} {tam[0]}x{tam[1]:<5d} warp {ms:7.0f} ms   diferenca media {dif:5.2f}")
    except Exception as e:
        print(f, "erro", type(e).__name__, e)
# custo no tamanho de exportacao
import numpy as _np
for lado in (1024, 2048, 4096):
    try:
        tam, ms, _, _ = mede("fotos/pessoa1.jpg", lado)
        print(f"  lado {lado:5d} -> {tam[0]}x{tam[1]:<5d} {ms:7.0f} ms")
    except Exception as e: print(lado, "erro", e)
