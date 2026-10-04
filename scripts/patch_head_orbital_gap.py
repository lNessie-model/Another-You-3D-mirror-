"""Add only source-attached nasal orbital closure faces; retain every old field."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from head_glb_edit import GlbEdit

def author(source,out):
    source=Path(source);out=Path(out);assert not out.exists()
    edit=GlbEdit(source/'character.glb');g=edit.source;skin=g.doc['meshes'][0]['primitives'][0];ring=g.doc['meshes'][0]['primitives'][1]
    before=len(g.read(ring['attributes']['POSITION']));assert before==3001
    clones=[11829,11904];newids={vertex:before+i for i,vertex in enumerate(clones)}
    actual=edit.doc['meshes'][0]['primitives'][1];white_uv=g.read(ring['attributes']['TEXCOORD_0'])[0]
    white=g.sample_base_colour(white_uv[None,:],ring['material'])[0,:3]
    sampled=g.sample_base_colour(g.read(skin['attributes']['TEXCOORD_0'])[clones],skin['material'])
    colors=g.read(skin['attributes']['COLOR_0'])[clones].copy()
    colors[:,:3]=np.clip(sampled[:,:3]*colors[:,:3]/white,0,1)
    for name,index in ring['attributes'].items():
        old=g.read(index);extra=g.read(skin['attributes'][name])[clones].copy()
        if name=='TEXCOORD_0':extra[:]=white_uv
        elif name=='COLOR_0':extra=colors
        actual['attributes'][name]=edit.add(np.r_[old,extra],{'POSITION':'VEC3','NORMAL':'VEC3','TEXCOORD_0':'VEC2','COLOR_0':'VEC4'}[name])
    for original,target,skin_target in zip(ring['targets'],actual['targets'],skin['targets']):
        assert original.keys()=={'POSITION'} and skin_target.keys()=={'POSITION'}
        target['POSITION']=edit.add(np.r_[g.read(original['POSITION']),g.read(skin_target['POSITION'])[clones]],'VEC3')
    added=np.array([[newids[11829],2304,2303],[newids[11829],2303,newids[11904]]],dtype=np.uint16)
    oldindices=g.read(ring['indices']).reshape(-1,3);actual['indices']=edit.add(np.r_[oldindices,added].reshape(-1),'SCALAR')
    out.mkdir();target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-orbital-closure';manifest['displayName']+=' · 鼻侧眶缝闭合'
    manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest();(out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    vertices=triangles=draws=0
    for node in edit.doc['nodes']:
        if 'mesh' not in node:continue
        for primitive in edit.doc['meshes'][node['mesh']]['primitives']:
            vertices+=edit.doc['accessors'][primitive['attributes']['POSITION']]['count'];triangles+=edit.doc['accessors'][primitive['indices']]['count']//3;draws+=1
    report={'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],'sourceDirectory':str(source.resolve()),
            'sourceRingVertices':before,'skinVertexClones':[{'sourcePrimitive':0,'sourceVertex':v,'targetPrimitive':1,'targetVertex':newids[v]} for v in clones],
            'addedTriangles':added.tolist(),'addedVertices':len(clones),'instanceVertices':vertices,'instanceTriangles':triangles,'draws':draws,
            'skinWhiteUv':white_uv.tolist(),'embeddedPbrMapsPreserved':True,'originalGeometryMorphsNodesAndBindingsPreserved':True,
            'scope':'Two source-skin clones and two local nasal bridge faces; no original vertices, targets, rig nodes, maps, or bindings edited. Requires independent preservation and actual closure validation.',
            'artistAccepted':False,'deviceInstalled':False,'tripoCreditsSpent':0}
    (out/'orbital-gap-patch.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--out',required=True);a=p.parse_args();author(a.source,a.out)
