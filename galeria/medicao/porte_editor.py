"""Porte fiel (numpy) dos motores do editor da Galeria Estudo, para medir sobre fotos reais na bancada.
Espelha Fundo.kt (máscara plena + filtro guiado + desfoque em disco normalizado) e Tom.kt (sombras por separação)."""
import sys, os, math, numpy as np
from PIL import Image
from collections import deque
from ai_edge_litert.interpreter import Interpreter

MODELO="/opt/camera-estudo/galeria/src/main/assets/selfie_multiclass_256x256.tflite"
_interp=None
def modelo():
    global _interp
    if _interp is None:
        _interp=Interpreter(model_path=MODELO, num_threads=2); _interp.allocate_tensors()
    return _interp

def segmentar(img):  # img: PIL RGB -> cats (256,256,6)
    it=modelo(); i=it.get_input_details()[0]; o=it.get_output_details()[0]
    peq=np.asarray(img.resize((256,256), Image.BILINEAR)).astype(np.float32)/255.0
    it.set_tensor(i["index"], peq[None,...]); it.invoke()
    out=it.get_tensor(o["index"])[0]
    return out.reshape(256,256,-1)

def suave(v,a,b):
    t=np.clip((v-a)/(b-a),0,1); return t*t*(3-2*t)

def limpa_mascara(m):  # maior bloco conectado >0.5 (4-viz), como Fundo.limpaMascara
    h,w=m.shape; rot=np.zeros((h,w),np.int32); k=0; tam=[]
    bin_=m>0.5
    for y in range(h):
        for x in range(w):
            if not bin_[y,x] or rot[y,x]: continue
            k+=1; n=0; q=deque([(y,x)]); rot[y,x]=k
            while q:
                cy,cx=q.popleft(); n+=1
                for dy,dx in ((-1,0),(1,0),(0,-1),(0,1)):
                    yy,xx=cy+dy,cx+dx
                    if 0<=yy<h and 0<=xx<w and bin_[yy,xx] and not rot[yy,xx]: rot[yy,xx]=k; q.append((yy,xx))
            tam.append(n)
    if len(tam)<=1: return m,0
    maior=int(np.argmax(tam))+1
    m=m.copy(); m[(rot!=0)&(rot!=maior)]=0
    return m,len(tam)-1

def caixaF(a,r):  # média em caixa separável com janela recortada nas bordas (igual ao Kotlin)
    h,w=a.shape
    def passa(v,axis):
        n=v.shape[axis]; c=np.cumsum(v,axis=axis); c=np.concatenate([np.zeros_like(np.take(c,[0],axis=axis)),c],axis=axis)
        idx=np.arange(n); lo=np.clip(idx-r,0,n); hi=np.clip(idx+r+1,0,n)
        s=np.take(c,hi,axis=axis)-np.take(c,lo,axis=axis); cnt=(hi-lo).astype(np.float32)
        return s/(cnt if axis==1 else cnt[:,None])
    return passa(passa(a,1),0)

def plena(px, bruta, guiado=True, tracos=()):
    h,w=px.shape[:2]
    m=np.asarray(Image.fromarray((bruta*255).astype(np.uint8)).resize((w,h), Image.BILINEAR)).astype(np.float32)/255.0
    if guiado:
        guia=(px[...,0]*0.299+px[...,1]*0.587+px[...,2]*0.114)/255.0
        r=max(3,min(w,h)//100); eps=0.02
        mI=caixaF(guia,r); mP=caixaF(m,r); cI=caixaF(guia*guia,r); cIP=caixaF(guia*m,r)
        varI=cI-mI*mI; cov=cIP-mI*mP; a=cov/(varI+eps); b=mP-a*mI
        m=np.clip(caixaF(a,r)*guia+caixaF(b,r),0,1)
    return m

_lin=np.array([ (c/255)/12.92 if c/255<=0.04045 else ((c/255+0.055)/1.055)**2.4 for c in range(256)],np.float32)
def srgb(l):
    l=np.clip(l,0,1); return np.where(l<=0.0031308, l*12.92, 1.055*np.power(l,1/2.4)-0.055)

def disco(a,r):  # média em disco por prefixos de linha, só o que cabe (igual ao Kotlin)
    h,w=a.shape; pref=np.concatenate([np.zeros((h,1),np.float32),np.cumsum(a,axis=1)],axis=1)
    s=np.zeros((h,w),np.float32); cnt=np.zeros((h,w),np.float32); xs=np.arange(w)
    for dy in range(-r,r+1):
        dx=int(math.sqrt(r*r-dy*dy)); ys=np.arange(h)+dy; ok=(ys>=0)&(ys<h); ys=np.clip(ys,0,h-1)
        x0=np.clip(xs-dx,0,w-1); x1=np.clip(xs+dx,0,w-1)
        row=pref[ys][:,x1+1]-pref[ys][:,x0]
        s+=row*ok[:,None]; cnt+=((x1-x0+1)[None,:]*ok[:,None]).astype(np.float32)
    return np.where(cnt>0,s/np.maximum(cnt,1),0)

def desfocar(px, pl, intensidade=60):
    h,w=px.shape[:2]; escF=min(1.0,600/max(w,h)); fw=max(1,int(w*escF)); fh=max(1,int(h*escF))
    sx=np.minimum(w-1,(np.arange(fw)/escF).astype(int)); sy=np.minimum(h-1,(np.arange(fh)/escF).astype(int))
    peq=px[sy][:,sx].astype(np.int32); pm=pl[sy][:,sx]
    pf=1-suave(pm,0.05,0.30)
    lr=_lin[peq[...,0]]*pf; lg=_lin[peq[...,1]]*pf; lb=_lin[peq[...,2]]*pf
    raio=int(2+(intensidade/100)*8); raio=max(2,min(10,raio)); rG=min(3*raio,30)
    dR,dG,dB,dP=(disco(x,raio) for x in (lr,lg,lb,pf)); gR,gG,gB,gP=(disco(x,rG) for x in (lr,lg,lb,pf))
    fR=np.where(dP>0.02,dR/np.maximum(dP,1e-6),np.where(gP>0.005,gR/np.maximum(gP,1e-6),lr))
    fG=np.where(dP>0.02,dG/np.maximum(dP,1e-6),np.where(gP>0.005,gG/np.maximum(gP,1e-6),lg))
    fB=np.where(dP>0.02,dB/np.maximum(dP,1e-6),np.where(gP>0.005,gB/np.maximum(gP,1e-6),lb))
    fundo=np.stack([srgb(fR),srgb(fG),srgb(fB)],-1)*255
    fundo=np.asarray(Image.fromarray(np.clip(fundo,0,255).astype(np.uint8)).resize((w,h),Image.BILINEAR)).astype(np.float32)
    a=suave(pl,0.20,0.80)[...,None]
    return np.clip(px*a+fundo*(1-a),0,255).astype(np.uint8)

def overlay(px, pl, cor=(255,87,95), forca=0.45):
    a=(suave(pl,0.2,0.8)*forca)[...,None]; c=np.array(cor,np.float32)
    return np.clip(px*(1-a)+c*a,0,255).astype(np.uint8)

def analisa(caminho, saida, lado=1024, intensidade=60):
    img=Image.open(caminho).convert("RGB"); img.thumbnail((lado,lado)); px=np.asarray(img).astype(np.float32)
    cats=segmentar(img); bruta=1-cats[...,0]; bruta,manchas=limpa_mascara(bruta)
    cob=float((bruta>0.5).mean())
    antiga=plena(px,bruta,guiado=False); nova=plena(px,bruta,guiado=True)
    # métricas de borda: quanto da máscara está em zona incerta (0.15..0.85) e quanto a máscara mudou
    inc_a=float(((antiga>0.15)&(antiga<0.85)).mean()); inc_n=float(((nova>0.15)&(nova<0.85)).mean())
    dif=float(np.abs(nova-antiga).mean())
    ba=desfocar(px,antiga,intensidade); bn=desfocar(px,nova,intensidade)
    h,w=px.shape[:2]
    def lado_a_lado(imgs):
        return Image.fromarray(np.concatenate(imgs,axis=1))
    lado_a_lado([px.astype(np.uint8), overlay(px,antiga), overlay(px,nova)]).save(saida+"_mascaras.jpg",quality=90)
    lado_a_lado([px.astype(np.uint8), ba, bn]).save(saida+"_desfoque.jpg",quality=90)
    print(f"{os.path.basename(caminho)}: {w}x{h} pessoa={cob*100:.1f}% manchas_removidas={manchas} incerta antiga={inc_a*100:.1f}% nova={inc_n*100:.1f}% |dif|={dif:.3f}")
    return bruta, nova

if __name__=="__main__":
    for c in sys.argv[1:]:
        base=os.path.join(os.path.dirname(__file__),"saida",os.path.splitext(os.path.basename(c))[0])
        analisa(c, base)
