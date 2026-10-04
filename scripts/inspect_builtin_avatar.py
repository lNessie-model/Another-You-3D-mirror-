"""Independent GLB/manifest checks and a contact sheet of ordinary Blender renders.

Run with the bundled host Python (numpy + Pillow); no network or device is required.
This checks the exported bytes, not the builder's intermediate Python objects.
"""
from __future__ import annotations

import hashlib
import argparse
import itertools
import json
import struct
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
PACKAGE=ROOT/'app/src/main/assets/avatars/builtin-guide'
OUT=ROOT/'app/build/builtin-guide-review'


def inspect():
    raw=(PACKAGE/'character.glb').read_bytes()
    manifest=json.loads((PACKAGE/'avatar.json').read_text(encoding='utf-8'))
    assert struct.unpack_from('<III',raw,0)==(0x46546c67,2,len(raw))
    length,kind=struct.unpack_from('<II',raw,12);assert kind==0x4e4f534a
    doc=json.loads(raw[20:20+length]);start=28+length
    bin_length,bin_kind=struct.unpack_from('<II',raw,20+length)
    assert bin_kind==0x004e4942 and start+bin_length==len(raw)
    assert manifest['modelSha256']==hashlib.sha256(raw).hexdigest()
    assert len(doc['buffers'])==1 and doc['buffers'][0]['byteLength']<=bin_length
    assert not any(k in doc for k in ('skins','animations','textures','images'))
    components={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
    types={5121:'<u1',5123:'<u2',5125:'<u4',5126:'<f4'}

    def accessor(index):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        dtype=np.dtype(types[a['componentType']]);width=components[a['type']]
        offset=v.get('byteOffset',0)+a.get('byteOffset',0)
        step=v.get('byteStride',width*dtype.itemsize)
        assert a.get('byteOffset',0)+(a['count']-1)*step+width*dtype.itemsize<=v['byteLength']
        assert offset+(a['count']-1)*step+width*dtype.itemsize<=doc['buffers'][0]['byteLength']
        return np.ndarray((a['count'],width),dtype=dtype,buffer=raw,offset=start+offset,strides=(step,dtype.itemsize)).copy()

    report={'passed':True,'glbSha256':manifest['modelSha256'],'fileBytes':len(raw),'meshes':[],
            'validator':'independent exported-byte numeric/topology checks; not Khronos glTF Validator',
            'coverage':{'authoredMorphActions':43,'rigidGazeActions':8,'neutralIgnored':True,
                        'derivedGeometryTargets':len(manifest.get('derivedBindings',[])),
                        'incompleteActions':manifest['incompleteSources'],'full52ExpressionArtAcceptance':False}}
    by_name={m['name']:m for m in doc['meshes']}
    total_vertices=total_triangles=primitive_count=0
    all_targets=set()
    for mesh in doc['meshes']:
        name=mesh['name'];names=mesh.get('extras',{}).get('targetNames',[])
        assert len(set(names))==len(names)
        for p in mesh['primitives']:
            primitive_count+=1
            vertices=accessor(p['attributes']['POSITION']).astype(np.float64)
            normal=accessor(p['attributes']['NORMAL']).astype(np.float64)
            color=accessor(p['attributes']['COLOR_0'])
            ids=accessor(p['indices']).reshape(-1,3)
            assert np.isfinite(vertices).all() and np.isfinite(normal).all() and np.isfinite(color).all()
            assert np.max(np.abs(np.linalg.norm(normal,axis=1)-1))<2e-6
            assert color.min()>=0 and color.max()<=1 and ids.max()<len(vertices)
            double_areas=np.linalg.norm(np.cross(vertices[ids[:,1]]-vertices[ids[:,0]],vertices[ids[:,2]]-vertices[ids[:,0]]),axis=1)
            assert (double_areas>0).all(),(name,'zero area exported triangle')
            edges=Counter(tuple(sorted((int(t[i]),int(t[j])))) for t in ids for i,j in ((0,1),(1,2),(2,0)))
            assert max(edges.values())<=2,(name,'nonmanifold edge')
            targets=p.get('targets',[]);assert len(targets)==len(names)
            nonzero={};deltas=[]
            for target_name,target in zip(names,targets):
                delta=accessor(target['POSITION']).astype(np.float64);assert delta.shape==vertices.shape and np.isfinite(delta).all()
                for weight in (0.,.5,1.):assert np.isfinite(vertices+delta*weight).all()
                nonzero[target_name]=int(np.any(delta!=0,axis=1).sum());deltas.append(delta)
            if name=='Face':
                assert all(v>0 for v in nonzero.values()),'every declared facial action needs actual nonzero geometry'
                for side in ('Left','Right'):
                    selected=mesh['extras']['validationGroups'][side]
                    other=mesh['extras']['validationGroups']['Right' if side=='Left' else 'Left']
                    delta=deltas[names.index('eyeBlink'+side)]
                    assert np.max(abs(delta[other]))==0,'blink leaked to opposite eye'
                    assert np.ptp((vertices+delta)[selected,1])<.0003,'lid did not close'
                all_targets.update(names)
                if manifest['schemaVersion']==2:
                    report['exportedEyeSeams']=[]
                    by_target=dict(zip(names,deltas))
                    for side in ('Left','Right'):
                        rim=mesh['extras']['validationGroups'][side]
                        maximum=0.;poses=0
                        for wide,squint in itertools.product(np.linspace(0,1,11),repeat=2):
                            source={'eyeBlink'+side:1.,'eyeWide'+side:wide,'eyeSquint'+side:squint}
                            weights=dict(source)
                            for entry in manifest['derivedBindings']:
                                if entry['mesh']=='Face':
                                    weights[entry['target']]=np.prod([source.get(s,0.) for s in entry['sources']])*entry['gain']
                            pose=vertices.copy()
                            for target in names:pose+=by_target[target]*weights.get(target,0.)
                            pose=pose.astype(np.float32)
                            for k in range(1,32):
                                maximum=max(maximum,float(np.linalg.norm(pose[rim[k]]-pose[rim[64-k]])))
                            poses+=1
                        assert maximum<1e-7,(side,'exported closed-eye seam',maximum)
                        report['exportedEyeSeams'].append({'side':side,'poses':poses,'maxClosedPairDistanceMeters':maximum})
            report['meshes'].append({'name':name,'vertices':len(vertices),'triangles':len(ids),'morphs':len(names),
                                    'boundaryEdges':sum(c==1 for c in edges.values()),'changedVerticesByTarget':nonzero})
            total_vertices+=len(vertices);total_triangles+=len(ids)
    assert primitive_count<=8 and total_vertices<=20_000 and total_triangles<=30_000 and len(raw)<=32*1024*1024
    for binding in manifest['bindings']:
        assert binding['mesh'] in by_name
        assert binding['target'] in by_name[binding['mesh']]['extras']['targetNames']
        assert binding['source']==binding['target']
    for binding in manifest.get('derivedBindings',[]):
        assert binding['operation']=='product' and len(binding['sources']) in (2,3)
        assert binding['mesh'] in by_name
        assert binding['target'] in by_name[binding['mesh']]['extras']['targetNames']
        assert len(set(binding['sources']))==len(binding['sources'])
        assert all(source in all_targets and not source.startswith('corrective_') for source in binding['sources'])
    nodes={n['name']:n for n in doc['nodes']};assert len(nodes)==len(doc['nodes'])
    assert np.allclose(nodes['Head']['translation'],manifest['coordinates']['headPivot'],atol=1e-8)
    assert nodes['Head']['children']==[2,3,4,5,6,7] and doc['nodes'][8]['name']=='Shoulders'
    report.update(vertices=total_vertices,triangles=total_triangles,primitives=primitive_count,
                  bindings=len(manifest['bindings']),nonGazeActionNames=sorted(all_targets))
    (OUT/'exported-glb-check.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    return report


def contact_sheet():
    cases=[('neutral','Neutral'),('left-blink','Subject left blink'),('jaw-open','Jaw open'),
           ('smile','Independent smiles'),('three-quarter','Three-quarter geometry'),('gaze-left','Rigid eye gaze')]
    tile_w,tile_h,label_h=360,450,42
    sheet=Image.new('RGB',(tile_w*3,(tile_h+label_h)*2),(30,35,38))
    draw=ImageDraw.Draw(sheet)
    try:font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',19)
    except OSError:font=ImageFont.load_default()
    for index,(name,label) in enumerate(cases):
        pic=Image.open(OUT/(name+'.png')).convert('RGB').resize((tile_w,tile_h),Image.Resampling.LANCZOS)
        x=(index%3)*tile_w;y=(index//3)*(tile_h+label_h)
        sheet.paste(pic,(x,y));draw.text((x+14,y+tile_h+10),label,fill=(230,236,236),font=font)
    sheet.save(OUT/'contact-sheet.png')
    Image.open(OUT/'neutral.png').resize((288,360),Image.Resampling.LANCZOS).save(PACKAGE/'thumbnail.png')


def combination_contact_sheets():
    mouth=[(f'jaw{j:03d}-close{c:03d}',f'Jaw {j/100:g} / Close {c/100:g}')
           for j,c in itertools.product((0,50,100),repeat=2)]
    eyes=[('extreme-Left','Left: blink + wide + squint'),('extreme-Right','Right: blink + wide + squint'),
          ('eyes-half-wide-squint','Both: blink .5\nwide 1 / squint 1'),
          ('all-closed-yaw-left','Closed\nyaw +25 / pitch +12'),
          ('all-closed-yaw-right','Closed\nyaw -25 / pitch -12'),
          ('open-smile-quarter','Jaw 1 / close .5 / smile .6'),
          ('closed-smile-quarter','Jaw 1 / close 1 / smile .6')]
    try:font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',18)
    except OSError:font=ImageFont.load_default()
    for filename,cases in (('mouth-contact-sheet.png',mouth),('eyes-pose-contact-sheet.png',eyes)):
        w,h,caption=320,400,58
        sheet=Image.new('RGB',(w*3,(h+caption)*((len(cases)+2)//3)),(29,33,36));draw=ImageDraw.Draw(sheet)
        for i,(name,label) in enumerate(cases):
            x=i%3*w;y=i//3*(h+caption)
            pic=Image.open(OUT/(name+'.png')).convert('RGB').resize((w,h),Image.Resampling.LANCZOS)
            sheet.paste(pic,(x,y));draw.multiline_text((x+8,y+h+7),label,font=font,fill=(238,238,238),spacing=3)
        sheet.save(OUT/filename)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--package',type=Path);parser.add_argument('--report-dir',type=Path)
    parser.add_argument('--no-contact-sheet',action='store_true');options=parser.parse_args()
    if options.package:PACKAGE=options.package.resolve()
    if options.report_dir:OUT=options.report_dir.resolve()
    OUT.mkdir(parents=True,exist_ok=True)
    result=inspect()
    if not options.no_contact_sheet:
        contact_sheet()
        if result['coverage']['derivedGeometryTargets']:combination_contact_sheets()
    print(json.dumps({k:result[k] for k in ('passed','glbSha256','fileBytes','vertices','triangles','primitives','bindings')},indent=2))
