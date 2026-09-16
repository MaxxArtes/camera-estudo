import numpy as np, math
from PIL import Image, ImageDraw
exec(open('detecta.py').read().split("res={}")[0])   # utilidades, im640, g, W, H
gx=np.zeros_like(g); gy=np.zeros_like(g); gx[:,1:-1]=g[:,2:]-g[:,:-2]; gy[1:-1]=g[2:]-g[:-2]; mag=np.hypot(gx,gy)
ys,xs=np.where(mag>0.10); ang=np.arctan2(gy[ys,xs],gx[ys,xs])
thetas=np.deg2rad(np.arange(-90,90,1.0)); diag=int(math.hypot(W,H)); acc=np.zeros((len(thetas),2*diag+1))
for i,th in enumerate(thetas):
    dif=np.abs(((ang-th+np.pi/2)%np.pi)-np.pi/2); sel=dif<np.deg2rad(20)
    rho=np.rint(xs[sel]*math.cos(th)+ys[sel]*math.sin(th)).astype(int)+diag; np.add.at(acc[i],rho,1)
def picos(faixas, nmax=8):
    A=acc.copy(); m=np.zeros(A.shape,bool)
    for lo,hi in faixas: m[(np.degrees(thetas)>=lo)&(np.degrees(thetas)<hi)]=True
    out=[]
    for _ in range(nmax):
        A2=np.where(m,A,0); i,j=np.unravel_index(A2.argmax(),A2.shape)
        if A2[i,j]<30: break
        out.append((thetas[i],j-diag,int(A2[i,j]))); A[max(0,i-8):i+8, max(0,j-12):j+12]=0
    return out
vert=picos([(-25,25)]); horiz=picos([(65,90),(-90,-65)])
print('vert', [(round(math.degrees(t)),r,v) for t,r,v in vert]); print('horiz', [(round(math.degrees(t)),r,v) for t,r,v in horiz])
def inter(l1,l2):
    (t1,r1,_),(t2,r2,_)=l1,l2; A=np.array([[math.cos(t1),math.sin(t1)],[math.cos(t2),math.sin(t2)]]); b=np.array([r1,r2],float)
    x,y=np.linalg.solve(A,b); return (x,y)
def x_em(l,y): t,r,_=l; return (r-y*math.sin(t))/max(1e-6,math.cos(t))
def y_em(l,x): t,r,_=l; return (r-x*math.cos(t))/(math.sin(t) if abs(math.sin(t))>1e-6 else 1e-6)
cands=[]
for i in range(len(vert)):
    for j in range(i+1,len(vert)):
        l,r=sorted([vert[i],vert[j]], key=lambda L: x_em(L,H/2))
        if x_em(r,H/2)-x_em(l,H/2) < 0.3*W: continue
        for a in range(len(horiz)):
            for b in range(a+1,len(horiz)):
                tp,bt=sorted([horiz[a],horiz[b]], key=lambda L: y_em(L,W/2))
                if y_em(bt,W/2)-y_em(tp,W/2) < 0.3*H: continue
                q=[inter(tp,l),inter(tp,r),inter(bt,r),inter(bt,l)]
                if any(not(-5<=x<=W+5 and -5<=y<=H+5) for x,y in q): continue
                fr=area(q)/(W*H)
                if not 0.15<=fr<=0.97: continue
                # dentro mais claro que fora (folha branca) e apoio de borda no perímetro
                m=np.zeros((H,W),bool); poly=Image.new('L',(W,H)); ImageDraw.Draw(poly).polygon([tuple(p) for p in q],fill=255); m=np.asarray(poly)>0
                anel=(box(m.astype(float),8)>0)&~m
                contraste=g[m].mean()-g[anel].mean() if anel.any() else 0
                votos=l[2]+r[2]+tp[2]+bt[2]
                # apoio: fração do perímetro com borda forte a ±2 px (borda da folha é contínua; linha de texto é parcial)
                magd=box(mag,2)*25  # máximo aproximado em janela 5x5 (soma)
                ok=0; tot=0
                for k in range(4):
                    (x0,y0),(x1,y1)=q[k],q[(k+1)%4]; n=int(max(abs(x1-x0),abs(y1-y0)))
                    for t in np.linspace(0,1,max(2,n//2)):
                        x=int(round(x0+(x1-x0)*t)); y=int(round(y0+(y1-y0)*t))
                        if 0<=x<W and 0<=y<H: tot+=1; ok+= mag[max(0,y-2):y+3, max(0,x-2):x+3].max()>0.06
                apoio=ok/max(1,tot)
                placar=apoio+fr*1.0
                cands.append((placar,q,fr,apoio,votos))
cands.sort(key=lambda c:-c[0])
for c in cands[:4]: print(f'placar {c[0]:.2f} fração {c[2]:.2f} apoio {c[3]:.2f} votos {c[4]} quad', [(int(x),int(y)) for x,y in c[1]])
im2=im640.copy(); d=ImageDraw.Draw(im2)
if cands: q=cands[0][1]; d.line([tuple(map(float,p)) for p in q]+[tuple(map(float,q[0]))], fill='yellow', width=3)
im2.save('linhas.jpg', quality=90)
