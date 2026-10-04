"""Independent preservation checks for isolated, unaccepted brow candidates."""
import argparse, hashlib, json, sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb

def check(source, directory):
    source=Path(source);root=Path(directory);old=Glb(source/'character.glb');new=Glb(root/'character.glb')
    sha=hashlib.sha256(new.raw).hexdigest();comparisons=0;changed=[]
    for key in ('nodes','materials','scenes','scene','images','textures','samplers','extensionsUsed','extensionsRequired'):
        assert old.doc.get(key)==new.doc.get(key),key
    assert len(old.doc['meshes'])==len(new.doc['meshes'])
    for mi,(before,after) in enumerate(zip(old.doc['meshes'],new.doc['meshes'])):
        assert {k:v for k,v in before.items() if k!='primitives'}=={k:v for k,v in after.items() if k!='primitives'}
        assert len(before['primitives'])==len(after['primitives'])
        names=before.get('extras',{}).get('targetNames',[])
        for pi,(a,b) in enumerate(zip(before['primitives'],after['primitives'])):
            assert {k:v for k,v in a.items() if k not in ('attributes','targets')}=={k:v for k,v in b.items() if k not in ('attributes','targets')}
            assert a['attributes']==b['attributes'] and len(a.get('targets',[]))==len(b.get('targets',[]))
            for name,index in a['attributes'].items():
                assert np.array_equal(old.read(index),new.read(b['attributes'][name])),(mi,pi,name);comparisons+=1
            assert np.array_equal(old.read(a['indices']),new.read(b['indices']));comparisons+=1
            for ti,(ta,tb) in enumerate(zip(a.get('targets',[]),b.get('targets',[]))):
                assert ta.keys()==tb.keys()
                for name,index in ta.items():
                    expected=old.read(index);actual=new.read(tb[name]);allow=mi==0 and pi in (0,1) and names[ti]=='browInnerUp' and name=='POSITION'
                    if allow:
                        assert expected.shape==actual.shape and np.isfinite(actual).all()
                        changed.append({'mesh':mi,'primitive':pi,'target':names[ti],'verticesChanged':int(np.count_nonzero(np.any(expected!=actual,axis=1)))})
                    else:
                        assert ta[name]==tb[name] and np.array_equal(expected,actual),(mi,pi,ti,name);comparisons+=1
    assert len(changed)==2
    for a,b in zip(old.doc['images'],new.doc['images']):
        av=old.doc['bufferViews'][a['bufferView']];bv=new.doc['bufferViews'][b['bufferView']]
        ao=old.start+av.get('byteOffset',0);bo=new.start+bv.get('byteOffset',0)
        assert old.raw[ao:ao+av['byteLength']]==new.raw[bo:bo+bv['byteLength']]
    ma=json.loads((source/'avatar.json').read_text('utf-8'));mb=json.loads((root/'avatar.json').read_text('utf-8'));ignore={'id','displayName','modelSha256'}
    assert {k:v for k,v in ma.items() if k not in ignore}=={k:v for k,v in mb.items() if k not in ignore}
    assert mb['modelSha256']==sha
    mesh=new.doc['meshes'][0];index=mesh['extras']['targetNames'].index('browInnerUp');skin=mesh['primitives'][0];ring=mesh['primitives'][1]
    ring_up=new.read(ring['targets'][index]['POSITION']);skin_up=new.read(skin['targets'][index]['POSITION'])
    pivot=np.array(next(n['translation'] for n in new.doc['nodes'] if n['name']=='Head'))
    positions=new.read(ring['attributes']['POSITION'])+pivot
    for sign in (1,-1):
        ids=np.flatnonzero((positions[:2080,1]>.01)&(sign*positions[:2080,0]>.004));assert len(ids)==512
        assert not ring_up[ids[-64:]].any()
    assert np.array_equal(ring_up[3001:3003],skin_up[[11829,11904]])
    instances=[new.doc['meshes'][n['mesh']] for n in new.doc['nodes'] if 'mesh' in n]
    vertices=sum(len(new.read(p['attributes']['POSITION'])) for m in instances for p in m['primitives'])
    triangles=sum(len(new.read(p['indices']))//3 for m in instances for p in m['primitives'])
    draws=sum(len(m['primitives']) for m in instances)
    assert len(new.raw)<32*1024*1024 and vertices<20000
    closure=json.loads((root/'extra-blink-check.json').read_text('utf-8'));assert closure['modelSha256']==sha
    failed=[{'pose':r['pose'],'view':r['view'],'eyes':[e for e in r['eyes'] if e['eyeProbes']]} for r in closure['measurements'] if any(e['eyeProbes'] for e in r['eyes'])]
    return {'sourceModelSha256':hashlib.sha256(old.raw).hexdigest(),'modelSha256':sha,'preservationPassed':True,'candidateAccepted':False,
            'unchangedFieldComparisons':comparisons,'changedFields':changed,'innerLidUpDeltaMeters':0,'closurePatchCloneDeltaErrorMeters':0,
            'embeddedMapsMaterialsNodesBindingsAllOtherTargetsExact':True,'fileBytes':len(new.raw),'instanceVertices':vertices,'instanceTriangles':triangles,'draws':draws,
            'strictExtraClosurePassed':closure['passed'],'failedClosureCases':failed,
            'scope':'A preservation pass is not acceptance. Candidate also requires visible natural brow improvement and zero eye leakage in all actual combined poses.'}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--model',required=True);p.add_argument('--out',required=True);a=p.parse_args()
    report=check(a.source,a.model);out=Path(a.out);assert not out.exists();out.write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))
