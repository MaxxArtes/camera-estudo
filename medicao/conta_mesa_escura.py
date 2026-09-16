import numpy as np, sys
from PIL import Image
sys.path.insert(0, '../folha')
im=Image.open('/root/.claude/uploads/b411c8f2-5312-4cb6-a058-243cb5ebe3b7/1333f6ae-image.jpg').convert('RGB'); print('foto', im.size)
W0,H0=im.size; esc=640/max(W0,H0); im640=im.resize((int(W0*esc),int(H0*esc)), Image.BILINEAR)
a=np.asarray(im640,float)/255; g=(a[...,0]*54+a[...,1]*183+a[...,2]*19)/256; H,W=g.shape
src=open('../folha/detecta.py').read()
# só as funções do detecta.py (entre 'def box' e 'res={}'), sem a carga da foto.jpg
ns={'np':np,'Image':Image}; exec(src[src.index('def box'):src.index('res={}')], ns)
box=ns['box']; otsu=ns['otsu']; maior_mancha=ns['maior_mancha']; quad_de_pontos=ns['quad_de_pontos']; area=ns['area']
def cand(m, nome):
    n,pts=maior_mancha(m); q=quad_de_pontos(pts); fr=area(q)/(W*H); pre=n/area(q) if area(q) else 0
    print(f'{nome:14s} fração {fr:.2f} preench {pre:.2f} quad {[(int(x),int(y)) for x,y in q]}')
fundo=box(g,40); norm=np.clip(g*0.78/np.maximum(fundo,1e-3),0,1)
cand(norm>otsu(norm),'claro(norm)')
cand(g>otsu(g),'claro(bruto)')
print('otsu bruto', round(otsu(g),3), 'otsu norm', round(otsu(norm),3), 'luma mesa', round(np.median(g[g<otsu(g)]),3), 'luma papel', round(np.median(g[g>otsu(g)]),3))
