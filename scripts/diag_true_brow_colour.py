"""Rank front-most Skin/Ring pigment samples; color alone is not a brow label.

The camera is the fixed 800px neutral review camera. Native Blender rays and
material inspection are required to confirm the warm gray inner eyebrow.
"""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from tripo_head_adapter import Glb
from head_surface_topology import FrontSurface
p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--out',required=True);a=p.parse_args();root=Path(a.source)
g=Glb(root/'character.glb');pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'));surfaces=[]
for primitive_index,pr in enumerate(g.doc['meshes'][0]['primitives'][:2]):
    vertices=g.read(pr['attributes']['POSITION'])+pivot;uv=g.read(pr['attributes']['TEXCOORD_0']);color=g.read(pr['attributes']['COLOR_0']);surfaces.append((primitive_index,pr,FrontSurface(np.c_[vertices,uv,color],g.read(pr['indices']).reshape(-1,3))))
rows=[];gray=[];center_x=.0004696696996688843;center_y=3.725290298461914e-9;scale=.2736000108718872
for side,low,high in [('LeftImage',300,397),('RightImage',412,500)]:
    for x in range(low,high,2):
        for y in range(285,326,2):
            world=[center_x+(x+.5-400)/800*scale,center_y+(400-y-.5)/800*scale]
            hits=[]
            for primitive_index,pr,surface in surfaces:
                try:sample=surface.sample(*world)
                except ValueError:continue
                if sample[2]>.04:hits.append((float(sample[2]),primitive_index,pr,sample))
            if not hits:continue
            _,primitive_index,pr,sample=max(hits,key=lambda r:r[0]);rgb=g.sample_base_colour(sample[3:5][None],pr['material'])[0,:3]*sample[5:8]
            srgb=np.where(rgb<=.0031308,rgb*12.92,1.055*np.maximum(rgb,0)**(1/2.4)-.055);sat=(srgb.max()-srgb.min())/max(srgb.max(),1e-9)
            if sat<.16 and .20<srgb.max()<.94:
                row={'side':side,'primitive':primitive_index,'pixel':[x,y],'world':sample[:3].tolist(),'baseColorSRGB':srgb.tolist(),'saturation':float(sat)};gray.append(row)
    own=[r for r in gray if r['side']==side];inner=sorted(own,key=lambda r:r['pixel'][0],reverse=side=='LeftImage')[:25];rows.append({'side':side,'grayCount':len(own),'innerSamples':inner})
out=Path(a.out);assert not out.exists();out.write_text(json.dumps({'modelSha256':hashlib.sha256(g.raw).hexdigest(),'rows':rows,'grayPixels':gray,'scope':'Front-most Skin/Ring color ranking from the fixed neutral camera; not automatic eyebrow identification or artistic acceptance.'},indent=2),'utf-8');print(json.dumps({'rows':rows}))
