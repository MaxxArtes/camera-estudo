"""Porte numpy de Cura.kt (descasca do contorno + alisa 3x3) e Local.kt (radial/linear) para medir antes da UI."""
import sys, os, glob, math, numpy as np
from PIL import Image
SP=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,SP)
import porte_editor as pe

def cura(px, pontos, raio_frac):   # px uint8 HxWx3; pontos normalizados; raio em fração da largura  (== Cura.cura)
    h,w=px.shape[:2]; r=max(1,round(raio_frac*w)); out=px.astype(np.int32).copy()
    xs=[round(nx*(w-1)) for nx,_ in pontos]; ys=[round(ny*(h-1)) for _,ny in pontos]
    x0=max(0,min(xs)-r-1); y0=max(0,min(ys)-r-1); x1=min(w-1,max(xs)+r+1); y1=min(h-1,max(ys)+r+1)
    bw=x1-x0+1; bh=y1-y0+1; yy,xx=np.mgrid[0:bh,0:bw]
    buraco=np.zeros((bh,bw),bool)
    for cx,cy in zip(xs,ys): buraco|=((xx+x0-cx)**2+(yy+y0-cy)**2)<=r*r
    pronto=~buraco; reg=out[y0:y1+1,x0:x1+1]
    voltas=0
    while (~pronto).any() and voltas<4*r+8:
        voltas+=1
        # soma e contagem dos 8 vizinhos prontos
        soma=np.zeros((bh,bw,3),np.float64); cnt=np.zeros((bh,bw),np.int32)
        for dy in (-1,0,1):
            for dx in (-1,0,1):
                if dx==0 and dy==0: continue
                sh=np.zeros_like(pronto); sr=np.zeros_like(reg,dtype=np.float64)
                ys_=slice(max(0,dy),bh+min(0,dy)); xs_=slice(max(0,dx),bw+min(0,dx)); yd=slice(max(0,-dy),bh+min(0,-dy)); xd=slice(max(0,-dx),bw+min(0,-dx))
                sh[yd,xd]=pronto[ys_,xs_]; sr[yd,xd]=reg[ys_,xs_]
                soma+=sr*sh[...,None]; cnt+=sh
        novo=(~pronto)&(cnt>=2)
        if not novo.any(): break
        reg[novo]=(soma[novo]/cnt[novo][:,None]).astype(np.int32); pronto|=novo
    # alisa 3x3 só dentro do buraco
    cop=reg.astype(np.float64); s=np.zeros_like(cop); c=np.zeros((bh,bw),np.float64)
    for dy in (-1,0,1):
        for dx in (-1,0,1):
            ys_=slice(max(0,dy),bh+min(0,dy)); xs_=slice(max(0,dx),bw+min(0,dx)); yd=slice(max(0,-dy),bh+min(0,-dy)); xd=slice(max(0,-dx),bw+min(0,-dx))
            s[yd,xd]+=cop[ys_,xs_]; c[yd,xd]+=1
    lis=s/c[...,None]; reg[buraco]=lis[buraco].astype(np.int32)
    out[y0:y1+1,x0:x1+1]=reg
    return np.clip(out,0,255).astype(np.uint8), (x0,y0,x1,y1)

def suave(t): x=np.clip(t,0,1); return x*x*(3-2*x)
def mascara_radial(w,h,cx,cy,rx,ry,suav):
    ny,nx=np.mgrid[0:h,0:w]; nx=nx/(w-1); ny=ny/(h-1)
    d=np.sqrt(((nx-cx)/max(rx,.01))**2+((ny-cy)/max(ry,.01))**2); s=np.clip(suav/100,0.02,1)
    return 1-suave((d-(1-s))/s)
def mascara_linear(w,h,x1,y1,x2,y2):
    ny,nx=np.mgrid[0:h,0:w]; nx=nx/(w-1); ny=ny/(h-1); ax=x2-x1; ay=y2-y1; l2=ax*ax+ay*ay
    t=((nx-x1)*ax+(ny-y1)*ay)/max(l2,1e-6); return 1-suave(t)

if __name__=="__main__":
    im=Image.open(f"{SP}/fotos/pessoa1.jpg").convert("RGB"); im.thumbnail((1024,1024)); px=np.asarray(im)
    h,w=px.shape[:2]
    # 1) cicatrizar: pinta 3 "manchas" sintéticas (círculos escuros) em pele/parede e cura; mede erro vs original
    sujo=px.copy(); alvos=[(0.5,0.78,0.012),(0.33,0.55,0.010),(0.8,0.3,0.015)]   # bochecha, testa/olho?, parede
    ny,nx=np.mgrid[0:h,0:w]
    for cx,cy,rf in alvos:
        r=rf*w; m=((nx-cx*(w-1))**2+(ny-cy*(h-1))**2)<=(r*0.8)**2; sujo[m]=(sujo[m]*0.35).astype(np.uint8)   # mancha escura
    curado=sujo.copy()
    for cx,cy,rf in alvos: curado,_=cura(curado,[(cx,cy)],rf)
    def err(a,b,cx,cy,rf):
        r=rf*w; m=((nx-cx*(w-1))**2+(ny-cy*(h-1))**2)<=r*r; return float(np.abs(a[m].astype(int)-b[m].astype(int)).mean())
    for cx,cy,rf in alvos: print(f"mancha ({cx:.2f},{cy:.2f}) r={rf*w:.0f}px: erro suja={err(sujo,px,cx,cy,rf):.1f}  curada={err(curado,px,cx,cy,rf):.1f}  (0=igual ao original)")
    # recortes ampliados das 3 regiões: original | com mancha | curado
    tiles=[]
    for cx,cy,rf in alvos:
        r=int(rf*w*3); X=int(cx*(w-1)); Y=int(cy*(h-1)); x0=max(0,X-r); y0=max(0,Y-r); x1=min(w,X+r); y1=min(h,Y+r)
        t=np.concatenate([px[y0:y1,x0:x1],sujo[y0:y1,x0:x1],curado[y0:y1,x0:x1]],axis=1)
        tiles.append(Image.fromarray(t).resize((900, int(900*t.shape[0]/t.shape[1])), Image.NEAREST))
    # 2) local: radial e linear renderizadas como sobreposição
    rad=mascara_radial(w,h,0.5,0.45,0.28,0.35,60); lin=mascara_linear(w,h,0.5,0.15,0.5,0.6)
    tiles.append(Image.fromarray(np.concatenate([pe.overlay(px.astype(np.float32),rad,forca=0.5), pe.overlay(px.astype(np.float32),lin,forca=0.5)],axis=1)).resize((900,int(900*h/(2*w))),Image.BILINEAR))
    H=sum(t.height for t in tiles); out=Image.new("RGB",(900,H)); y=0
    for t in tiles: out.paste(t,(0,y)); y+=t.height
    out.save(f"{SP}/saida/painel_cura_local.jpg",quality=90); print("painel_cura_local:", out.size)
