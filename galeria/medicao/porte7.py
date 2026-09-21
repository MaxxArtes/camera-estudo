"""Máscara D = min(quadro inteiro, ladrilhos 2x2 com sobreposição 25% e janela Hann), validada nas 6 fotos."""
import sys, os, glob, numpy as np
from PIL import Image, ImageDraw
from collections import deque
SP=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,SP)
import porte_editor as pe, porte2 as p2

def rotula(b):
    h,w=b.shape; rot=np.zeros((h,w),np.int32); k=0; tam=[]
    for y in range(h):
        for x in range(w):
            if not b[y,x] or rot[y,x]: continue
            k+=1; n=0; q=deque([(y,x)]); rot[y,x]=k
            while q:
                cy,cx=q.popleft(); n+=1
                for dy,dx in ((-1,0),(1,0),(0,-1),(0,1)):
                    yy,xx=cy+dy,cx+dx
                    if 0<=yy<h and 0<=xx<w and b[yy,xx] and not rot[yy,xx]: rot[yy,xx]=k; q.append((yy,xx))
            tam.append(n)
    return rot,tam

def ladrilhos2x2(im, sobre=0.25, lado=512):
    """probabilidade de pessoa numa grade lado×lado, misturando 4 ladrilhos (Hann)."""
    w,h=im.size; n=2
    tw=int(w/(n-(n-1)*sobre)); th=int(h/(n-(n-1)*sobre)); xs=[0,w-tw]; ys=[0,h-th]
    acc=np.zeros((lado,lado),np.float32); peso=np.zeros((lado,lado),np.float32)
    for y0 in ys:
        for x0 in xs:
            cats=pe.segmentar(im.crop((x0,y0,x0+tw,y0+th))); p=1-cats[...,0]
            # destino na grade lado×lado
            gx0=int(round(x0/w*lado)); gy0=int(round(y0/h*lado)); gw=int(round(tw/w*lado)); gh=int(round(th/h*lado))
            gx1=min(lado,gx0+gw); gy1=min(lado,gy0+gh); gw=gx1-gx0; gh=gy1-gy0
            p=np.asarray(Image.fromarray((p*255).astype(np.uint8)).resize((gw,gh),Image.BILINEAR)).astype(np.float32)/255
            jan=np.outer(np.hanning(gh+2)[1:-1],np.hanning(gw+2)[1:-1]).astype(np.float32)+1e-3
            acc[gy0:gy1,gx0:gx1]+=p*jan; peso[gy0:gy1,gx0:gx1]+=jan
    return acc/np.maximum(peso,1e-6)

def mascara_D(im, lado=512):
    cats=pe.segmentar(im); cheio=1-cats[...,0]
    cheio512=np.asarray(Image.fromarray((cheio*255).astype(np.uint8)).resize((lado,lado),Image.BILINEAR)).astype(np.float32)/255
    return np.minimum(cheio512, ladrilhos2x2(im,lado=lado)), cheio

def pos(px, prob):   # pós-processamento do 0.17 sobre uma prob em qualquer grade
    h,w=px.shape[:2]
    m=np.asarray(Image.fromarray((prob*255).astype(np.uint8)).resize((w,h),Image.BILINEAR)).astype(np.float32)/255
    b=m>0.5; r=max(2,min(w,h)//60); b=p2.erode(p2.dilata(b,r),r)
    rot,tam=rotula(~b); borda=set(np.unique(np.concatenate([rot[0,:],rot[-1,:],rot[:,0],rot[:,-1]])))
    for k,n in enumerate(tam,1):
        if k not in borda: b[rot==k]=True
    rot2,tam2=rotula(b); keep=np.zeros_like(b)
    for k,n in enumerate(tam2,1):
        if n>=0.003*h*w: keep|=(rot2==k)
    mm=keep.astype(np.float32); rg=max(3,min(w,h)//120); guia=(px[...,0]*0.299+px[...,1]*0.587+px[...,2]*0.114)/255
    mI=pe.caixaF(guia,rg); mP=pe.caixaF(mm,rg); cI=pe.caixaF(guia*guia,rg); cIP=pe.caixaF(guia*mm,rg)
    a=(cIP-mI*mP)/((cI-mI*mI)+1e-3); bb=mP-a*mI
    return np.clip(pe.caixaF(a,rg)*guia+pe.caixaF(bb,rg),0,1)

if __name__=="__main__":
    cor=np.array([255,255,255],np.float32); linhas=[]
    for nome in ["grupo12_original","grupo6","pessoa1","pessoa2","pessoa3","pessoa4"]:
        im=Image.open(f"{SP}/fotos/{nome}.jpg").convert("RGB"); im.thumbnail((1024,1024)); px=np.asarray(im).astype(np.float32); h,w=px.shape[:2]
        D,cheio=mascara_D(im); qA=pos(px,cheio); qD=pos(px,D)
        ia=qA>0.5; idd=qD>0.5; perdido=float((ia&~idd).mean()*100); ganho=float((idd&~ia).mean()*100)
        print(f"{nome:18s} A={ia.mean()*100:5.1f}%  D={idd.mean()*100:5.1f}%  D remove {perdido:4.1f}% do quadro (era pessoa em A)  acrescenta {ganho:4.1f}%")
        aA=pe.suave(qA,0.2,0.8)[...,None]; aD=pe.suave(qD,0.2,0.8)[...,None]
        t=np.concatenate([np.clip(px*aA+cor*(1-aA),0,255).astype(np.uint8), np.clip(px*aD+cor*(1-aD),0,255).astype(np.uint8)],axis=1)
        p=Image.fromarray(t); d=ImageDraw.Draw(p); d.rectangle([0,0,300,24],fill=(0,0,0)); d.text((6,6),f"{nome}: A (atual) | D (∩ 2x2)",fill=(255,255,255))
        p=p.resize((1400,int(p.height*1400/p.width)),Image.BILINEAR); linhas.append(p)
    H=sum(p.height for p in linhas); out=Image.new("RGB",(1400,H)); y=0
    for p in linhas: out.paste(p,(0,y)); y+=p.height
    out.save(f"{SP}/saida/painel_D.jpg",quality=85); print("painel_D:", out.size)
