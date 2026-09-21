import sys, os, glob, numpy as np
from PIL import Image, ImageDraw
SP=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,SP)
import porte_editor as pe, porte7 as p7
branco=np.array([255,255,255],np.float32)
def rot(arr,t):
    p=Image.fromarray(arr); d=ImageDraw.Draw(p); d.rectangle([0,0,min(p.width,520),26],fill=(0,0,0)); d.text((6,6),t,fill=(255,255,255)); return np.asarray(p)
im=Image.open(f"{SP}/fotos/grupo12_original.jpg").convert("RGB"); im.thumbnail((1024,1024)); px=np.asarray(im).astype(np.float32); h,w=px.shape[:2]
cats=pe.segmentar(im); q=p7.pos(px,1-cats[...,0]); a=pe.suave(q,0.2,0.8)[...,None]
tiles=[rot(np.clip(px*a+branco*(1-a),0,255).astype(np.uint8),"NOSSO 0.17 (selfie_multiclass 256²)")]
ref=f"{SP}/fotos/removebg_ref.jpg"
if os.path.exists(ref):
    r=Image.open(ref).convert("RGB").resize((w,h),Image.BILINEAR); tiles.append(rot(np.asarray(r),"remove.bg (print do dono, referência)"))
ordem=["u2net_human_seg","silueta","isnet-general-use","birefnet-general-lite","birefnet-portrait"]
tempos={}
log=open(sys.argv[1]).read() if len(sys.argv)>1 and os.path.exists(sys.argv[1]) else ""
for m in ordem:
    f=f"{SP}/saida/modelo_{m}.png"
    if not os.path.exists(f): continue
    rgba=np.asarray(Image.open(f).convert("RGBA").resize((w,h),Image.BILINEAR)).astype(np.float32); al=rgba[...,3:4]/255.0
    comp=np.clip(rgba[...,:3]*al+branco*(1-al),0,255).astype(np.uint8)
    linha=[l for l in log.splitlines() if l.startswith(m+":")]
    info=linha[0].split("|")[0].replace(m+":","").strip() if linha else ""
    tiles.append(rot(comp, f"{m}  {info}"))
# grade 2 colunas
if len(tiles)%2: tiles.append(np.full_like(tiles[0],20))
linhas=[np.concatenate(tiles[i:i+2],axis=1) for i in range(0,len(tiles),2)]
pan=Image.fromarray(np.concatenate(linhas,axis=0)); pan=pan.resize((1400,int(pan.height*1400/pan.width)),Image.BILINEAR)
pan.save(f"{SP}/saida/painel_modelos.jpg",quality=88); print("painel_modelos:", pan.size, "tiles:", len(tiles))
