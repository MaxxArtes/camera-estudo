"""Protótipo medido: alinhamento MTB (Ward 2003), exposure fusion (Mertens 2007) e merge robusto de rajada.
Só numpy + Pillow, com as mesmas operações que o Kotlin vai fazer (pirâmides com kernel binomial 5, sem FFT)."""
import sys, time, numpy as np
from PIL import Image, ImageFilter
rng = np.random.default_rng(7)

# ---------- utilidades ----------
def blur5(a):
    """binomial [1,4,6,4,1]/16 separável com borda replicada; a: (H,W) ou (H,W,C)"""
    k = np.array([1, 4, 6, 4, 1], float) / 16
    p = np.pad(a, ((2, 2), (2, 2)) + ((0, 0),) * (a.ndim - 2), mode='edge')
    h = sum(k[i] * p[:, i:i + a.shape[1]] for i in range(5))
    return sum(k[i] * h[i:i + a.shape[0]] for i in range(5))

def down(a): return blur5(a)[::2, ::2]
def up(a, shape):
    z = np.zeros((a.shape[0] * 2, a.shape[1] * 2) + a.shape[2:], a.dtype)
    z[::2, ::2] = a
    return (4 * blur5(z))[:shape[0], :shape[1]]

def gauss_pyr(a, n):
    p = [a]
    for _ in range(n - 1): p.append(down(p[-1]))
    return p
def lap_pyr(a, n):
    g = gauss_pyr(a, n); return [g[i] - up(g[i + 1], g[i].shape) for i in range(n - 1)] + [g[-1]]
def collapse(l):
    a = l[-1]
    for i in range(len(l) - 2, -1, -1): a = l[i] + up(a, l[i].shape)
    return a

def gray(im): return (im[..., 0] * 54 + im[..., 1] * 183 + im[..., 2] * 19) / 256

def lapvar(g):
    l = -4 * g[1:-1, 1:-1] + g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:]
    return float(l.var())

def psnr(a, b):
    m = float(((a - b) ** 2).mean()); return 99.0 if m == 0 else 10 * np.log10(1 / m)

# ---------- MTB (Ward 2003) ----------
def mtb_align(ref, img, niveis=6, tol=4 / 255):
    """desloca img para casar com ref (translação inteira). Devolve (dx, dy) tal que img[y+dy, x+dx] ≈ ref[y, x]."""
    def bits(g):
        med = np.median(g); return g > med, np.abs(g - med) > tol
    pr = [gray(ref)]; pi = [gray(img)]
    for _ in range(niveis - 1): pr.append(down(pr[-1])); pi.append(down(pi[-1]))
    dx = dy = 0
    for n in range(niveis - 1, -1, -1):
        br, er = bits(pr[n]); bi, ei = bits(pi[n])
        dx *= 2; dy *= 2
        melhor = None
        for ox in (-1, 0, 1):
            for oy in (-1, 0, 1):
                sx, sy = dx + ox, dy + oy
                b2 = np.roll(np.roll(bi, -sy, 0), -sx, 1); e2 = np.roll(np.roll(ei, -sy, 0), -sx, 1)
                erro = int(((br ^ b2) & er & e2).sum())
                if melhor is None or erro < melhor[0]: melhor = (erro, sx, sy)
        _, dx, dy = melhor
    return dx, dy

def desloca(img, dx, dy):
    return np.roll(np.roll(img, -dy, 0), -dx, 1)

# ---------- Mertens 2007 ----------
def mertens(ims, niveis=6, wc=1.0, ws=1.0, we=1.0, sigma=0.2):
    pesos = []
    for im in ims:
        g = gray(im)
        lap = np.abs(-4 * g + np.roll(g, 1, 0) + np.roll(g, -1, 0) + np.roll(g, 1, 1) + np.roll(g, -1, 1))
        sat = im.std(axis=2)
        exp_ = np.exp(-((im - 0.5) ** 2) / (2 * sigma ** 2)).prod(axis=2)
        pesos.append((lap ** wc) * (sat ** ws) * (exp_ ** we) + 1e-12)
    soma = sum(pesos); pesos = [p / soma for p in pesos]
    acc = None
    for im, p in zip(ims, pesos):
        lp = lap_pyr(im, niveis); gp = gauss_pyr(p, niveis)
        termos = [l * g[..., None] for l, g in zip(lp, gp)]
        acc = termos if acc is None else [a + t for a, t in zip(acc, termos)]
    return np.clip(collapse(acc), 0, 1)

# ---------- merge robusto de rajada (espírito do HDR+, no domínio espacial) ----------
def burst_merge(quadros, ladrilho=64, busca=3, modo='robusto'):
    nit = [lapvar(gray(q)) for q in quadros]
    r = int(np.argmax(nit)); ref = quadros[r]
    gref = gray(ref)
    # ruído estimado no ref: MAD do resíduo de alta frequência
    acc = ref.copy(); wsum = np.ones(gref.shape)
    escolhido = np.full(gref.shape, r); tau = None
    for i, q in enumerate(quadros):
        if i == r: continue
        dx, dy = mtb_align(ref, q)
        q = desloca(q, dx, dy)
        if tau is None:
            d0 = gray(q) - gref; sigma = 1.4826 * np.median(np.abs(d0 - np.median(d0))) / np.sqrt(2)
            tau = 2.5 * sigma + 0.005
        if modo == 'robusto':
            d = np.abs(gray(q) - gref)
            w = np.exp(-(d / tau) ** 2)
            acc += q * w[..., None]; wsum += w
        elif modo == 'media':
            acc += q; wsum += 1
        elif modo == 'ladrilho':
            gq = gray(q); H, W = gref.shape
            for y in range(0, H, ladrilho):
                for x in range(0, W, ladrilho):
                    sl = (slice(y, y + ladrilho), slice(x, x + ladrilho))
                    if lapvar(gq[sl]) > lapvar(gray(acc[sl] / wsum[sl][..., None])):
                        acc[sl] = q[sl] * wsum[sl][..., None]; escolhido[sl] = i
    return np.clip(acc / wsum[..., None], 0, 1), r, tau

# ---------- rajadas sintéticas ----------
def carrega(f, lado=1000):
    im = Image.open(f).convert('RGB'); im.thumbnail((lado, lado)); return np.asarray(im, float) / 255

def sintetiza_rajada(limpa, n=5, desloc=10, rot=0.4, ruido=0.045, borrada=1):
    quadros = []; verdade = []
    H, W = limpa.shape[:2]
    for i in range(n):
        dx, dy = rng.integers(-desloc, desloc + 1, 2); ang = rng.uniform(-rot, rot) if i else 0.0
        im = Image.fromarray((limpa * 255).astype(np.uint8))
        im = im.rotate(ang, resample=Image.BILINEAR, translate=(int(dx), int(dy)))
        if i == borrada: im = im.filter(ImageFilter.Kernel((5, 5), [0,0,0,0,0, 0,0,0,0,0, 1,1,1,1,1, 0,0,0,0,0, 0,0,0,0,0], scale=5))   # tremor horizontal 5 px
        a = np.asarray(im, float) / 255
        a = np.clip(a + rng.normal(0, ruido, a.shape) * np.sqrt(np.clip(a, 0.05, 1) / 0.5), 0, 1)   # ruído maior no claro (Poisson-ish)
        a = np.round(a * 255) / 255
        quadros.append(a); verdade.append((int(dx), int(dy), ang))
    return quadros, verdade

def sintetiza_bracket(limpa, evs=(-2, 0, 2), ruido=0.02):
    L = limpa ** 2.2 * 1.6      # "radiância" com faixa acima de 1 para ter estouro
    saida = []
    for ev in evs:
        e = np.clip(L * 2.0 ** ev, 0, 1) ** (1 / 2.2)
        e = np.clip(e + rng.normal(0, ruido, e.shape) * (1.5 - e), 0, 1)
        saida.append(np.round(e * 255) / 255)
    return saida

def recorte_comum(a, m=24): return a[m:-m, m:-m]

if __name__ == '__main__':
    saida = sys.argv[1] if len(sys.argv) > 1 else '.'
    for nome, f in [('texto', '../tela/crop_bruto.jpg'), ('casal', '/opt/cha-de-panela/static/casal.jpg')]:
        limpa = carrega(f)
        print(f'\n=== {nome} {limpa.shape[1]}x{limpa.shape[0]} ===')
        quadros, verdade = sintetiza_rajada(limpa)
        t = time.time(); dxs = [mtb_align(quadros[0], q) for q in quadros[1:]]; tm = time.time() - t
        print('MTB: verdade (dx,dy,rot) ->', [(v[0], v[1], round(v[2], 2)) for v in verdade[1:]])
        print(f'     MTB {tm / 4 * 1000:.0f} ms/par')
        dx0, dy0, _ = verdade[0]
        print('     achado   (dx,dy)     ->', [tuple(d) for d in dxs], ' esperado ->', [(v[0] - dx0, v[1] - dy0) for v in verdade[1:]])
        def no_ref(a, r): dxr, dyr, _ = verdade[r]; return recorte_comum(desloca(a, dxr, dyr))
        L = recorte_comum(limpa)
        base = quadros[0]
        print(f'1 quadro:      PSNR {psnr(no_ref(base, 0), L):.2f} dB  nitidez {lapvar(gray(base)):.5f}  (limpa {lapvar(gray(limpa)):.5f})')
        for modo in ('robusto', 'media', 'ladrilho'):
            t = time.time(); fus, r, tau = burst_merge(quadros, modo=modo); tt = time.time() - t
            print(f'{modo:9s} ref={r} tau={tau:.3f}: PSNR {psnr(no_ref(fus, r), L):.2f} dB  nitidez {lapvar(gray(fus)):.5f}  {tt:.1f}s')
            Image.fromarray((fus * 255).astype(np.uint8)).save(f'{saida}/{nome}_rajada_{modo}.jpg', quality=92)
        Image.fromarray((base * 255).astype(np.uint8)).save(f'{saida}/{nome}_rajada_1quadro.jpg', quality=92)

        brk = sintetiza_bracket(limpa)
        t = time.time(); hdr = mertens(brk); th = time.time() - t
        def estouro(a): return float(((a >= 254 / 255) | (a <= 1 / 255)).mean() * 100)
        print(f'HDR Mertens {th:.1f}s: estouro% ev-2 {estouro(brk[0]):.1f} ev0 {estouro(brk[1]):.1f} ev+2 {estouro(brk[2]):.1f} -> fundido {estouro(hdr):.1f}; '
              f'nitidez ev0 {lapvar(gray(brk[1])):.5f} fundido {lapvar(gray(hdr)):.5f}')
        for i, b in enumerate(brk): Image.fromarray((b * 255).astype(np.uint8)).save(f'{saida}/{nome}_ev{i}.jpg', quality=92)
        Image.fromarray((hdr * 255).astype(np.uint8)).save(f'{saida}/{nome}_hdr.jpg', quality=92)
