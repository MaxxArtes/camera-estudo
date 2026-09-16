import numpy as np
from PIL import Image
exec(open('realce.py').read().split("r=int(24*H/2000")[0])
for r in (16,40,80):
    f=box(g,r); a=estica(np.clip(g*0.92/np.maximum(f,1e-3),0,1))
    w=(g>box(g,r)-0.03).astype(float); f2=box(g*w,r)/np.maximum(box(w,r),1e-3); b=estica(np.clip(g*0.92/np.maximum(f2,1e-3),0,1))
    ma=metricas(a); mb=metricas(b)
    print(f'r={r:3d}  borrão: mancha {ma[0]:.4f} contraste {ma[1]:.3f} | normalizada: mancha {mb[0]:.4f} contraste {mb[1]:.3f}')
