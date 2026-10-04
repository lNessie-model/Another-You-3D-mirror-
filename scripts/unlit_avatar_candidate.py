"""Create a separate flat-colour material candidate without editing geometry/atlas.

Original normal policy and functional morph bindings are unchanged. Style
acceptance and actual-board performance are separate.
"""
from pathlib import Path
import argparse,copy,hashlib,json,struct
from tripo_head_adapter import Glb


def author(source,out):
    source=Path(source);out=Path(out)
    if out.exists():raise FileExistsError('Preserve prior material candidates')
    g=Glb(source/'character.glb');doc=copy.deepcopy(g.doc)
    for material in doc['materials']:
        material.setdefault('extensions',{})['KHR_materials_unlit']={}
    used=doc.setdefault('extensionsUsed',[])
    if 'KHR_materials_unlit' not in used:used.append('KHR_materials_unlit')
    binary=g.raw[g.start:];encoded=json.dumps(doc,separators=(',',':')).encode()
    encoded+=b' '*((-len(encoded))%4)
    raw=struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary
    out.mkdir(parents=True);(out/'character.glb').write_bytes(raw)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'))
    manifest['id']+='-unlit';manifest['displayName']+=' · 直接颜色候选'
    manifest['modelSha256']=hashlib.sha256(raw).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'source':str(source),'source_sha256':hashlib.sha256(g.raw).hexdigest(),'sha256':manifest['modelSha256'],
      'binary_identical':True,'all_materials_unlit':True,'normal_policy':manifest['normalPolicy'],'artistAccepted':False,
      'scope':'Independent flat-colour style candidate; original mesh, atlas and morph bytes retained exactly. Device shader/FPS and visual acceptance required.'}
    (out/'material-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--source',required=True);a.add_argument('--out',required=True)
    v=a.parse_args();author(v.source,v.out)
