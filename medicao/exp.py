import time, numpy as np
from PIL import Image
import proto as P
from proto import *

def merge_param(quadros, fator=2.5, refino=0):
    nit = [lapvar(gray(q)) for q in quadros]; r = int(np.argmax(nit)); ref = quadros[r]; gref = gray(ref)
    acc = ref.copy(); wsum = np.ones(gref.shape); tau = None
    for i, q in enumerate(quadros):
        if i == r: continue
        dx, dy = mtb_align(ref, q); q = desloca(q, dx, dy)
        if refino:
            gq = gray(q); H, W = gref.shape; T = 128; q2 = q.copy()
            for y in range(0, H - T + 1, T):
                for x in range(0, W - T + 1, T):
                    melhor = None
                    for oy in range(-refino, refino + 1):
                        for ox in range(-refino, refino + 1):
                            ys, xs = y + oy, x + ox
                            if ys < 0 or xs < 0 or ys + T > H or xs + T > W: continue
                            sad = np.abs(gq[ys:ys + T, xs:xs + T] - gref[y:y + T, x:x + T]).sum()
                            if melhor is None or sad < melhor[0]: melhor = (sad, oy, ox)
                    _, oy, ox = melhor; q2[y:y + T, x:x + T] = q[y + oy:y + oy + T, x + ox:x + ox + T]
            q = q2
        if tau is None:
            d0 = gray(q) - gref; sigma = 1.4826 * np.median(np.abs(d0 - np.median(d0))) / np.sqrt(2); tau = fator * sigma + 0.005
        d = np.abs(gray(q) - gref); w = np.exp(-(d / tau) ** 2); acc += q * w[..., None]; wsum += w
    return np.clip(acc / wsum[..., None], 0, 1), r

for nome, f in [('texto', '../tela/crop_bruto.jpg'), ('casal', '/opt/cha-de-panela/static/casal.jpg')]:
    P.rng = np.random.default_rng(7); limpa = carrega(f); quadros, verdade = sintetiza_rajada(limpa)
    def no_ref(a, r): dxr, dyr, _ = verdade[r]; return recorte_comum(desloca(a, dxr, dyr))
    L = recorte_comum(limpa)
    print(f'== {nome}: 1 quadro {psnr(no_ref(quadros[0], 0), L):.2f} dB')
    for fator in (1.5, 2.5, 4.0):
        fus, r = merge_param(quadros, fator); print(f'  tau {fator}x: {psnr(no_ref(fus, r), L):.2f} dB nitidez {lapvar(gray(fus)):.5f}')
    for refino in (1, 2):
        t = time.time(); fus, r = merge_param(quadros, 2.5, refino); print(f'  refino ±{refino} px/128: {psnr(no_ref(fus, r), L):.2f} dB nitidez {lapvar(gray(fus)):.5f} {time.time() - t:.1f}s')
    # rajada sem rotação (só translação): teto do método
    P.rng = np.random.default_rng(7); q2, v2 = sintetiza_rajada(limpa, rot=0.0)
    def no_ref2(a, r): dxr, dyr, _ = v2[r]; return recorte_comum(desloca(a, dxr, dyr))
    fus, r = merge_param(q2, 2.5); print(f'  sem rotação: {psnr(no_ref2(fus, r), L):.2f} dB')
    brk = sintetiza_bracket(limpa)
    def estouro(a): return float(((a >= 254 / 255) | (a <= 1 / 255)).mean() * 100)
    for niv in (4, 6, 8):
        t = time.time(); hdr = mertens(brk, niveis=niv); print(f'  mertens {niv} níveis: estouro {estouro(hdr):.1f}% nitidez {lapvar(gray(hdr)):.5f} {time.time() - t:.1f}s')
    t = time.time(); hdr = mertens(brk, niveis=6, ws=0.0); print(f'  mertens sem saturação: estouro {estouro(hdr):.1f}% nitidez {lapvar(gray(hdr)):.5f} {time.time() - t:.1f}s')
    Image.fromarray((hdr * 255).astype(np.uint8)).save(f'{nome}_hdr_semsat.jpg', quality=92)
