"""Diagnostic only: suppress background triangles while retaining exact asset framing.

This is not a deliverable prop optimization. Original static positions remain so
the production bounds/fit and all head/atlas/morph bytes stay identical.
"""
from pathlib import Path
import argparse,copy,hashlib,json,struct
from tripo_head_adapter import Glb

def author(source,out):
    source=Path(source);out=Path(out)
    if out.exists():raise FileExistsError('Preserve prior diagnostic assets')
    g=Glb(source/'character.glb');doc=copy.deepcopy(g.doc)
    matches=[m for m in doc['meshes'] if m.get('name')=='StaticBackground']
    assert len(matches)==1 and len(matches[0]['primitives'])==1
    primitive=matches[0]['primitives'][0];assert not primitive.get('targets')
    old_count=doc['accessors'][primitive['indices']]['count']
    binary=bytearray(g.raw[g.start:g.start+doc['buffers'][0]['byteLength']])
    original=bytes(binary)
    while len(binary)%4:binary.append(0)
    view=len(doc['bufferViews']);doc['bufferViews'].append({'buffer':0,'byteOffset':len(binary),'byteLength':6})
    binary.extend(struct.pack('<HHH',0,0,0))
    primitive['indices']=len(doc['accessors'])
    doc['accessors'].append({'bufferView':view,'componentType':5123,'count':3,'type':'SCALAR'})
    doc['buffers'][0]['byteLength']=len(binary)
    assert bytes(binary[:len(original)])==original
    while len(binary)%4:binary.append(0)
    encoded=json.dumps(doc,separators=(',',':')).encode();encoded+=b' '*((-len(encoded))%4)
    raw=struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary
    out.mkdir(parents=True);(out/'character.glb').write_bytes(raw)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'))
    manifest['id']+='-background-draw-baseline';manifest['displayName']+=' · 背景不绘制对照'
    manifest['modelSha256']=hashlib.sha256(raw).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'source':str(source),'sourceSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],
      'originalBackgroundTriangles':old_count//3,'diagnosticBackgroundTriangles':1,'backgroundTriangleZeroArea':True,
      'originalBinaryPrefixIdentical':True,'staticPositionsRetained':True,'artistAccepted':False,
      'scope':'Diagnostic only; zero-area background draw preserves source bounds. Must verify production fit bitwise before an A/B run; not a shipped background optimization.'}
    (out/'diagnostic-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();author(a.source,a.out)
