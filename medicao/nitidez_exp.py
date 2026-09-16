import numpy as np
from PIL import Image
def blur5(a):
    k=np.array([1,4,6,4,1],float)/16; p=np.pad(a,2,mode='edge')
    h=sum(k[i]*p[:,i:i+a.shape[1]] for i in range(5)); return sum(k[i]*h[i:i+a.shape[0]] for i in range(5))
def box(a,r):
    p=np.pad(a,r,mode='edge'); n=2*r+1
    h=sum(p[:,i:i+a.shape[1]] for i in range(n))/n; return sum(h[i:i+a.shape[0]] for i in range(n))/n
def medidas(g, escala=1200):
    # avalia na escala 1200 px como as outras medidas
    im=Image.fromarray((np.clip(g,0,1)*255).astype(np.uint8)); im.thumbnail((escala,escala)); a=np.asarray(im,float)/255
    b=blur5(a); hp=a-b; vals=[]
    for y in range(0,a.shape[0]-24,24):
        for x in range(0,a.shape[1]-24,24):
            bl=b[y:y+24,x:x+24]
            if bl.std()<0.01 and bl.mean()>0.15: vals.append(hp[y:y+24,x:x+24].std())
    h,w=a.shape; c=a[int(h*0.35):int(h*0.75), int(w*0.25):int(w*0.75)]
    l=-4*c[1:-1,1:-1]+c[:-2,1:-1]+c[2:,1:-1]+c[1:-1,:-2]+c[1:-1,2:]
    return l.var(), np.median(vals)
g=np.asarray(Image.open('728356f4-image.jpg').convert('L'),float)/255   # trabalha na resolução gravada (1944x2592)
print('base       nitidez %.5f ruído %.4f' % medidas(g))
for r in (1,2):
    b=box(g,r)
    for q in (1.0,1.5,2.0,3.0):
        for teto in (40/255, 80/255):
            d=np.clip((g-b)*q,-teto,teto); s=g+d
            n,ru=medidas(s); print(f'raio {r} q {q} teto {int(teto*255):3d}: nitidez {n:.5f} ruído {ru:.4f}')
