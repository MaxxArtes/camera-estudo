"""Variantes do pós-processamento: raio do fechamento e preenchimento de buracos condicionado à probabilidade bruta."""
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

def pos(px, bruta, div_fecha=60, limiar_buraco=None, min_frac=0.003, eps=1e-3):
    h,w=px.shape[:2]
    m=np.asarray(Image.fromarray((bruta*255).astype(np.uint8)).resize((w,h), Image.BILINEAR)).astype(np.float32)/255.0
    b=m>0.5; r=max(2,min(w,h)//div_fecha)
    b=p2.erode(p2.dilata(b,r),r)
    # buracos = componentes de fundo NÃO ligados à borda
    rot,tam=rotula(~b); borda=set(np.unique(np.concatenate([rot[0,:],rot[-1,:],rot[:,0],rot[:,-1]])))
    cheios=0; area=0
    for k,n in enumerate(tam,1):
        if k in borda: continue
        reg=(rot==k)
        if limiar_buraco is None or m[reg].mean()>=limiar_buraco: b[reg]=True; cheios+=1; area+=n
    rot2,tam2=rotula(b); keep=np.zeros_like(b)
    for k,n in enumerate(tam2,1):
        if n>=min_frac*h*w: keep|=(rot2==k)
    mm=keep.astype(np.float32); rg=max(3,min(w,h)//120)
    guia=(px[...,0]*0.299+px[...,1]*0.587+px[...,2]*0.114)/255.0
    mI=pe.caixaF(guia,rg); mP=pe.caixaF(mm,rg); cI=pe.caixaF(guia*guia,rg); cIP=pe.caixaF(guia*mm,rg)
    varI=cI-mI*mI; cov=cIP-mI*mP; a=cov/(varI+eps); bb=mP-a*mI
    q=np.clip(pe.caixaF(a,rg)*guia+pe.caixaF(bb,rg),0,1)
    return q, cheios, area

im=Image.open(f"{SP}/fotos/grupo12_original.jpg").convert("RGB"); im.thumbnail((1024,1024)); px=np.asarray(im).astype(np.float32); h,w=px.shape[:2]
cats=pe.segmentar(im); bruta=1-cats[...,0]
cor=np.array([17,17,20],np.float32)
def rot(arr,t):
    p=Image.fromarray(arr); d=ImageDraw.Draw(p); d.rectangle([0,0,420,26],fill=(0,0,0)); d.text((6,6),t,fill=(255,255,255)); return np.asarray(p)
tiles=[]
for nome,div,lim in [("V0 atual: fecha /60, enche tudo",60,None),("V1: fecha /120, enche tudo",120,None),("V2: fecha /60, enche se bruta>=0.12",60,0.12),("V3: fecha /120, enche se bruta>=0.12",120,0.12)]:
    q,cheios,area=pos(px,bruta,div,lim); a=pe.suave(q,0.2,0.8)[...,None]
    solido=np.clip(px*a+cor*(1-a),0,255).astype(np.uint8)
    print(f"{nome}: máscara={(q>0.5).mean()*100:.1f}%  buracos enchidos={cheios} área={area*100/(w*h):.2f}%  incerta={((q>0.15)&(q<0.85)).mean()*100:.1f}%")
    tiles.append(rot(solido,nome))
pan=Image.fromarray(np.concatenate([np.concatenate(tiles[:2],axis=1),np.concatenate(tiles[2:],axis=1)],axis=0)); pan=pan.resize((1400,int(pan.height*1400/pan.width)),Image.BILINEAR)
pan.save(f"{SP}/saida/grupo12_variantes.jpg",quality=88); print("painel:", pan.size)
