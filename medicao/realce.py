"""Manchas no realce: fundo estimado por borrão simples é puxado para baixo pelo texto (halo branco em volta das letras).
Alternativas: (b) convolução normalizada só com pixels claros; (c) máximo local + borrão. Métrica: desvio do papel
(mancha) e contraste do texto, no recorte da folha."""
import numpy as np
from PIL import Image
im=Image.open('foto.jpg').convert('L'); W0,H0=im.size; k=W0/480
folha=im.crop((int(46*k)+6,int(62*k)+6,int(433*k)-6,int(577*k)-6)); g=np.asarray(folha,float)/255; H,W=g.shape
def box(x,r):
    p=np.pad(x,r,mode='edge'); n=2*r+1
    h=sum(p[:,i:i+x.shape[1]] for i in range(n))/n; return sum(h[i:i+x.shape[0]] for i in range(n))/n
def maxf(x,r):
    p=np.pad(x,r,mode='edge'); n=2*r+1
    h=np.max(np.stack([p[:,i:i+x.shape[1]] for i in range(n)]),0); return np.max(np.stack([h[i:i+x.shape[0]] for i in range(n)]),0)
def estica(y, corte=0.08):
    h,_=np.histogram(y,256,(0,1)); c=np.cumsum(h)/h.sum(); lo=np.searchsorted(c,0.01)/255; hi=np.searchsorted(c,1-corte)/255
    return np.clip((y-lo)/max(1e-3,hi-lo),0,1) if hi-lo>0.15 else y
def metricas(y):
    # papel = pixels acima da mediana local (fundo); texto = abaixo de 0,5 do fundo
    fundo=box(y,6); papel=y>fundo-0.02; texto=y<0.55
    return y[papel].std(), y[papel].mean()-y[texto].mean() if texto.any() else 0, texto.mean()
r=int(24*H/2000*1.0)+1; r=max(6,r)  # raio equivalente ao do app (24 em 2000 px)
res={}
f=box(g,r); res['a_borrao']=estica(np.clip(g*0.92/np.maximum(f,1e-3),0,1))
w=(g>box(g,r)-0.03).astype(float); f2=box(g*w,r)/np.maximum(box(w,r),1e-3); res['b_normalizada']=estica(np.clip(g*0.92/np.maximum(f2,1e-3),0,1))
f3=box(maxf(g,3),r); res['c_maximo']=estica(np.clip(g*0.98/np.maximum(f3,1e-3),0,1))
# (d) normalizada em raio maior + corte de branco mais brando
w=(g>box(g,2*r)-0.03).astype(float); f4=box(g*w,2*r)/np.maximum(box(w,2*r),1e-3); res['d_norm_r2x_branco1%']=estica(np.clip(g*0.92/np.maximum(f4,1e-3),0,1),corte=0.01)
print(f'original            mancha {metricas(g)[0]:.4f} contraste {metricas(g)[1]:.3f} texto {metricas(g)[2]*100:.1f}%')
for k_,y in res.items():
    m=metricas(y); print(f'{k_:20s} mancha {m[0]:.4f} contraste {m[1]:.3f} texto {m[2]*100:.1f}%')
# montagem de um trecho com texto denso
y0,y1,x0,x1=int(H*0.55),int(H*0.72),int(W*0.05),int(W*0.6)
tiras=[Image.fromarray((np.clip(v,0,1)*255).astype(np.uint8)).crop((x0,y0,x1,y1)) for v in [g]+list(res.values())]
m=Image.new('L',(tiras[0].width, sum(t.height+6 for t in tiras)),128); yy=0
for t in tiras: m.paste(t,(0,yy)); yy+=t.height+6
m.save('realce_compara.jpg',quality=90)
