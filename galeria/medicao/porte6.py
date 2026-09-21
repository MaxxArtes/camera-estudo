import sys, os, glob, numpy as np
from PIL import Image, ImageDraw
SP=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,SP)
import porte_editor as pe, porte2 as p2, porte5 as p5
from ai_edge_litert.interpreter import Interpreter

def guiado_rgb(px, m, r, eps):   # filtro guiado com guia colorido (He et al.): a = (Σ+eps I)^-1 cov, por pixel
    h,w=px.shape[:2]; I=px/255.0
    mI=[pe.caixaF(I[...,c],r) for c in range(3)]; mP=pe.caixaF(m,r)
    cov=[pe.caixaF(I[...,c]*m,r)-mI[c]*mP for c in range(3)]
    S=np.zeros((h,w,3,3),np.float32)
    for i in range(3):
        for j in range(3):
            S[...,i,j]=pe.caixaF(I[...,i]*I[...,j],r)-mI[i]*mI[j]
    S+=eps*np.eye(3,dtype=np.float32)
    a=np.linalg.solve(S, np.stack(cov,-1)[...,None])[...,0]            # (h,w,3)
    b=mP-(a[...,0]*mI[0]+a[...,1]*mI[1]+a[...,2]*mI[2])
    ma=[pe.caixaF(a[...,c],r) for c in range(3)]; mb=pe.caixaF(b,r)
    return np.clip(ma[0]*I[...,0]+ma[1]*I[...,1]+ma[2]*I[...,2]+mb,0,1)

def pos_com_guia(px, bruta, guia_rgb=False):
    h,w=px.shape[:2]
    m=np.asarray(Image.fromarray((bruta*255).astype(np.uint8)).resize((w,h), Image.BILINEAR)).astype(np.float32)/255.0
    b=m>0.5; r=max(2,min(w,h)//60); b=p2.erode(p2.dilata(b,r),r)
    rot,tam=p5.rotula(~b); borda=set(np.unique(np.concatenate([rot[0,:],rot[-1,:],rot[:,0],rot[:,-1]])))
    for k,n in enumerate(tam,1):
        if k not in borda: b[rot==k]=True
    rot2,tam2=p5.rotula(b); keep=np.zeros_like(b)
    for k,n in enumerate(tam2,1):
        if n>=0.003*h*w: keep|=(rot2==k)
    mm=keep.astype(np.float32); rg=max(3,min(w,h)//120)
    if guia_rgb: return guiado_rgb(px,mm,rg,1e-3)
    guia=(px[...,0]*0.299+px[...,1]*0.587+px[...,2]*0.114)/255.0
    mI=pe.caixaF(guia,rg); mP=pe.caixaF(mm,rg); cI=pe.caixaF(guia*guia,rg); cIP=pe.caixaF(guia*mm,rg)
    a=(cIP-mI*mP)/((cI-mI*mI)+1e-3); bb=mP-a*mI
    return np.clip(pe.caixaF(a,rg)*guia+pe.caixaF(bb,rg),0,1)

im=Image.open(f"{SP}/fotos/grupo12_original.jpg").convert("RGB"); im.thumbnail((1024,1024)); px=np.asarray(im).astype(np.float32); h,w=px.shape[:2]
cats=pe.segmentar(im)
bruta=1-cats[...,0]
sem_outros=np.clip(cats[...,1]+cats[...,2]+cats[...,3]+cats[...,4],0,1)     # cabelo+pele corpo+pele rosto+roupa (sem "outros")
it=Interpreter(model_path=f"{SP}/modelos/deeplab_v3.tflite", num_threads=2); it.allocate_tensors(); i=it.get_input_details()[0]; o=it.get_output_details()[0]
x=np.asarray(im.resize((257,257), Image.BILINEAR)).astype(np.float32)/255.0; it.set_tensor(i["index"], x[None,...]); it.invoke(); lg=it.get_tensor(o["index"])[0]
e=np.exp(lg-lg.max(-1,keepdims=True)); dl=(e/e.sum(-1,keepdims=True))[...,15]
dl256=np.asarray(Image.fromarray((dl*255).astype(np.uint8)).resize((256,256),Image.BILINEAR)).astype(np.float32)/255
inter=np.minimum(bruta, np.where(dl256>=0.3,1.0,dl256/0.3))                  # multiclass ∩ deeplab (suave)
cor=np.array([17,17,20],np.float32)
def rot(arr,t):
    p=Image.fromarray(arr); d=ImageDraw.Draw(p); d.rectangle([0,0,480,26],fill=(0,0,0)); d.text((6,6),t,fill=(255,255,255)); return np.asarray(p)
# proxy de sangramento no chão: fração da máscara na faixa inferior de 10% da imagem que NÃO é pessoa pelo DeepLab
faixa=slice(int(h*0.9),h)
tiles=[]
for nome,bru,rgb in [("A: atual (luminância)",bruta,False),("B: sem classe 'outros'",sem_outros,False),("C: multiclass ∩ DeepLab",inter,False),("D: atual + guia RGB",bruta,True)]:
    q=pos_com_guia(px,bru,rgb); a=pe.suave(q,0.2,0.8)[...,None]
    dlfull=np.asarray(Image.fromarray((dl*255).astype(np.uint8)).resize((w,h),Image.BILINEAR)).astype(np.float32)/255
    chao=float(((q[faixa]>0.5)&(dlfull[faixa]<0.2)).mean()*100)
    print(f"{nome}: máscara={(q>0.5).mean()*100:.1f}%  incerta={((q>0.15)&(q<0.85)).mean()*100:.1f}%  chão-suspeito(faixa inferior)={chao:.1f}%")
    tiles.append(rot(np.clip(px*a+cor*(1-a),0,255).astype(np.uint8),nome))
pan=Image.fromarray(np.concatenate([np.concatenate(tiles[:2],axis=1),np.concatenate(tiles[2:],axis=1)],axis=0)); pan=pan.resize((1400,int(pan.height*1400/pan.width)),Image.BILINEAR)
pan.save(f"{SP}/saida/grupo12_remedios.jpg",quality=88); print("painel:", pan.size)
