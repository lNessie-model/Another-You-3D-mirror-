"""Keep mandible opening while closing only the continuous lip edge."""
import argparse
from collections import deque
import hashlib
import json
from pathlib import Path

import numpy as np
from head_glb_edit import GlbEdit


def lip_loops(points,triangles,cy):
    lip=points[:,1]<cy+.03;triangles=triangles[lip[triangles].all(axis=1)]
    edges=np.sort(np.concatenate([triangles[:,[0,1]],triangles[:,[1,2]],triangles[:,[2,0]]]),axis=1)
    edges,counts=np.unique(edges,axis=0,return_counts=True)
    adjacent=[set() for _ in points];boundary=[set() for _ in points]
    for a,b in edges:adjacent[a].add(int(b));adjacent[b].add(int(a))
    for a,b in edges[counts==1]:boundary[a].add(int(b));boundary[b].add(int(a))
    remaining=set(np.unique(edges[counts==1]));loops=[]
    while remaining:
        first=remaining.pop();stack=[first];component=[first]
        while stack:
            for other in boundary[stack.pop()]:
                if other in remaining:remaining.remove(other);stack.append(other);component.append(other)
        loops.append(np.array(component,dtype=int))
    assert len(loops)==2 and all(len(loop)==96 for loop in loops)
    inner,outer=sorted(loops,key=lambda v:np.ptp(points[v,0]))
    distance=np.full(len(points),-1,dtype=int);queue=deque(outer.tolist());distance[outer]=0
    while queue:
        vertex=queue.popleft()
        for other in adjacent[vertex]:
            if distance[other]<0:distance[other]=distance[vertex]+1;queue.append(other)
    assert (distance[inner]==10).all() and (distance[lip]>=0).all()
    return inner,outer,distance,lip


def author(source,landmark_report,out):
    source=Path(source);out=Path(out);assert not out.exists()
    spec=json.loads(Path(landmark_report).read_text('utf-8'));cx,cy=spec['mouth_center']
    edit=GlbEdit(source/'character.glb');g=edit.source;mesh=g.doc['meshes'][0]
    names=mesh['extras']['targetNames'];ji=names.index('jawOpen');ci=names.index('correctiveJawOpenMouthClose');mi=names.index('mouthClose')
    pivot=np.array(g.doc['nodes'][1]['translation'])
    ring=mesh['primitives'][1];points=g.read(ring['attributes']['POSITION']).astype(float)+pivot
    j=g.read(ring['targets'][ji]['POSITION']).astype(float);m=g.read(ring['targets'][mi]['POSITION']).astype(float)
    inner,outer,distance,lip=lip_loops(points,g.read(ring['indices']).reshape(-1,3),cy)
    upper=inner[j[inner,1]>=-1e-7];lower=inner[j[inner,1]<-1e-7]
    corrected=np.zeros_like(points);closed=points+j+m
    for vertex in inner:
        opposite=lower if vertex in upper else upper
        other=int(opposite[np.argmin(abs(points[opposite,0]-points[vertex,0]))])
        if abs(points[other,0]-points[vertex,0])<1e-6:
            corrected[vertex]=(closed[other]-closed[vertex])*.5
    # Two sides are smooth functions of X; explicit corner anchors keep the seam ends fixed.
    lower_with_corners=np.r_[lower,inner[np.linalg.norm(j[inner],axis=1)<1e-7]]
    def sample(side,x):
        selected=upper if side else lower_with_corners;order=np.argsort(points[selected,0]);selected=selected[order]
        return np.array([np.interp(x,points[selected,0],corrected[selected,k]) for k in range(3)])
    order=np.argsort(points[upper,0]);xs=points[upper[order],0];line=points[upper[order],1]+m[upper[order],1]
    delta=np.zeros_like(points)
    for vertex in np.flatnonzero(lip):
        t=distance[vertex]/10;blend=t*t*(3-2*t)
        side=points[vertex,1]>=np.interp(points[vertex,0],xs,line)
        delta[vertex]=sample(side,points[vertex,0])*blend
    delta[inner]=corrected[inner] # No interpolation drift at the actual closure boundary.
    skin=mesh['primitives'][0]
    edit.doc['meshes'][0]['primitives'][0]['targets'][ci]['POSITION']=edit.add(np.zeros_like(g.read(skin['attributes']['POSITION'])),'VEC3')
    edit.doc['meshes'][0]['primitives'][1]['targets'][ci]['POSITION']=edit.add(delta,'VEC3')
    oral=mesh['primitives'][2];op=g.read(oral['attributes']['POSITION']).astype(float)+pivot
    colors=g.read(oral['attributes']['COLOR_0']);oj=g.read(oral['targets'][ji]['POSITION'])
    groups,counts=np.unique(colors,axis=0,return_counts=True)
    wall=groups[counts==96];wall=wall[np.argsort(wall[:,:3].mean(axis=1))[::-1]];assert len(wall)==3
    oral_delta=np.zeros_like(op)
    for group,fade in zip(wall,(1,.5,0)):
        for vertex in np.flatnonzero((colors==group).all(axis=1)):
            oral_delta[vertex]=sample(oj[vertex,1]>=-1e-7,op[vertex,0])*fade
    # Retain exact closure continuity at the wall's lip boundary.
    front=(colors==wall[0]).all(axis=1)
    for vertex in np.flatnonzero(front):
        other=inner[np.argmin(np.linalg.norm(points[inner]-op[vertex],axis=1))]
        assert np.linalg.norm(points[other]-op[vertex])<1e-7
        oral_delta[vertex]=corrected[other]
    edit.doc['meshes'][0]['primitives'][2]['targets'][ci]['POSITION']=edit.add(oral_delta,'VEC3')
    out.mkdir();target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-jaw-close'
    manifest['displayName']='杰洛特方向 · 闭唇与下颌解耦候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],
            'mouthCenter':[cx,cy],'halfWidth':spec['half_width'],'lipBoundaryVertices':len(inner),
            'maxLipCorrection':float(np.linalg.norm(delta,axis=1).max()),'artistAccepted':False,
            'scope':'Only jawOpen*mouthClose corrective changed; closed lips retain mandible opening, existing channels/maps unchanged'}
    (out/'jaw-close-authoring.json').write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--landmark-report',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();author(a.source,a.landmark_report,a.out)
