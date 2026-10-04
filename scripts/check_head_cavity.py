"""Front-face mouth occlusion oracle using actual production rig pose snapshots."""
from pathlib import Path
import argparse,hashlib,json
import numpy as np
from tripo_head_adapter import Glb
from head_surface_topology import FrontSurface


def check(directory,cx,cy,half):
    root=Path(directory);g=Glb(root/'character.glb');data=json.loads((root/'production-poses.json').read_text('utf-8'))
    assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
    failures=[];checks=0
    for pose in data['poses']:
        if pose['name'] not in ('neutral','jaw-open','jaw-open-mouth-close','smile-jaw'):continue
        mesh=pose['meshes'][0];weights=dict(zip(mesh['targetNames'],mesh['weights']))
        node=next(n for n in pose['nodes'] if n['name']=='Face');world=np.array(node['worldMatrixColumnMajor']).reshape(4,4).T
        components=[]
        for primitive in g.doc['meshes'][0]['primitives']:
            p=g.read(primitive['attributes']['POSITION']).astype(float)
            for name,target in zip(g.doc['meshes'][0]['extras']['targetNames'],primitive['targets']):p+=g.read(target['POSITION'])*weights[name]
            p=(np.c_[p,np.ones(len(p))]@world.T)[:,:3];ids=g.read(primitive['indices']).reshape(-1,3)
            cross=np.cross(p[ids[:,1]]-p[ids[:,0]],p[ids[:,2]]-p[ids[:,0]])
            components.append((p,ids,cross,primitive['material']))
        for view,direction in [('front',np.array([0,0,1.])),('oblique',np.array([.5,0,1.])/np.sqrt(1.25))]:
            right=np.array([direction[2],0,-direction[0]]);basis=np.array([right,[0,1,0],direction]);attrs=[];faces=[];offset=0
            for p,ids,cross,material in components:
                projected=p@basis.T;attrs.extend(np.c_[projected,np.full(len(p),material)])
                front=(cross@direction)>1e-12;faces.extend(ids[front]+offset);offset+=len(p)
            sampler=FrontSurface(attrs,faces)
            opened = pose['name'] in ('jaw-open','smile-jaw')
            # The smile snapshot opens the jaw only 65%. Scale the probe's depth
            # with the actual exported jaw weight so it stays within that smaller
            # aperture. Closed poses keep the full-depth probes to check skin
            # coverage. These rays test occlusion, not lip-boundary location.
            aperture_scale = weights['jawOpen'] if opened else 1.0
            assert 0 < aperture_scale <= 1
            for fx in (-.30,0,.30):
                for dy in (.006,.009,.012):
                    # Aim from each view at the interior of the opened lip aperture.
                    target=np.array([cx+half*fx,cy-dy*aperture_scale,.075])@basis.T
                    expected=1 if opened else 0
                    try:sample=sampler.sample(*target[:2]);actual=round(sample[3])
                    except ValueError:actual=None
                    checks+=1
                    if actual!=expected:failures.append({'pose':pose['name'],'view':view,'fx':fx,'dy':dy,'aperture_scale':aperture_scale,'expected_material':expected,'actual_material':actual})
    report={'model_sha256':data['modelSha256'],'checks':checks,'failures':failures,'passed':not failures,
            'probe_depth_rule':'Opened poses scale 6/9/12mm depths by exported jawOpen; closed poses use full depth',
            'scope':'Front-facing ray occlusion at selected mouth samples for four actual production poses/two directions, not lip boundaries, all-view collision or art acceptance'}
    print(json.dumps(report,ensure_ascii=False));return report


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory');p.add_argument('--cx',type=float,required=True);p.add_argument('--cy',type=float,required=True);p.add_argument('--half',type=float,required=True);a=p.parse_args()
    raise SystemExit(0 if check(a.directory,a.cx,a.cy,a.half)['passed'] else 1)
