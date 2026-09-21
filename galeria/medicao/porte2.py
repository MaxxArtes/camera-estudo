import sys, os, math, numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
import porte_editor as pe
from ai_edge_litert.interpreter import Interpreter
SP=os.path.dirname(os.path.abspath(__file__))

def roda(modelo, img, lado):
    it=Interpreter(model_path=modelo, num_threads=2); it.allocate_tensors()
    i=it.get_input_details()[0]; o=it.get_output_details()[0]
    x=np.asarray(img.resize((lado,lado), Image.BILINEAR)).astype(np.float32)/255.0
    it.set_tensor(i["index"], x[None,...]); it.invoke(); out=it.get_tensor(o["index"])[0]
    return out, o["shape"]

def mascara_deeplab(img):
    out,shape=roda(f"{SP}/modelos/deeplab_v3.tflite", img, 257)
    if out.ndim==3 and out.shape[-1]>=21: return out[...,15]/np.maximum(out.sum(-1),1e-6) if out.max()>1.5 else out[...,15]   # pessoa = classe 15 (VOC)
    return out[...,0]

def mascara_selfie_geral(img):
    out,shape=roda(f"{SP}/modelos/selfie_segmenter.tflite", img, 256)
    return out[...,0] if out.ndim==3 else out

def smoothstep(v,a,b):
    t=np.clip((v-a)/(b-a),0,1); return t*t*(3-2*t)

def dilata(b, r):  # máx em caixa por passes separáveis (bool)
    a=b.astype(np.float32); a=pe.caixaF(a,r); return a>1e-6
def erode(b, r):
    return ~dilata(~b, r)

def pos_processa(px, bruta, r_fecha, min_frac=0.003, eps=1e-3, r_guia=None):
    """afia (smoothstep) → fecha buracos internos → mantém blocos >= min_frac → filtro guiado com eps pequeno"""
    h,w=px.shape[:2]
    m=np.asarray(Image.fromarray((bruta*255).astype(np.uint8)).resize((w,h), Image.BILINEAR)).astype(np.float32)/255.0
    m=smoothstep(m,0.35,0.65)
    b=m>0.5
    if r_fecha>0: b=erode(dilata(b,r_fecha),r_fecha)            # fechamento: une pedaços e fecha frestas
    # preenche buracos totalmente cercados por pessoa (flood do fundo a partir da borda)
    from collections import deque
    fora=np.zeros_like(b); q=deque()
    for y in range(h):
        for x in (0,w-1):
            if not b[y,x] and not fora[y,x]: fora[y,x]=True; q.append((y,x))
    for x in range(w):
        for y in (0,h-1):
            if not b[y,x] and not fora[y,x]: fora[y,x]=True; q.append((y,x))
    while q:
        y,x=q.popleft()
        for dy,dx in ((-1,0),(1,0),(0,-1),(0,1)):
            yy,xx=y+dy,x+dx
            if 0<=yy<h and 0<=xx<w and not b[yy,xx] and not fora[yy,xx]: fora[yy,xx]=True; q.append((yy,xx))
    b=b|(~fora)                                                  # buraco interno = pessoa
    # componentes >= min_frac (não só o maior)
    rot=np.zeros((h,w),np.int32); k=0; tam={}
    for y in range(h):
        for x in range(w):
            if not b[y,x] or rot[y,x]: continue
            k+=1; n=0; q=deque([(y,x)]); rot[y,x]=k
            while q:
                cy,cx=q.popleft(); n+=1
                for dy,dx in ((-1,0),(1,0),(0,-1),(0,1)):
                    yy,xx=cy+dy,cx+dx
                    if 0<=yy<h and 0<=xx<w and b[yy,xx] and not rot[yy,xx]: rot[yy,xx]=k; q.append((yy,xx))
            tam[k]=n
    keep=np.zeros((h,w),bool)
    for kk,n in tam.items():
        if n>=min_frac*h*w: keep|=(rot==kk)
    m=keep.astype(np.float32)
    # filtro guiado (guia = luminância) com eps pequeno: transfere as bordas da foto para a máscara binária
    r=r_guia or max(3,min(w,h)//120)
    guia=(px[...,0]*0.299+px[...,1]*0.587+px[...,2]*0.114)/255.0
    mI=pe.caixaF(guia,r); mP=pe.caixaF(m,r); cI=pe.caixaF(guia*guia,r); cIP=pe.caixaF(guia*m,r)
    varI=cI-mI*mI; cov=cIP-mI*mP; a=cov/(varI+eps); bb=mP-a*mI
    q=np.clip(pe.caixaF(a,r)*guia+pe.caixaF(bb,r),0,1)
    return q, len(tam), int(sum(1 for n in tam.values() if n>=min_frac*h*w))

def incerta(m): return float(((m>0.15)&(m<0.85)).mean())

def experimento(caminho, lado=1024):
    img=Image.open(caminho).convert("RGB"); img.thumbnail((lado,lado)); px=np.asarray(img).astype(np.float32); h,w=px.shape[:2]
    cats=pe.segmentar(img); bruta=1-cats[...,0]
    antiga,_=pe.limpa_mascara(bruta); antiga=pe.plena(px,antiga,guiado=False)
    nova,ncomp,nkeep=pos_processa(px,bruta,r_fecha=max(2,min(w,h)//60))
    linha=[px.astype(np.uint8), pe.overlay(px,antiga), pe.overlay(px,nova)]
    msg=f"{os.path.basename(caminho)} {w}x{h}: incerta antiga={incerta(antiga)*100:.1f}% pós={incerta(nova)*100:.1f}% blocos={ncomp} mantidos={nkeep}"
    if os.path.exists(f"{SP}/modelos/deeplab_v3.tflite"):
        try:
            dl=mascara_deeplab(img); dl_pl,_,_=pos_processa(px,dl,r_fecha=max(2,min(w,h)//60))
            linha.append(pe.overlay(px,dl_pl)); msg+=f" | deeplab pessoa={(dl>0.5).mean()*100:.1f}% incerta={incerta(dl_pl)*100:.1f}%"
        except Exception as e: msg+=f" | deeplab falhou: {type(e).__name__}: {str(e)[:80]}"
    print(msg)
    return Image.fromarray(np.concatenate(linha,axis=1)), nova, (dl_pl if 'dl_pl' in dir() else None)

if __name__=="__main__":
    paineis=[]
    for c in sys.argv[1:]:
        p,_,_=experimento(c); W=1400; p=p.resize((W,int(p.height*W/p.width)),Image.BILINEAR); paineis.append(p)
    H=sum(p.height for p in paineis); out=Image.new("RGB",(1400,H)); y=0
    for p in paineis: out.paste(p,(0,y)); y+=p.height
    out.save(f"{SP}/saida/painel2.jpg",quality=88); print("painel2:", out.size)
