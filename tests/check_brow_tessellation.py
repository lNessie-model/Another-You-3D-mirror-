"""Independent brow asset regression: source-affine subdivision and actual mixed poses.

Positive parent partitions prove non-Up surfaces, not just matching source arrays.
Dynamic smooth-deformed-v1 normals can change after subdivision; pixels are not invariant.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb
from check_head_ocular import point_surface_distances,visible_eye

ALLOWED={'browInnerUp','correctiveBlinkBrowInnerUpLeft','correctiveBlinkBrowInnerUpRight'}


def arrays(g,primitive):
    return {k:g.read(v) for k,v in primitive['attributes'].items()}


def deformed(g,primitive,weights,names,exclude=()):
    p=g.read(primitive['attributes']['POSITION']).astype(float)
    for name,weight,target in zip(names,weights,primitive.get('targets',[])):
        if weight and name not in exclude:p+=weight*g.read(target['POSITION'])
    return p


def determinant(p,ids):
    v=p[ids];a=v[:,1,:2]-v[:,0,:2];b=v[:,2,:2]-v[:,0,:2]
    return a[:,0]*b[:,1]-a[:,1]*b[:,0]


def check(candidate,source,pose_files,require_full_source_restore=False,preservation_only=False):
    root=Path(candidate);base=Path(source);g=Glb(root/'character.glb');old=Glb(base/'character.glb')
    assert g.doc['nodes']==old.doc['nodes']
    for key in ('materials','textures','samplers','scenes','scene','skins','animations'):
        assert g.doc.get(key)==old.doc.get(key),key
    assert len(g.doc['meshes'])==len(old.doc['meshes']) and len(g.doc['images'])==len(old.doc['images'])
    for a,b in zip(old.doc['images'],g.doc['images']):
        assert {k:v for k,v in a.items() if k!='bufferView'}=={k:v for k,v in b.items() if k!='bufferView'}
        va=old.doc['bufferViews'][a['bufferView']];vb=g.doc['bufferViews'][b['bufferView']]
        assert old.raw[old.start+va.get('byteOffset',0):old.start+va.get('byteOffset',0)+va['byteLength']]==g.raw[g.start+vb.get('byteOffset',0):g.start+vb.get('byteOffset',0)+vb['byteLength']]
    ma=json.loads((base/'avatar.json').read_text('utf-8'));mb=json.loads((root/'avatar.json').read_text('utf-8'))
    for key,value in ma.items():
        if key not in ('id','displayName','modelSha256'):assert mb[key]==value,key
    assert mb['modelSha256']==hashlib.sha256(g.raw).hexdigest()
    names=old.doc['meshes'][0]['extras']['targetNames'];changed=[];comparisons=0
    skin=old.doc['meshes'][0]['primitives'][0];newskin=g.doc['meshes'][0]['primitives'][0]
    count=len(old.read(skin['attributes']['POSITION']));newcount=len(g.read(newskin['attributes']['POSITION']))
    mapping=json.loads((root/'local-brow-fit.json').read_text('utf-8')) if newcount!=count else None
    insertions=mapping['insertions'] if mapping else [];assert newcount==count+len(insertions)
    source_ids=old.read(skin['indices']).reshape(-1,3);candidate_ids=g.read(newskin['indices']).reshape(-1,3)
    supports={i:{i} for i in range(count)};barys={};vertex_parents={}
    for offset,row in enumerate(insertions):
        index=count+offset;assert row['vertex']==index
        triangle=int(row['sourceTriangle']);verts=source_ids[triangle];bary=np.asarray(row['barycentric'])
        assert row['sourceVertices']==verts.tolist() and bary.min()>-1e-6 and abs(bary.sum()-1)<1e-8
        supports[index]=set(verts.tolist());barys[index]=bary;vertex_parents[index]=triangle
    for mi,(a,b) in enumerate(zip(old.doc['meshes'],g.doc['meshes'])):
        assert {k:v for k,v in a.items() if k!='primitives'}=={k:v for k,v in b.items() if k!='primitives'}
        assert len(a['primitives'])==len(b['primitives'])
        for pi,(pa,pb) in enumerate(zip(a['primitives'],b['primitives'])):
            expanded=mi==0 and pi==0 and bool(insertions);prefix=len(old.read(pa['attributes']['POSITION']))
            assert {k:v for k,v in pa.items() if k not in ('attributes','indices','targets')}=={k:v for k,v in pb.items() if k not in ('attributes','indices','targets')}
            if not expanded:assert np.array_equal(old.read(pa['indices']),g.read(pb['indices']))
            assert pa['attributes'].keys()==pb['attributes'].keys()
            fields=[('attribute:'+key,old.read(index),g.read(pb['attributes'][key]),False) for key,index in pa['attributes'].items()]
            assert len(pa.get('targets',[]))==len(pb.get('targets',[]))
            for ti,(ta,tb) in enumerate(zip(pa.get('targets',[]),pb.get('targets',[]))):
                assert ta.keys()==tb.keys()
                for key,index in ta.items():fields.append((names[ti]+':'+key,old.read(index),g.read(tb[key]),mi==0 and names[ti] in ALLOWED and key=='POSITION'))
            for label,before,after,allowed in fields:
                if allowed:
                    if len(before)!=len(after) or not np.array_equal(before,after):changed.append([mi,pi,label])
                    continue
                comparisons+=1;assert np.array_equal(before,after[:prefix]),(mi,pi,label,'prefix')
                if expanded:
                    expected=np.asarray([barys[row['vertex']]@before[row['sourceVertices']] for row in insertions],dtype=after.dtype)
                    assert np.array_equal(expected,after[prefix:]),(mi,pi,label,'not source affine')
                else:assert len(before)==len(after)
    parents=np.arange(len(source_ids));partition_error=0.;bary_rows=None
    if insertions:
        adjacent=[set() for _ in range(count)]
        for ti,ids in enumerate(source_ids):
            for vi in ids:adjacent[vi].add(ti)
        parents=[];bary_rows=[]
        for triangle in candidate_ids:
            candidates=None
            for vi in triangle:
                viable={vertex_parents[int(vi)]} if vi>=count else adjacent[int(vi)]
                candidates=viable.copy() if candidates is None else candidates&viable
            assert candidates,('Child crosses original parent triangles',triangle.tolist())
            parent=min(candidates);verts=source_ids[parent];rows=[]
            for vi in triangle:
                rows.append(barys[int(vi)] if vi>=count else np.eye(3)[np.flatnonzero(verts==vi)[0]])
            parents.append(parent);bary_rows.append(rows)
        parents=np.asarray(parents);bary_rows=np.asarray(bary_rows)
        # Every child is inside its parent with preserved winding and complete
        # oriented area. Boundary-chain cancellation additionally rejects gaps.
        ratios=determinant(bary_rows.reshape(-1,3),np.arange(len(bary_rows)*3).reshape(-1,3))
        # determinant() projects dimensions 0,1; barycentric plane orientation
        # (lambda0,lambda1) agrees with (lambda1,lambda2).
        assert np.all(ratios>0)
        totals=np.bincount(parents,weights=ratios,minlength=len(source_ids));partition_error=float(abs(totals-1).max())
        assert partition_error<2e-6,('Incomplete/overlapping parent area',partition_error)
        chains=[{} for _ in source_ids]
        for parent,rows in zip(parents,bary_rows):
            points=[tuple(np.round(row,9)) for row in rows]
            for a,b in zip(points,points[1:]+points[:1]):
                key=tuple(sorted((a,b)));sign=1 if a<b else -1
                chains[parent][key]=chains[parent].get(key,0)+sign
        for parent,chain in enumerate(chains):
            for (a,b),winding in chain.items():
                if not winding:continue
                assert abs(winding)==1,('Duplicate parent boundary coverage',parent)
                assert any(abs(a[axis])<1e-8 and abs(b[axis])<1e-8 for axis in range(3)),('Open internal child edge/T junction',parent,a,b)
    instances=sum(len(g.read(pr['attributes']['POSITION'])) for n in g.doc['nodes'] if n.get('mesh',-1)>=0 for pr in g.doc['meshes'][n['mesh']]['primitives'])
    if preservation_only:
        return {'passed':instances<=20000 and len(g.raw)<32*1024*1024,'preservationOnly':True,'modelSha256':mb['modelSha256'],'sourceModelSha256':hashlib.sha256(old.raw).hexdigest(),'instanceVertices':instances,'fileBytes':len(g.raw),'addedSkinVertices':len(insertions),'preservedFields':comparisons,'allowedChangedFields':changed,'parentPartitionMaxAreaError':partition_error,'scope':'Static attrs, non-Up targets, node/material/manifest bindings and actual image bytes only; no pose, closure, lighting or artistic acceptance.'}
    poses=[]
    for path in pose_files:
        data=json.loads(Path(path).read_text('utf-8'));assert data['modelSha256']==mb['modelSha256'];poses+=data['poses']
    assert poses,'Actual production poses required'
    ring=g.doc['meshes'][0]['primitives'][1];oldring=old.doc['meshes'][0]['primitives'][1]
    ring_p=g.read(ring['attributes']['POSITION']).astype(float);pivot=np.asarray(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
    eye_groups=[np.flatnonzero(((ring_p[:2080]+pivot)[:,1]>.01)&(sign*ring_p[:2080,0]>.004)) for sign in (1,-1)]
    weights=np.zeros(len(names));ui=names.index('browInnerUp');blink=[names.index('eyeBlink'+s) for s in ('Left','Right')];products=[names.index('correctiveBlinkBrowInnerUp'+s) for s in ('Left','Right')]
    def complete_products(w):
        # Synthetic samples are in post-response rig space. These participating
        # direct bindings have unit gain and no curve; apply every manifest
        # product, including BlinkWide, rather than testing impossible weights.
        active={'browInnerUp','eyeBlinkLeft','eyeBlinkRight','eyeWideLeft','eyeWideRight','browOuterUpLeft','browOuterUpRight'}
        for binding in mb['bindings']:
            if binding['source'] in active:assert binding.get('gain',1)==1 and 'curve' not in binding
        for binding in mb['derivedBindings']:
            assert binding['operation']=='product' and binding['mesh']=='Face'
            w[names.index(binding['target'])]=np.prod([w[names.index(name)] for name in binding['sources']])*binding.get('gain',1)
        return w
    synthetic=[]
    for amount in (.25,.5,.75):
        for wide in (0,1):
            w=weights.copy();w[ui]=1;w[blink]=amount;w[products]=amount
            w[[names.index('eyeWideLeft'),names.index('eyeWideRight')]]=wide
            synthetic.append({'name':'half-blink-%.2f-wide-%s'%(amount,wide),'weights':complete_products(w)})
    endpoints=[]
    for outer in (0,1):
        w=weights.copy();w[ui]=1
        w[[names.index('browOuterUpLeft'),names.index('browOuterUpRight')]]=outer
        endpoints.append({'name':'inner-up-endpoint-outer-%s'%outer,'weights':complete_products(w)})
    cases=[{'name':p['name'],'weights':p['meshes'][0]['weights']} for p in poses]+synthetic+endpoints
    full_restore=[]
    for side,group,pi in zip(('Left','Right'),eye_groups,products):
        expected=old.read(oldring['targets'][ui]['POSITION'])+old.read(oldring['targets'][pi]['POSITION'])
        actual=g.read(ring['targets'][ui]['POSITION'])+g.read(ring['targets'][pi]['POSITION'])
        region=np.r_[group,np.flatnonzero((np.arange(len(ring_p))>=2080)&((1 if side=='Left' else -1)*ring_p[:,0]>0))]
        full_restore.append({'side':side,'maxErrorMeters':float(abs(expected[region]-actual[region]).max())})
        for index in (ui,*products):
            assert np.array_equal(g.read(ring['targets'][index]['POSITION'])[group[-64:]],old.read(oldring['targets'][index]['POSITION'])[group[-64:]]),'Brow fields changed the inner eyelid rim'
    surface=[];flips=[];attachments=[]
    for case in cases:
        w=case['weights'];sp=deformed(old,skin,w,names,ALLOWED);cp=deformed(g,newskin,w,names,ALLOWED)
        error=float(abs(cp[count:]-np.asarray([barys[row['vertex']]@sp[row['sourceVertices']] for row in insertions])).max()) if insertions else float(abs(cp-sp).max())
        surface.append({'pose':case['name'],'maxNonUpSurfaceErrorMeters':error});assert error<3e-8
        for pi in (0,1):
            before=old.doc['meshes'][0]['primitives'][pi];after=g.doc['meshes'][0]['primitives'][pi]
            old_ids=old.read(before['indices']).reshape(-1,3);new_ids=g.read(after['indices']).reshape(-1,3)
            parent=parents if pi==0 else np.arange(len(old_ids))
            old_p=deformed(old,before,w,names);new_p=deformed(g,after,w,names)
            original=old.read(before['attributes']['POSITION']).astype(float)+pivot
            v=original[old_ids];near=(v[:,:,1].max(1)>.005)&(v[:,:,1].min(1)<.112)&(v[:,:,2].min(1)>.045)
            base_det=determinant(original,old_ids);reference=determinant(old_p,old_ids)
            actual=determinant(new_p,new_ids);valid=near[parent]&(abs(base_det[parent])>1e-10)
            # Use the same dimensionless sign tolerance on both assets. An
            # absolute area-product cutoff can label an already-negative
            # source triangle "new" solely because its area grew in candidate.
            denominator=np.where(abs(base_det[parent])>1e-30,base_det[parent],1.)
            inherited=reference[parent]/denominator<-1e-8;current=actual/denominator<-1e-8
            flips.append({'pose':case['name'],'primitive':pi,'inheritedSourceFlips':int((valid&inherited).sum()),'candidateFlips':int((valid&current).sum()),'newCandidateFlips':int((valid&current&~inherited).sum()),'newCandidateFlipTriangleIds':np.flatnonzero(valid&current&~inherited)[:12].tolist()})
            old_v=old_p[old_ids];new_v=new_p[new_ids]
            base_cross=np.cross(v[:,1]-v[:,0],v[:,2]-v[:,0]);old_cross=np.cross(old_v[:,1]-old_v[:,0],old_v[:,2]-old_v[:,0]);new_cross=np.cross(new_v[:,1]-new_v[:,0],new_v[:,2]-new_v[:,0])
            norm2=(base_cross*base_cross).sum(1);valid3=near[parent]&(norm2[parent]>1e-20)
            old_dot=(old_cross[parent]*base_cross[parent]).sum(1)/np.maximum(norm2[parent],1e-30)
            new_dot=(new_cross*base_cross[parent]).sum(1)/np.maximum(norm2[parent],1e-30)
            inherited3=old_dot<-1e-8;current3=new_dot<-1e-8
            flips[-1].update(inheritedSourceCrossNormalReversals=int((valid3&inherited3).sum()),candidateCrossNormalReversals=int((valid3&current3).sum()),newCandidateCrossNormalReversals=int((valid3&current3&~inherited3).sum()),newCrossNormalReversalTriangleIds=np.flatnonzero(valid3&current3&~inherited3)[:12].tolist())
        # All 923 appended endpoints, checked against actual original Skin and
        # first-2080 Ring surfaces, not against their own bridge triangles.
        vertices=[];triangles=[];offset=0
        for pi,pr in enumerate(g.doc['meshes'][0]['primitives'][:2]):
            p=deformed(g,pr,w,names);ids=g.read(pr['indices']).reshape(-1,3)
            if pi==1:p=p[:2080];ids=ids[(ids<2080).all(1)]
            vv=p[ids]+pivot;near=(vv[:,:,1].max(1)>.005)&(vv[:,:,2].max(1)>.03)
            vertices.extend(p);triangles.extend(ids[near]+offset);offset+=len(p)
        actual=deformed(g,ring,w,names)[2080:]
        distance=point_surface_distances(actual,np.asarray(vertices),np.asarray(triangles))
        attachments.append({'pose':case['name'],'maxEndpointErrorMeters':float(distance.max())})
    maximum=max(row['maxEndpointErrorMeters'] for row in attachments);new_flips=sum(row['newCandidateFlips'] for row in flips)
    new3=sum(row['newCandidateCrossNormalReversals'] for row in flips);closure=[];half=[]
    if not insertions:
        for pose in poses:
            w=pose['meshes'][0]['weights']
            if min(w[i] for i in blink)<.999:continue
            for view,direction in [('front',[0,0,1]),('left',[-.45,0,1]),('right',[.45,0,1])]:closure.append({'pose':pose['name'],'view':view,'eyes':visible_eye(g,pose,direction)})
        template=poses[0]
        for case in synthetic:
            pose=json.loads(json.dumps(template));pose['meshes'][0]['weights']=list(case['weights']);half.append({'pose':case['name'],'eyes':visible_eye(g,pose,[0,0,1])})
    closed_safe=all(eye['eyeProbes']==0 for row in closure for eye in row['eyes'])
    half_monotone=True
    for wide in (0,1):
        selected=[row for row in half if row['pose'].endswith('wide-'+str(wide))]
        for side in (0,1):
            counts=[row['eyes'][side]['eyeProbes'] for row in selected]
            half_monotone=half_monotone and all(a>=b for a,b in zip(counts,counts[1:]))
    restored=max(row['maxErrorMeters'] for row in full_restore)<2e-8
    passed=instances<=20000 and len(g.raw)<32*1024*1024 and maximum<2e-7 and new_flips==0 and new3==0 and closed_safe and half_monotone and (restored or not require_full_source_restore)
    return {'passed':passed,'modelSha256':mb['modelSha256'],'sourceModelSha256':hashlib.sha256(old.raw).hexdigest(),'addedSkinVertices':len(insertions),'instanceVertices':instances,'fileBytes':len(g.raw),'preservedFields':comparisons,'allowedChangedFields':changed,'parentPartitionMaxAreaError':partition_error,'parentPartitionScope':'Within each original parent only; shared old-edge split parameter sets across parents and duplicate UV seams are not audited. Non-Up source-affine surfaces proven; no general Up T-crack claim.','nonUpSurfaceChecks':surface,'fullBlinkSourceRestoration':full_restore,'fullSourceRestorationRequired':require_full_source_restore,'innerEyelidRimPreserved':True,'actualAndHalfBlinkProjectedFlips':flips,'orientationRegion':{'primitives':[0,1],'neutralWorldMinZGreaterThanMeters':.045,'neutralWorldMaxYGreaterThanMeters':.005,'neutralWorldMinYLessThanMeters':.112,'projectedNeutralDoubleAreaMinSquareMeters':1e-10,'crossNeutralNormSquaredMinMetersFourth':1e-20,'signedRelativeReversalThreshold':-1e-8},'orbitalAttachmentChecks':attachments,'maxOrbitalAttachmentErrorMeters':maximum,'newProjectedFlipOccurrences':new_flips,'newCrossNormalReversalOccurrences':new3,'fullClosedRayChecks':closure,'halfBlinkRayMeasurements':half,'fullClosedRaysSafe':closed_safe,'halfBlinkVisibleEyeCountNonIncreasing':half_monotone,'scope':'Specified actual exported poses plus six half-blink/wide combinations and two Up endpoints; synthetic weights include all seven product bindings. Orientation checks cover the defined neutral front brow/ocular region of Skin and Ring, not every model triangle. 3D cross-normal reversals are relative to neutral, not a full collision proof. Full-brow closure may change under the current Ring design unless source restoration explicitly required. Added static normals are affine; smooth-deformed-v1 recomputation may alter lighting after tessellation. No pixel-identity or artistic acceptance claim.'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('candidate');p.add_argument('--source',required=True);p.add_argument('--poses',action='append',default=[]);p.add_argument('--report',required=True);p.add_argument('--require-full-source-restore',action='store_true');p.add_argument('--preservation-only',action='store_true');a=p.parse_args()
    out=Path(a.report);assert not out.exists();result=check(a.candidate,a.source,a.poses,a.require_full_source_restore,a.preservation_only);out.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps({k:v for k,v in result.items() if not isinstance(v,list)}));raise SystemExit(0 if result['passed'] else 1)
