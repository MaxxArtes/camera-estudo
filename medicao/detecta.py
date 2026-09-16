"""Por que a folha branca no piso claro não foi achada, e o que acha. Candidatos: claro (Otsu no normalizado),
lisa (região sem bordas), textura (desvio local baixo + fechamento) e LINHAS (Hough nas bordas da folha)."""
import numpy as np, math, time
from PIL import Image, ImageDraw
im=Image.open('foto.jpg').convert('RGB'); W0,H0=im.size
esc=640/max(W0,H0); im640=im.resize((int(W0*esc),int(H0*esc)), Image.BILINEAR); a=np.asarray(im640,float)/255; g=(a[...,0]*54+a[...,1]*183+a[...,2]*19)/256; H,W=g.shape
def box(x,r):
    p=np.pad(x,r,mode='edge'); n=2*r+1
    h=sum(p[:,i:i+x.shape[1]] for i in range(n))/n; return sum(h[i:i+x.shape[0]] for i in range(n))/n
def otsu(v):
    h,_=np.histogram(v,256,(0,1)); p=h/h.sum(); w=np.cumsum(p); m=np.cumsum(p*np.arange(256)); mt=m[-1]
    s=(mt*w-m)**2/np.maximum(w*(1-w),1e-9); return np.argmax(s)/255
def maior_mancha(m):
    # rotula por BFS simples (scipy não há): usa varredura com união de linhas
    from collections import deque
    lab=np.zeros(m.shape,int); best=(0,None); k=0
    for y in range(m.shape[0]):
        for x in range(m.shape[1]):
            if m[y,x] and lab[y,x]==0:
                k+=1; q=deque([(y,x)]); lab[y,x]=k; pts=[]
                while q:
                    cy,cx=q.popleft(); pts.append((cy,cx))
                    for ny,nx in ((cy-1,cx),(cy+1,cx),(cy,cx-1),(cy,cx+1)):
                        if 0<=ny<m.shape[0] and 0<=nx<m.shape[1] and m[ny,nx] and lab[ny,nx]==0: lab[ny,nx]=k; q.append((ny,nx))
                if len(pts)>best[0]: best=(len(pts),pts)
    return best
def quad_de_pontos(pts):
    ys=np.array([p[0] for p in pts]); xs=np.array([p[1] for p in pts])
    s=xs+ys; d=xs-ys
    return [(xs[s.argmin()],ys[s.argmin()]),(xs[d.argmax()],ys[d.argmax()]),(xs[s.argmax()],ys[s.argmax()]),(xs[d.argmin()],ys[d.argmin()])]
def area(q):
    return abs(sum(q[i][0]*q[(i+1)%4][1]-q[(i+1)%4][0]*q[i][1] for i in range(4)))/2
res={}
t=time.time()
fundo=box(g,40); norm=np.clip(g*0.78/np.maximum(fundo,1e-3),0,1); lim=otsu(norm)
n,pts=maior_mancha(norm>lim); q=quad_de_pontos(pts); res['claro']=(q, n/area(q) if area(q) else 0, area(q)/(W*H))
# lisa: gradiente forte dilatado
gx=np.zeros_like(g); gy=np.zeros_like(g); gx[:,1:-1]=g[:,2:]-g[:,:-2]; gy[1:-1]=g[2:]-g[:-2]; mag=np.hypot(gx,gy)
borda=mag>0.08; borda=box(borda.astype(float),2)>0; n,pts=maior_mancha(~borda); q=quad_de_pontos(pts); res['lisa']=(q, n/area(q) if area(q) else 0, area(q)/(W*H))
# textura: desvio local pequeno em janela 3, depois fechamento grande para engolir o texto
med=box(g,1); var=box((g-med)**2,1); liso=var<(0.004**1)  # limiar de textura
fech=box(box(liso.astype(float),6)>0.0,6)>0.999  # dilata 6, erode 6 (aprox)
n,pts=maior_mancha(fech); q=quad_de_pontos(pts); res['textura']=(q, n/area(q) if area(q) else 0, area(q)/(W*H))
print('tempo candidatos', round(time.time()-t,1),'s')
# LINHAS: Hough em bordas fortes, procura 2 quase verticais e 2 quase horizontais
t=time.time()
ys,xs=np.where(mag>0.10); ang=np.arctan2(gy[ys,xs],gx[ys,xs])
thetas=np.deg2rad(np.arange(0,180,1.0)); diag=int(math.hypot(W,H))
acc=np.zeros((len(thetas),2*diag+1))
for i,th in enumerate(thetas):
    # só pontos cujo gradiente é paralelo à normal da linha (±20°) votam
    dif=np.abs(((ang-th+np.pi/2)%np.pi)-np.pi/2)
    sel=dif<np.deg2rad(20)
    rho=(xs[sel]*math.cos(th)+ys[sel]*math.sin(th)).astype(int)+diag
    np.add.at(acc[i],rho,1)
def picos(faixa_theta, nmax=6):
    out=[]
    A=acc.copy()
    for _ in range(nmax):
        m=np.zeros_like(A,bool)
        for lo,hi in faixa_theta: m[lo:hi]=True
        A2=np.where(m,A,0); i,j=np.unravel_index(A2.argmax(),A2.shape)
        if A2[i,j]<30: break
        out.append((thetas[i],j-diag,A2[i,j])); A[max(0,i-8):i+8, max(0,j-15):j+15]=0
    return out
vert=picos([(0,25),(155,180)]); horiz=picos([(65,115)])
print('verticais', [(round(math.degrees(t)),r,int(v)) for t,r,v in vert]); print('horizontais', [(round(math.degrees(t)),r,int(v)) for t,r,v in horiz])
def inter(l1,l2):
    (t1,r1,_),(t2,r2,_)=l1,l2; A=np.array([[math.cos(t1),math.sin(t1)],[math.cos(t2),math.sin(t2)]]); b=np.array([r1,r2])
    try: x,y=np.linalg.solve(A,b); return (x,y)
    except Exception: return None
# escolhe o par de verticais mais afastado e o par de horizontais mais afastado que caibam na imagem
def melhor_par(ls, eixo):
    best=None
    for i in range(len(ls)):
        for j in range(i+1,len(ls)):
            d=abs(ls[i][1]-ls[j][1])
            if d>0.3*(W if eixo=='v' else H) and (best is None or d>best[0]): best=(d,ls[i],ls[j])
    return best
pv=melhor_par(vert,'v'); ph=melhor_par(horiz,'h')
if pv and ph:
    l,r=sorted([pv[1],pv[2]], key=lambda l: l[1]*(1 if abs(math.cos(l[0]))>0 else -1)); tp,bt=sorted([ph[1],ph[2]], key=lambda l: l[1])
    q=[inter(tp,l),inter(tp,r),inter(bt,r),inter(bt,l)]
    if all(q): q=[(float(np.clip(x,0,W-1)),float(np.clip(y,0,H-1))) for x,y in q]; res['linhas']=(q, None, area(q)/(W*H))
print('tempo linhas', round(time.time()-t,1),'s')
for k,(q,pre,fr) in res.items(): print(f'{k:8s} fração {fr:.2f} preench {pre if pre is None else round(pre,2)} quad', [(int(x),int(y)) for x,y in q])
# desenha
cores={'claro':'red','lisa':'blue','textura':'green','linhas':'yellow'}
d=ImageDraw.Draw(im640)
for k,(q,_,_) in res.items(): d.line([tuple(map(float,p)) for p in q]+[tuple(map(float,q[0]))], fill=cores[k], width=3)
im640.save('candidatos.jpg', quality=90)
