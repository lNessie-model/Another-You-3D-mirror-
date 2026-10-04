"""Author only inner-brow lift on a pre-socket carrier; retain the accepted rig."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from head_glb_edit import GlbEdit
from tripo_head_adapter import Glb
from head_surface_topology import FrontSurface
from refine_head_expression import attached_delta

def ease(a,b,v):
    t=np.clip((v-a)/(b-a),0,1);return t*t*(3-2*t)

def author(source,carrier_path,out,peak=.0060,sigma=.0040):
    source=Path(source);out=Path(out);assert not out.exists()
    edit=GlbEdit(source/'character.glb');g=edit.source;mesh=g.doc['meshes'][0];names=mesh['extras']['targetNames'];up_index=names.index('browInnerUp')
    carrier=Glb(Path(carrier_path)/'character.glb');cp=carrier.doc['meshes'][0]['primitives'][0]
    pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
    cp_p=carrier.read(cp['attributes']['POSITION']).astype(float)+pivot;cp_ids=carrier.read(cp['indices']).reshape(-1,3)
    x=cp_p[:,0];y=cp_p[:,1];z=cp_p[:,2]
    # Historical ray probes were mislabeled as gray eyebrow material; later
    # native-texture inspection identified these coordinates as brown Skin.
    # Retain the recorded experiment's math; this field is not true-gray fitting.
    xfield=np.exp(-((x+.0025)/sigma)**2)+np.exp(-((x-.0110)/sigma)**2)
    # A broad central lift connects the two heads without forming a new notch.
    central=.0030*np.exp(-((x-.00425)/.0050)**2)
    displacement=(peak*xfield+central)*(1-ease(.027,.045,abs(x-.00425)))
    field=np.zeros_like(cp_p)
    field[:,1]=displacement*ease(.008,.030,y)*(1-ease(.036,.078,y))*ease(.027,.055,z)
    affected=cp_p[cp_ids[np.any(field[cp_ids,1]>1e-12,axis=1)]].reshape(-1,3)
    low=affected.min(0)-2e-7;high=affected.max(0)+2e-7
    positions=[g.read(p['attributes']['POSITION']).astype(float)+pivot for p in mesh['primitives']]
    skin=mesh['primitives'][0];ring=mesh['primitives'][1];skin_up=np.zeros_like(positions[0]);ring_up=g.read(ring['targets'][up_index]['POSITION']).astype(float)
    selected=np.flatnonzero(((positions[0]>=low)&(positions[0]<=high)).all(1))
    skin_up[selected],carrier_error=attached_delta(positions[0][selected],cp_p,cp_ids,field)
    prefix=2080;bridge_end=3001;assert len(positions[1])==3003
    surface=FrontSurface(np.c_[cp_p,field],cp_ids)
    inner_checks=[]
    for side,sign in [('Left',1),('Right',-1)]:
        group=np.flatnonzero((positions[1][:prefix,1]>.01)&(sign*positions[1][:prefix,0]>.004));assert len(group)==512
        for row in range(8):
            for vertex in group[row*64:(row+1)*64]:
                ring_up[vertex]=surface.sample(*positions[1][vertex,:2])[3:]*(1-ease(0,1,row/7))
        assert not ring_up[group[-64:]].any();inner_checks.append({'side':side,'innerVertices':64,'maxInnerUpDeltaMeters':0})
    vertices=np.r_[positions[0],positions[1][:prefix]];deltas=np.r_[skin_up,ring_up[:prefix]]
    ids0=g.read(skin['indices']).reshape(-1,3);ids1=g.read(ring['indices']).reshape(-1,3);ids1=ids1[(ids1<prefix).all(1)]+len(positions[0])
    ids=np.r_[ids0,ids1];near=(vertices[ids,1].max(1)>.008)&(vertices[ids,2].max(1)>.03)
    ring_up[prefix:bridge_end],attachment_error=attached_delta(positions[1][prefix:bridge_end],vertices,ids[near],deltas)
    # The two accepted closure-patch points continue to be exact skin copies.
    ring_up[3001]=skin_up[11829];ring_up[3002]=skin_up[11904]
    for pi,values in [(0,skin_up),(1,ring_up)]:edit.doc['meshes'][0]['primitives'][pi]['targets'][up_index]['POSITION']=edit.add(values,'VEC3')
    out.mkdir();target=out/'character.glb';edit.write(target);sha=hashlib.sha256(target.read_bytes()).hexdigest()
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-natural-inner-brow';manifest['displayName']+=' · 自然眉内端候选';manifest['modelSha256']=sha
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':sha,'carrierModelSha256':hashlib.sha256(carrier.raw).hexdigest(),
            'changedTarget':'browInnerUp','unchangedAllOtherTargets':True,'unchangedPositionsNormalsUvsColorsIndicesMapsMaterialsNodesBindings':True,
            'peakMeters':peak,'sigmaXMeters':sigma,'browCentersWorldX':[-.0025,.0110],'centralLiftMeters':.003,'centralWorldX':.00425,'centralSigmaXMeters':.005,'lowerFadeY':[.008,.030],'upperFadeY':[.036,.078],
            'carrierAttachmentErrorMeters':carrier_error,'bridgeAttachmentErrorMeters':attachment_error,'eyeInnerRims':inner_checks,
            'scope':'Historical brown-Skin probes originally mislabeled as gray eyebrows; this field does not establish true gray-brow endpoint movement. Math retained; all legacy blink-brow correctives retained byte exact. Requires actual mixed/brow/blink visual and closure review.',
            'artistAccepted':False,'deviceInstalled':False,'tripoCreditsSpent':0}
    (out/'brow-natural-authoring.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--carrier',required=True);p.add_argument('--out',required=True);p.add_argument('--peak',type=float,default=.0060);p.add_argument('--sigma',type=float,default=.0040)
    a=p.parse_args();author(a.source,a.carrier,a.out,a.peak,a.sigma)
