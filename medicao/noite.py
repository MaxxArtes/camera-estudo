"""Cenário noturno: bracket com ruído forte na exposição escura. Compara o Mertens original com a versão
'ciente de ruído' (contraste = max(0, |laplaciano| − k·σ), σ por MAD do laplaciano de cada exposição; viés para o ev0;
esticamento final mais brando)."""
import numpy as np, time
from PIL import Image
import proto as P
from proto import *

def mertens2(ims, niveis=6, k=2.0, vies0=1.3, sigma_e=0.2):
    pesos = []; meio = len(ims) // 2
    for i, im in enumerate(ims):
        g = gray(im)
        lap = -4 * g + np.roll(g, 1, 0) + np.roll(g, -1, 0) + np.roll(g, 1, 1) + np.roll(g, -1, 1)
        sig = 1.4826 * np.median(np.abs(lap - np.median(lap))) / np.sqrt(20)   # var do laplaciano = 20 σ²
        contraste = np.maximum(0, np.abs(lap) - k * sig * np.sqrt(20))
        sat = im.std(axis=2)
        exp_ = np.exp(-((im - 0.5) ** 2) / (2 * sigma_e ** 2)).prod(axis=2)
        w = contraste * sat * exp_ + 1e-12
        if i == meio: w = w * vies0
        pesos.append(w)
    soma = sum(pesos); pesos = [p / soma for p in pesos]
    acc = None
    for im, p in zip(ims, pesos):
        lp = lap_pyr(im, niveis); gp = gauss_pyr(p, niveis)
        termos = [l * g[..., None] for l, g in zip(lp, gp)]
        acc = termos if acc is None else [a + t for a, t in zip(acc, termos)]
    return np.clip(collapse(acc), 0, 1)

def ruido_plano(a, box):
    y0, y1, x0, x1 = box; return float(gray(a[y0:y1, x0:x1]).std())

if __name__ == "__main__":
    P.rng = np.random.default_rng(3)
    limpa = carrega('/opt/cha-de-panela/static/casal.jpg', 1000) * 0.35     # cena escura
    # bracket noturno: ruído maior quanto mais escura a exposição (o ISP sobe o ganho)
    L = limpa ** 2.2 * 1.6; brk = []
    for ev, ru in zip((-2, 0, 2), (0.09, 0.05, 0.03)):
        e = np.clip(L * 2.0 ** ev, 0, 1) ** (1 / 2.2)
        e = np.clip(e + P.rng.normal(0, ru, e.shape) * (1.5 - e), 0, 1); brk.append(np.round(e * 255) / 255)
    box = (5, 80, 5, 80)   # canto: fundo escuro liso na foto do casal
    print(f'ruído (std) no fundo liso: ev-2 {ruido_plano(brk[0], box):.4f}  ev0 {ruido_plano(brk[1], box):.4f}  ev+2 {ruido_plano(brk[2], box):.4f}')
    def estouro(a): return float(((a >= 254 / 255) | (a <= 1 / 255)).mean() * 100)
    h1 = mertens(brk, niveis=6)
    print(f'mertens original : ruído {ruido_plano(h1, box):.4f}  nitidez {lapvar(gray(h1)):.5f}  estouro {estouro(h1):.1f}%')
    for k in (1.0, 2.0, 3.0):
        h2 = mertens2(brk, k=k)
        print(f'ciente ruído k={k}: ruído {ruido_plano(h2, box):.4f}  nitidez {lapvar(gray(h2)):.5f}  estouro {estouro(h2):.1f}%')
    h2 = mertens2(brk, k=2.0)
    m = Image.new('RGB', (1000 * 3 + 20, brk[1].shape[0]), 'white')
    for i, a in enumerate((brk[1], h1, h2)): m.paste(Image.fromarray((a * 255).astype(np.uint8)), (i * 1010, 0))
    m.save('noite_compara.jpg', quality=88)
