"""Independent geometric checks for the user's brow, smile and pointed-mouth feedback."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb


def check(directory,source,spec_path):
    g=Glb(Path(directory)/'character.glb');old=Glb(Path(source)/'character.glb');spec=json.loads(Path(spec_path).read_text('utf-8'))
    cx,cy=spec['mouth_center'];half=spec['half_width'];names=g.doc['meshes'][0]['extras']['targetNames']
    before_names=old.doc['meshes'][0]['extras']['targetNames']
    assert names[:len(before_names)]==before_names
    assert names[len(before_names):]==['correctiveBlinkBrowInnerUpLeft','correctiveBlinkBrowInnerUpRight']
    allowed={'browInnerUp','browDownLeft','browDownRight','mouthSmileLeft','mouthSmileRight','jawOpen','correctiveJawOpenMouthClose'}
    assert g.doc['nodes']==old.doc['nodes'] and g.doc['materials']==old.doc['materials']
    for mi,(a,b) in enumerate(zip(old.doc['meshes'],g.doc['meshes'])):
        assert len(a['primitives'])==len(b['primitives'])
        for pi,(pa,pb) in enumerate(zip(a['primitives'],b['primitives'])):
            for key,index in pa['attributes'].items():assert np.array_equal(old.read(index),g.read(pb['attributes'][key])),key
            assert np.array_equal(old.read(pa['indices']),g.read(pb['indices']))
            for name,ta,tb in zip(names,pa.get('targets',[]),pb.get('targets',[])):
                if mi==0 and name in allowed:continue
                if mi==0 and pi==1 and name in ('browDownLeft','browDownRight','browOuterUpLeft','browOuterUpRight'):continue
                for key,index in ta.items():assert np.array_equal(old.read(index),g.read(tb[key])),name
    for a,b in zip(old.doc['images'],g.doc['images']):
        va=old.doc['bufferViews'][a['bufferView']];vb=g.doc['bufferViews'][b['bufferView']]
        assert old.raw[old.start+va.get('byteOffset',0):old.start+va.get('byteOffset',0)+va['byteLength']]==g.raw[g.start+vb.get('byteOffset',0):g.start+vb.get('byteOffset',0)+vb['byteLength']]
    pr=g.doc['meshes'][0]['primitives'][1];before=old.doc['meshes'][0]['primitives'][1]
    p=g.read(pr['attributes']['POSITION']).astype(float)+np.array(g.doc['nodes'][1]['translation'])
    ids=g.read(pr['indices']).reshape(-1,3);ids=ids[(p[ids,1]<cy+.03).all(1)]
    edges=np.sort(np.r_[ids[:,[0,1]],ids[:,[1,2]],ids[:,[2,0]]],axis=1);edges,counts=np.unique(edges,axis=0,return_counts=True)
    boundary=np.unique(edges[counts==1]);inner=boundary[(abs(p[boundary,1]-cy)<.002)&(abs(p[boundary,0]-cx)<half*1.01)]
    assert len(inner)==96
    targets={name:g.read(t['POSITION']) for name,t in zip(names,pr['targets'])}
    original={name:old.read(t['POSITION']) for name,t in zip(names,before['targets'])}
    lower=inner[original['jawOpen'][inner,1]<-1e-7];upper=inner[original['jawOpen'][inner,1]>=-1e-7]
    q=abs(p[lower,0]-cx)/half;central=lower[q<.45];depth=-targets['jawOpen'][central,1]
    ratio=float(depth.min()/depth.max());assert ratio>.97,('Central lower rim still pointed',ratio)
    closed=p+targets['jawOpen']+targets['mouthClose']+targets['correctiveJawOpenMouthClose'];gaps=[]
    for v in lower:
        other=upper[np.argmin(abs(p[upper,0]-p[v,0]))];assert abs(p[other,0]-p[v,0])<1e-6
        gaps.append(float(np.linalg.norm(closed[v]-closed[other])))
    assert max(gaps)<2e-6,('Re-shaped lips do not close',max(gaps))
    assert np.array_equal(g.read(g.doc['meshes'][0]['primitives'][0]['targets'][names.index('jawOpen')]['POSITION']),old.read(old.doc['meshes'][0]['primitives'][0]['targets'][names.index('jawOpen')]['POSITION'])),'Mandible geometry changed'
    assert targets['mouthSmileLeft'][:,0].max()>.003 and targets['mouthSmileRight'][:,0].min()<-.003
    assert targets['mouthSmileLeft'][:,1].max()>.0045 and targets['mouthSmileRight'][:,1].max()>.0045
    for name in ['mouthSmileLeft','mouthSmileRight']:
        # Closed-mouth smiles must not separate corresponding neutral lip rims.
        moved=p+targets[name]
        for v in lower:
            other=upper[np.argmin(abs(p[upper,0]-p[v,0]))]
            # Neutral upper/lower lips differ in depth by up to 2.64 mm; their
            # frontal opening is the XY gap. Full jaw/mouth-close above checks 3D.
            assert np.linalg.norm(moved[v,:2]-moved[other,:2])<.0006,'Smile opens an unintended frontal lip seam'
    return {'passed':True,'modelSha256':hashlib.sha256(g.raw).hexdigest(),'centralLowerLipFlatnessRatio':ratio,
            'maxFullClosedLipGapMeters':max(gaps),'unchangedSourceDataVerified':True,
            'scope':'Local shape/boundary invariants, unchanged geometry/maps/bones and unrelated channels; no claim of naturalness, detector calibration, all-angle collisions or device acceptance'}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('directory');parser.add_argument('--source',required=True);parser.add_argument('--spec',required=True);parser.add_argument('--report')
    args=parser.parse_args();result=check(args.directory,args.source,args.spec)
    if args.report:
        p=Path(args.report);assert not p.exists();p.write_text(json.dumps(result,indent=2),'utf-8')
    print(json.dumps(result))
