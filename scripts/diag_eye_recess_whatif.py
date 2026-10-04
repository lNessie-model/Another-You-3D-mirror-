"""Read-only hypothetical eye-node recession, retaining all real Face data."""
import argparse,copy,hashlib,json,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tests'))
from check_head_ocular import Glb,visible_eye,triangles

def fixed_grid_visible(g,pose,direction,centers):
    all_v,_,all_tag,_=triangles(g,pose,direction);rows=[]
    for tag,center in centers.items():
        probes=np.stack(np.meshgrid(center[0]+np.linspace(-.012,.012,65),center[1]+np.linspace(-.007,.007,33)),axis=-1).reshape(-1,2)
        low=probes.min(0);high=probes.max(0);relevant=(all_v[:,:,:2].max(1)>=low).all(1)&(all_v[:,:,:2].min(1)<=high).all(1)
        v=all_v[relevant];tags=all_tag[relevant];a=v[:,0,:2];ab=v[:,1,:2]-a;ac=v[:,2,:2]-a;det=ab[:,0]*ac[:,1]-ab[:,1]*ac[:,0]
        count=0
        for begin in range(0,len(probes),96):
            q=probes[begin:begin+96,None,:]-a;s=(q[:,:,0]*ac[:,1]-q[:,:,1]*ac[:,0])/det;t=(ab[:,0]*q[:,:,1]-ab[:,1]*q[:,:,0])/det
            inside=(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001);z=v[:,0,2]+s*(v[:,1,2]-v[:,0,2])+t*(v[:,2,2]-v[:,0,2]);z[~inside]=-np.inf
            hit=z.argmax(1);ri=np.arange(len(hit));count+=int(((tags[hit]==tag)&np.isfinite(z[ri,hit])).sum())
        rows.append({'side':'Left' if tag==1 else 'Right','eyeProbes':count})
    return rows

p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--poses',required=True);p.add_argument('--out',required=True);a=p.parse_args()
g=Glb(Path(a.model)/'character.glb');data=json.loads(Path(a.poses).read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest();results=[]
for delta in (.0003,.0006,.0010):
    rows=[]
    for original in data['poses']:
        pose=copy.deepcopy(original)
        for node in pose['nodes']:
            if node['name'] in ('LeftEye','RightEye'):
                matrix=node['worldMatrixColumnMajor']
                for row in range(3):matrix[12+row]-=delta*matrix[8+row]
        for view,direction in [('front',[0,0,1]),('right',[.45,0,1]),('left',[-.45,0,1])]:
            visible=visible_eye(g,pose,direction)
            _,_,_,old_centers=triangles(g,original,direction)
            fixed=fixed_grid_visible(g,pose,direction,old_centers)
            rows.append({'pose':original['name'],'view':view,'recenteredProbeVisible':[e['eyeProbes'] for e in visible],
                         'originalFixedProbeVisible':[e['eyeProbes'] for e in fixed]})
    result={'eyeLocalRecessionMeters':delta,'passedRecentered':all(not any(r['recenteredProbeVisible']) for r in rows),
            'passedOriginalFixedGrid':all(not any(r['originalFixedProbeVisible']) for r in rows),'measurements':rows}
    results.append(result);print(json.dumps(result),flush=True)
report={'unchangedFaceModelSha256':data['modelSha256'],'results':results,'scope':'Hypothetical world matrices only, no file mutation; both recentered and original grids detect sampling aliasing; not a real authored-asset or production proof.'}
out=Path(a.out);assert not out.exists();out.write_text(json.dumps(report,indent=2),'utf-8')
