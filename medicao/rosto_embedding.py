"""Separação do embedding facial: cosseno entre fotos do dono vs dono x outras pessoas.
Detecção/alinhamento com Haar (OpenCV) só para o teste; no app é o ML Kit."""
import glob, numpy as np, cv2, itertools
from tflite_runtime.interpreter import Interpreter
it = Interpreter('modelo.tflite'); it.allocate_tensors()
inp = it.get_input_details()[0]; out = it.get_output_details()[0]
print('entrada', inp['shape'], inp['dtype'], 'saida', out['shape'])
face = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
eyes = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_eye.xml')
def embed(img, norm):
    x = cv2.resize(img, (112, 112)).astype(np.float32)
    x = (x - 127.5) / 128.0 if norm == 'pm1' else x / 255.0
    it.set_tensor(inp['index'], x[None]); it.invoke(); v = it.get_tensor(out['index'])[0].astype(np.float64)
    return v / (np.linalg.norm(v) + 1e-9)
def rostos(path):
    im = cv2.imread(path); h, w = im.shape[:2]; esc = 800 / max(h, w); im = cv2.resize(im, (int(w * esc), int(h * esc)))
    g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY); dets = face.detectMultiScale(g, 1.2, 5, minSize=(60, 60))
    saida = []
    for (x, y, fw, fh) in dets:
        # alinha pelos olhos quando achar os dois
        roi = g[y:y + fh, x:x + fw]; es = eyes.detectMultiScale(roi, 1.1, 5)
        crop = im[y:y + fh, x:x + fw]
        if len(es) >= 2:
            es = sorted(es, key=lambda e: e[0])[:2]; (x1, y1, w1, h1), (x2, y2, w2, h2) = es
            ang = np.degrees(np.arctan2((y2 + h2 / 2) - (y1 + h1 / 2), (x2 + w2 / 2) - (x1 + w1 / 2)))
            M = cv2.getRotationMatrix2D((fw / 2, fh / 2), ang, 1.0); crop = cv2.warpAffine(crop, M, (fw, fh))
        m = int(fw * 0.1); crop = crop[m:fh - m, m:fw - m]
        saida.append(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
    return saida
for norm in ('pm1', '01'):
    vet = {}
    for p in sorted(glob.glob('fotos/*.jpg')):
        rs = rostos(p)
        for i, r in enumerate(rs): vet[f'{p.split("/")[-1][:-4]}#{i}'] = embed(r, norm)
    dono = [k for k in vet if k.startswith('dono')]; outros = [k for k in vet if not k.startswith('dono')]
    intra = [float(vet[a] @ vet[b]) for a, b in itertools.combinations(dono, 2)]
    inter = [float(vet[a] @ vet[b]) for a in dono for b in outros]
    print(f'norm {norm}: rostos {len(vet)} (dono {len(dono)}, outros {len(outros)}) | dono x dono: min {min(intra):.3f} med {np.median(intra):.3f} | dono x outros: max {max(inter) if inter else float("nan"):.3f} med {np.median(inter) if inter else float("nan"):.3f}')
    if outros: print('  outros x outros:', [round(float(vet[a] @ vet[b]), 3) for a, b in itertools.combinations(outros, 2)])

# quem são os pontos fora: similaridade de cada foto do dono com o centroide dos demais
vet = {}
for p in sorted(glob.glob('fotos/*.jpg')):
    for i, r in enumerate(rostos(p)): vet[f'{p.split("/")[-1][:-4]}#{i}'] = embed(r, 'pm1')
dono = [k for k in vet if k.startswith('dono')]
for k in dono:
    resto = [vet[j] for j in dono if j != k]; c = np.mean(resto, 0); c /= np.linalg.norm(c)
    print(f'  {k:28s} sim ao centroide {float(vet[k] @ c):.3f}')
for k in vet:
    if not k.startswith('dono'):
        c = np.mean([vet[j] for j in dono], 0); c /= np.linalg.norm(c); print(f'  {k:28s} (outro) sim ao centroide do dono {float(vet[k] @ c):.3f}')
