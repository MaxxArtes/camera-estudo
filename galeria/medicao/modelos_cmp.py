"""Roda UM modelo do rembg na foto do grupo e grava recorte (RGBA) + tempo. Um processo por modelo (falha isolada)."""
import sys, os, time, io, glob, numpy as np
from PIL import Image
SP=os.path.dirname(os.path.abspath(__file__)); nome=sys.argv[1]
from rembg import new_session, remove
im=Image.open(f"{SP}/fotos/grupo12_original.jpg").convert("RGB"); im.thumbnail((1024,1024))
t0=time.time(); sess=new_session(nome); t1=time.time()
out=remove(im, session=sess, post_process_mask=False); t2=time.time()
out.save(f"{SP}/saida/modelo_{nome}.png")
tam=0
for f in glob.glob(os.path.expanduser("~/.u2net/*")):
    if nome.replace("-","").replace("_","")[:8] in os.path.basename(f).replace("-","").replace("_","").lower(): tam=os.path.getsize(f)
a=np.asarray(out)[...,3]/255.0
print(f"{nome}: carga {t1-t0:.1f}s inferência {t2-t1:.1f}s | máscara={(a>0.5).mean()*100:.1f}% incerta={((a>0.15)&(a<0.85)).mean()*100:.1f}% | arquivo ~{tam/1e6:.0f} MB")
