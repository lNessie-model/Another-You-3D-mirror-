"""Measure actual-pose front-surface foldovers without modifying authoring data."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from tripo_head_adapter import Glb
p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--out',required=True);a=p.parse_args()
root=Path(a.model);g=Glb(root/'character.glb');data=json.loads((root/'feedback-poses.json').read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
primitive=g.doc['meshes'][0]['primitives'][0];base=g.read(primitive['attributes']['POSITION']).astype(float)
ids=g.read(primitive['indices']).reshape(-1,3);b=base[ids]
normal=np.cross(b[:,1]-b[:,0],b[:,2]-b[:,0]);pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
center=b.mean(1)+pivot;area=normal[:,2]
region=(center[:,1]>.019)&(center[:,1]<.070)&(abs(center[:,0]-.005)<.058)&(center[:,2]>.06)&(area>1e-8)
rows=[]
for pose in data['poses']:
    if pose['name'] not in ('neutral','brow-mixed','brow-up','blink-brow-up','smile'):continue
    v=base.copy()
    for weight,target in zip(pose['meshes'][0]['weights'],primitive['targets']):
        if weight:v+=weight*g.read(target['POSITION'])
    t=v[ids];new=np.cross(t[:,1]-t[:,0],t[:,2]-t[:,0]);ratio=new[:,2]/np.where(abs(area)>1e-30,area,1)
    flips=np.flatnonzero(region&(ratio<0));records=[]
    for i in flips:records.append({'triangle':int(i),'neutralCenterWorld':center[i].tolist(),'deformedCenterWorld':(t[i].mean(0)+pivot).tolist(),
             'projectedAreaRatio':float(ratio[i]),'neutralAreaSquareMm':float(area[i]*.5*1e6)})
    rows.append({'pose':pose['name'],'sampledBrowFrontTriangles':int(region.sum()),'foldedFrontTriangles':len(flips),'minProjectedAreaRatio':float(ratio[region].min()),'folds':records})
report={'modelSha256':data['modelSha256'],'measurements':rows,'scope':'Positive-area neutral brow skin triangles changing projected winding in actual production feedback poses; projection reversal suggests a fold but does not alone prove self-intersection.'}
out=Path(a.out);assert not out.exists();out.write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))
