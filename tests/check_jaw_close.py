"""Geometric requirements: closing lips must not undo an opened mandible."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb


def check(directory,source,landmark_report):
    root=Path(directory);g=Glb(root/'character.glb');old=Glb(Path(source)/'character.glb')
    spec=json.loads(Path(landmark_report).read_text('utf-8'));cx,cy=spec['mouth_center'];half=spec['half_width']
    names=g.doc['meshes'][0]['extras']['targetNames'];jaw=names.index('jawOpen');close=names.index('correctiveJawOpenMouthClose')
    pr=g.doc['meshes'][0]['primitives'][0];p=g.read(pr['attributes']['POSITION']).astype(float)
    p+=np.array(g.doc['nodes'][1]['translation']);j=g.read(pr['targets'][jaw]['POSITION']);c=g.read(pr['targets'][close]['POSITION'])
    chin=(p[:,1]<cy-.014)&(p[:,1]>cy-.042)&(p[:,2]>.035)&(abs(p[:,0]-cx)<half*1.3)&(np.linalg.norm(j,axis=1)>.0005)
    assert chin.sum()>20,'No meaningful mandible probes'
    error=float(np.linalg.norm(c[chin],axis=1).max())
    assert error<2e-6,f'Closed lips cancel mandible motion: {error:.6f}m at {chin.sum()} chin vertices'
    # Asset preservation: every unchanged facial channel and attribute stays exact.
    for i in (0,1):
        a=old.doc['meshes'][0]['primitives'][i];b=g.doc['meshes'][0]['primitives'][i]
        for key,index in a['attributes'].items():assert np.array_equal(old.read(index),g.read(b['attributes'][key])),key
        assert np.array_equal(old.read(a['indices']),g.read(b['indices']))
        for name,ta,tb in zip(names,a['targets'],b['targets']):
            if name!='correctiveJawOpenMouthClose':
                for key,index in ta.items():assert np.array_equal(old.read(index),g.read(tb[key])),name
    ring=g.doc['meshes'][0]['primitives'][1];rp=g.read(ring['attributes']['POSITION']).astype(float)
    rp+=np.array(g.doc['nodes'][1]['translation']);tri=g.read(ring['indices']).reshape(-1,3)
    lip=rp[:,1]<cy+.03;tri=tri[lip[tri].all(axis=1)]
    edges=np.sort(np.concatenate([tri[:,[0,1]],tri[:,[1,2]],tri[:,[2,0]]]),axis=1)
    edges,counts=np.unique(edges,axis=0,return_counts=True);boundary=np.unique(edges[counts==1])
    # The neutral inner lip edge is the narrow boundary around the seam.
    inner=boundary[(abs(rp[boundary,1]-cy)<.002)&(abs(rp[boundary,0]-cx)<half*1.01)]
    assert len(inner)==96,'Wrong inner lip boundary'
    targets={name:g.read(t['POSITION']) for name,t in zip(names,ring['targets'])}
    opened=rp+targets['jawOpen'];closed=opened+targets['mouthClose']+targets['correctiveJawOpenMouthClose']
    upper=inner[targets['jawOpen'][inner,1]>=-1e-7];lower=inner[targets['jawOpen'][inner,1]<-1e-7]
    gaps=[]
    for vertex in lower:
        other=int(upper[np.argmin(abs(rp[upper,0]-rp[vertex,0]))])
        assert abs(rp[other,0]-rp[vertex,0])<1e-6
        gaps.append(float(np.linalg.norm(closed[vertex]-closed[other])))
    assert len(gaps)>40 and max(gaps)<2e-6,('Closed lip edge has a gap',max(gaps))
    # The final closed seam moves with the opened jaw instead of returning to rest.
    seam_drop=float(np.median(closed[inner,1]-rp[inner,1]));assert seam_drop<-.003,seam_drop
    def image(model,i):
        v=model.doc['bufferViews'][model.doc['images'][i]['bufferView']];a=model.start+v.get('byteOffset',0)
        return model.raw[a:a+v['byteLength']]
    for i in range(3):assert image(g,i)==image(old,i),'Source PBR image changed'
    return {'modelSha256':hashlib.sha256(g.raw).hexdigest(),'chinProbes':int(chin.sum()),
            'mandibleCancellationError':error,'closedLipPairs':len(gaps),'maxClosedSeamGap':max(gaps),
            'medianClosedSeamDrop':seam_drop,'unchangedFaceAttributesAndOtherChannels':True,'mapsByteExact':True,
            'scope':'Selected chin region and full jawOpen+mouthClose lip boundary; not all-expression collision or art acceptance'}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--asset',required=True);parser.add_argument('--source',required=True)
    parser.add_argument('--landmark-report',required=True);parser.add_argument('--report')
    args=parser.parse_args();result=check(args.asset,args.source,args.landmark_report)
    if args.report:
        p=Path(args.report);assert not p.exists();p.write_text(json.dumps(result,indent=2),'utf-8')
    print(json.dumps(result))
