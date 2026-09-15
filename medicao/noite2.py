import numpy as np
from PIL import Image
import proto as P
from proto import *
from noite import mertens2

def ruido_lisos(a, limpa, bloco=32):
    """std do passa-alta do resultado, só nos blocos onde a imagem limpa é lisa (std < 0.01)."""
    g = gray(a); gl = gray(limpa); hp = g - blur5(g); vals = []
    for y in range(0, g.shape[0] - bloco, bloco):
        for x in range(0, g.shape[1] - bloco, bloco):
            if gl[y:y + bloco, x:x + bloco].std() < 0.01: vals.append(hp[y:y + bloco, x:x + bloco].std())
    return float(np.median(vals)) if vals else float('nan'), len(vals)

def mertens3(ims, niveis=6, penal=True, suaviza=True, sigma_e=0.2):
    pesos = []; sigs = []
    for im in ims:
        g = gray(im); gs = blur5(g) if suaviza else g
        lap = -4 * gs + np.roll(gs, 1, 0) + np.roll(gs, -1, 0) + np.roll(gs, 1, 1) + np.roll(gs, -1, 1)
        lap0 = -4 * g + np.roll(g, 1, 0) + np.roll(g, -1, 0) + np.roll(g, 1, 1) + np.roll(g, -1, 1)
        sigs.append(1.4826 * np.median(np.abs(lap0 - np.median(lap0))))
        sat = im.std(axis=2); exp_ = np.exp(-((im - 0.5) ** 2) / (2 * sigma_e ** 2)).prod(axis=2)
        pesos.append(np.abs(lap) * sat * exp_ + 1e-12)
    if penal:
        smin = min(sigs); pesos = [p * (smin / s) ** 2 for p, s in zip(pesos, sigs)]
    soma = sum(pesos); pesos = [p / soma for p in pesos]
    acc = None
    for im, p in zip(ims, pesos):
        lp = lap_pyr(im, niveis); gp = gauss_pyr(p, niveis)
        termos = [l * g[..., None] for l, g in zip(lp, gp)]
        acc = termos if acc is None else [a + t for a, t in zip(acc, termos)]
    return np.clip(collapse(acc), 0, 1), sigs

def estouro(a): return float(((a >= 254 / 255) | (a <= 1 / 255)).mean() * 100)

P.rng = np.random.default_rng(3)
# cena noturna com um céu liso: metade de cima da imagem vira gradiente escuro liso
limpa = carrega('/opt/cha-de-panela/static/casal.jpg', 1000) * 0.35
H = limpa.shape[0]; ceu = np.linspace(0.02, 0.10, H // 2)[:, None, None] * np.ones((1, limpa.shape[1], 3)); limpa[:H // 2] = ceu
L = limpa ** 2.2 * 1.6
def expo(ev, ru):
    e = np.clip(L * 2.0 ** ev, 0, 1) ** (1 / 2.2)
    return np.round(np.clip(e + P.rng.normal(0, ru, e.shape) * (1.5 - e), 0, 1) * 255) / 255
brk = [expo(-2, 0.05), expo(0, 0.05), expo(2, 0.09)]      # à noite o +2 EV sobe o ISO: mais ruído
print('ruído nos lisos (std do passa-alta) | estouro | nitidez')
for nome, a in (('ev0', brk[1]), ('ev+2', brk[2])):
    r, nb = ruido_lisos(a, limpa); print(f'{nome:26s} {r:.4f} ({nb} blocos) | {estouro(a):.1f}% | {lapvar(gray(a)):.5f}')
h1 = mertens(brk, niveis=6); r, _ = ruido_lisos(h1, limpa); print(f'{"mertens original":26s} {r:.4f} | {estouro(h1):.1f}% | {lapvar(gray(h1)):.5f}')
h2, sigs = mertens3(brk, penal=True, suaviza=False); r, _ = ruido_lisos(h2, limpa); print(f'{"penaliza quadro ruidoso":26s} {r:.4f} | {estouro(h2):.1f}% | {lapvar(gray(h2)):.5f}  sigmas {[round(s, 4) for s in sigs]}')
h3, _ = mertens3(brk, penal=True, suaviza=True); r, _ = ruido_lisos(h3, limpa); print(f'{"penaliza + contraste suave":26s} {r:.4f} | {estouro(h3):.1f}% | {lapvar(gray(h3)):.5f}')
h4, _ = mertens3(brk, penal=False, suaviza=True); r, _ = ruido_lisos(h4, limpa); print(f'{"só contraste suave":26s} {r:.4f} | {estouro(h4):.1f}% | {lapvar(gray(h4)):.5f}')
# alternativa: 4 quadros ev0 fundidos (rajada) + levantar sombras (gama 0,7)
P.rng = np.random.default_rng(5); q = [expo(0, 0.05) for _ in range(4)]
from exp import merge_param
fus, _ = merge_param(q, 2.5, 2); lev = np.clip(fus, 0, 1) ** 0.7
r, _ = ruido_lisos(lev, limpa); print(f'{"rajada 4x ev0 + gama 0,7":26s} {r:.4f} | {estouro(lev):.1f}% | {lapvar(gray(lev)):.5f}')
m = Image.new('RGB', (1000 * 4 + 30, H), 'white')
for i, a in enumerate((brk[1], h1, h3, lev)): m.paste(Image.fromarray((a * 255).astype(np.uint8)), (i * 1010, 0))
m.save('noite_compara2.jpg', quality=88)
