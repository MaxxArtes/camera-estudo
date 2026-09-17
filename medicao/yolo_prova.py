# Prova da escala das caixas do yolov8n-seg.tflite (17/09): roda em Docker python:3.11-slim com tflite-runtime pillow "numpy<2".
# Uso: copiar o .tflite e uma foto como caneca.jpg para a pasta e rodar; grava mascara.png e sobreposta.jpg.
import numpy as np, sys
from PIL import Image
from tflite_runtime.interpreter import Interpreter
it=Interpreter("yolov8n-seg.tflite", num_threads=2); it.allocate_tensors()
inp=it.get_input_details()[0]; outs=it.get_output_details()
print("entrada", inp['shape'], inp['dtype'])
for o in outs: print("saida", o['shape'])
im=Image.open("caneca.jpg").convert("RGB"); W,H=im.size
N=640; esc=min(N/W,N/H); nw,nh=int(W*esc),int(H*esc); padX=(N-nw)/2; padY=(N-nh)/2
tela=Image.new("RGB",(N,N),(114,114,114)); tela.paste(im.resize((nw,nh)),(int(padX),int(padY)))
x=np.asarray(tela,dtype=np.float32)[None]/255.0
it.set_tensor(inp['index'],x); it.invoke()
det=[it.get_tensor(o['index']) for o in outs if len(o['shape'])==3][0]
proto=[it.get_tensor(o['index']) for o in outs if len(o['shape'])==4][0]
d=det[0]  # [116,8400]
if d.shape[0]!=116: d=d.T
sc=d[4:84].max(0); a=sc.argmax(); cls=d[4:84,a].argmax()
print("melhor ancora", a, "classe", cls, "score", sc[a])
print("xywh cru:", d[0:4,a])
print("max cx sobre todas ancoras:", d[0].max(), "max w:", d[2].max())
# máscara com caixa em pixels (correção): sigmoid(coef·proto) dentro da caixa, grade 160
PM=160
cx,cy,w,h=d[0:4,a]*N
x0,y0,x1,y1=cx-w/2,cy-h/2,cx+w/2,cy+h/2
gx0,gy0=int(x0*PM/N),int(y0*PM/N); gx1,gy1=int(x1*PM/N),int(y1*PM/N)
coef=d[84:116,a]; p=proto[0]  # [160,160,32]
m=1/(1+np.exp(-(p@coef)))
box=np.zeros_like(m); box[gy0:gy1,gx0:gx1]=1; m=m*box
print("caixa px", x0,y0,x1,y1, "grade", gx0,gy0,gx1,gy1, "fracao>0.5 na grade:", (m>0.5).mean())
Image.fromarray((m*255).astype(np.uint8)).resize((N,N)).save("mascara.png")
# sobrepõe na letterbox
ov=np.asarray(tela).astype(np.float32); mm=np.asarray(Image.fromarray((m*255).astype(np.uint8)).resize((N,N)))/255.0
ov[...,1]=ov[...,1]*(0.4+0.6*mm); ov[...,0]=ov[...,0]*(0.4+0.6*mm)
Image.fromarray(ov.astype(np.uint8)).save("sobreposta.jpg")
