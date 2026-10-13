"""Build a fixed-body/head-pose candidate from a single bust GLB.

The source is split on a measured Y plane. The body remains on the root node;
the upper piece is a child of Head. Six small correction targets keep the cut
ring fixed while the neck gradually follows the head rotation. This is a
rigid-node plus morph profile understood by the mirror runtime, not glTF skin.
The source file is never modified.
"""
from pathlib import Path
import argparse, copy, hashlib, io, json, math, struct, sys
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from tripo_head_adapter import Glb

TARGETS=["eyeBlinkLeft","eyeBlinkRight","jawOpen",
         "neckPitchPositive","neckPitchNegative","neckYawPositive",
         "neckYawNegative","neckRollPositive","neckRollNegative"]
CORRECTIONS=[("pitch",1,"neckPitchPositive"),("pitch",-1,"neckPitchNegative"),
             ("yaw",1,"neckYawPositive"),("yaw",-1,"neckYawNegative"),
             ("roll",1,"neckRollPositive"),("roll",-1,"neckRollNegative")]

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def clip_mesh(pos,nor,uv,faces,cut,body):
    """Clip triangles to one side, preserving shared cut-edge vertices."""
    cache={}; outp=[]; outn=[]; outuv=[]; outf=[]; keys=[]
    def point(kind, value):
        if kind[0]=='v':
            i=int(kind[1]); return pos[i],nor[i],uv[i],kind
        a,b=kind[1:]; pa,pb=pos[a],pos[b]; na,nb=nor[a],nor[b]; ua,ub=uv[a],uv[b]
        t=float((cut-pa[1])/(pb[1]-pa[1]))
        n=na*(1-t)+nb*t; n/=max(np.linalg.norm(n),1e-12)
        return pa*(1-t)+pb*t,n,ua*(1-t)+ub*t,kind
    def vertex(kind):
        if kind not in cache:
            p,n,u,_=point(kind,None); cache[kind]=len(outp);outp.append(p);outn.append(n);outuv.append(u);keys.append(kind)
        return cache[kind]
    def inside(p): return p[1] <= cut+1e-8 if body else p[1] >= cut-1e-8
    for tri in faces:
        poly=[('v',int(i)) for i in tri]
        result=[]
        prev=poly[-1]
        for cur in poly:
            pcur=pos[cur[1]] if cur[0]=='v' else point(cur,None)[0]
            pprev=pos[prev[1]] if prev[0]=='v' else point(prev,None)[0]
            incur,inprev=inside(pcur),inside(pprev)
            if incur != inprev:
                if prev[0]!='v' or cur[0]!='v': raise ValueError('Unexpected nested clip intersection')
                a,b=prev[1],cur[1]
                result.append(('e',min(a,b),max(a,b)))
            if incur: result.append(cur)
            prev=cur
        if len(result)<3: continue
        for i in range(1,len(result)-1):
            ids=[vertex(result[0]),vertex(result[i]),vertex(result[i+1])]
            p=np.asarray(outp)[ids]
            if np.linalg.norm(np.cross(p[1]-p[0],p[2]-p[0]))>1e-12: outf.append(ids)
    if not outf: raise ValueError('Clip produced no triangles')
    return (np.asarray(outp,dtype='<f4'),np.asarray(outn,dtype='<f4'),np.asarray(outuv,dtype='<f4'),
            np.asarray(outf,dtype=np.uint32),keys)

def rot(axis,degrees):
    a=math.radians(degrees);c,s=math.cos(a),math.sin(a);r=np.eye(3)
    if axis=='pitch': r=np.array([[1,0,0],[0,c,-s],[0,s,c]],dtype=float)
    elif axis=='yaw': r=np.array([[c,0,s],[0,1,0],[-s,0,c]],dtype=float)
    elif axis=='roll': r=np.array([[c,-s,0],[s,c,0],[0,0,1]],dtype=float)
    return r

def composite_rotation(angles):
    return rot('roll',angles[2])@rot('yaw',angles[1])@rot('pitch',angles[0])

def correction_targets(head_abs,pivot,cut,transition,axis_limits):
    rel=head_abs-pivot[None,:]; static=np.clip((cut+transition-head_abs[:,1])/transition,0,1)
    targets=[]
    for axis,sign,_ in CORRECTIONS:
        inv=rot(axis,sign*axis_limits[axis]).T
        targets.append((static[:,None]*(rel@inv.T-rel)).astype('<f4'))
    return targets

class Writer:
    def __init__(self): self.bin=bytearray(); self.views=[]; self.accessors=[]
    def add(self,a,kind,component=5126,bounds=False):
        a=np.asarray(a,dtype='<f4' if component==5126 else '<u2' if component==5123 else '<u4')
        while len(self.bin)%4:self.bin.append(0)
        off=len(self.bin); self.bin.extend(a.tobytes()); view=len(self.views)
        self.views.append({'buffer':0,'byteOffset':off,'byteLength':a.nbytes})
        width={'SCALAR':1,'VEC2':2,'VEC3':3}[kind]; row=a.reshape(-1,width)
        ac={'bufferView':view,'componentType':component,'count':len(row),'type':kind}
        if bounds: ac.update(min=row.min(0).tolist(),max=row.max(0).tolist())
        self.accessors.append(ac); return len(self.accessors)-1
    def image(self,data,name):
        while len(self.bin)%4:self.bin.append(0)
        off=len(self.bin); self.bin.extend(data); view=len(self.views)
        self.views.append({'buffer':0,'byteOffset':off,'byteLength':len(data)})
        return {'bufferView':view,'mimeType':'image/png','name':name}
    def write(self,path,body,head,pivot,head_angles=(0,0,0),targets=True,images=()):
        meshes=[]
        for label,m,mesh_targets in [('StaticBody',body,[]),('HeadNeck',head,head.get('targets',[]) if targets else [])]:
            p=self.add(m['positions'],'VEC3',bounds=True); n=self.add(m['normals'],'VEC3'); u=self.add(m['uv'],'VEC2')
            ids=np.asarray(m['faces']).reshape(-1); comp=5123 if len(m['positions'])<65535 else 5125
            idx=self.add(ids,'SCALAR',comp)
            prim={'attributes':{'POSITION':p,'NORMAL':n,'TEXCOORD_0':u},'indices':idx,'material':0,'mode':4}
            if mesh_targets:
                prim['targets']=[{'POSITION':self.add(t,'VEC3')} for t in mesh_targets]
                meshes.append({'name':label,'primitives':[prim],'weights':[0.0]*len(mesh_targets),'extras':{'targetNames':TARGETS}})
            else: meshes.append({'name':label,'primitives':[prim]})
        q=quat(composite_rotation(head_angles))
        nodes=[{'name':'Root','children':[1,2]},
               {'name':'Head','translation':pivot.tolist(),'rotation':q.tolist(),'children':[3,4,5,6]},
               {'name':'StaticBody','mesh':0}, {'name':'LeftEye'}, {'name':'RightEye'},
               {'name':'JawAttachments'}, {'name':'HeadNeck','mesh':1}]
        png_meta=[self.image(data,name) for data,name in images]
        logical=len(self.bin)
        while len(self.bin)%4:self.bin.append(0)
        doc={'asset':{'version':'2.0','generator':'Another You static-body/head pose pipeline'},
             'scene':0,'scenes':[{'nodes':[0]}],'nodes':nodes,'meshes':meshes,
             'materials':[{'name':'MirrorPbr','pbrMetallicRoughness':{
                 'baseColorTexture':{'index':0,'texCoord':0},'metallicFactor':0,'roughnessFactor':.78,
                 'metallicRoughnessTexture':{'index':2,'texCoord':0}},
                 'normalTexture':{'index':1,'texCoord':0,'scale':1},'alphaMode':'OPAQUE'}],
             'extras':{'mirrorAlbedoAtlas':1,'mirrorPbrAtlas':1},
             'images':png_meta,'textures':[{'source':i} for i in range(3)],
             'buffers':[{'byteLength':logical}],'bufferViews':self.views,'accessors':self.accessors}
        encoded=json.dumps(doc,separators=(',',':'),ensure_ascii=True).encode();encoded+=b' '*((-len(encoded))%4)
        payload=struct.pack('<III',0x46546c67,2,28+len(encoded)+len(self.bin))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(self.bin),0x004e4942)+self.bin
        Path(path).write_bytes(payload); return doc

def quat(r):
    # Column-vector rotation matrix to glTF [x,y,z,w].
    tr=np.trace(r)
    if tr>0:
        s=math.sqrt(tr+1)*2; w=.25*s; x=(r[2,1]-r[1,2])/s; y=(r[0,2]-r[2,0])/s; z=(r[1,0]-r[0,1])/s
    elif r[0,0]>r[1,1] and r[0,0]>r[2,2]:
        s=math.sqrt(1+r[0,0]-r[1,1]-r[2,2])*2; w=(r[2,1]-r[1,2])/s; x=.25*s; y=(r[0,1]+r[1,0])/s; z=(r[0,2]+r[2,0])/s
    elif r[1,1]>r[2,2]:
        s=math.sqrt(1+r[1,1]-r[0,0]-r[2,2])*2; w=(r[0,2]-r[2,0])/s; x=(r[0,1]+r[1,0])/s; y=.25*s; z=(r[1,2]+r[2,1])/s
    else:
        s=math.sqrt(1+r[2,2]-r[0,0]-r[1,1])*2; w=(r[1,0]-r[0,1])/s; x=(r[0,2]+r[2,0])/s; y=(r[1,2]+r[2,1])/s; z=.25*s
    return np.array([x,y,z,w],dtype='<f4')

def png_rgb(glb,index,size,normal=False):
    arr=np.clip(np.rint(glb.image(index)*255),0,255).astype(np.uint8)
    image=Image.fromarray(arr,'RGB')
    image.thumbnail((size,size),Image.Resampling.LANCZOS)
    arr=np.asarray(image).copy()
    if normal:
        n=arr.astype(np.float32)/127.5-1; length=np.linalg.norm(n,axis=2,keepdims=True); n/=np.maximum(length,1e-6); arr=np.clip(np.rint((n+1)*127.5),0,255).astype(np.uint8)
    out=io.BytesIO(); Image.fromarray(arr,'RGB').save(out,format='PNG',optimize=True,compress_level=9)
    return out.getvalue(),image.size

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--source',type=Path,required=True); ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--cut-y',type=float,default=.22); ap.add_argument('--transition',type=float,default=.14); ap.add_argument('--pivot-y',type=float,default=.25)
    ap.add_argument('--max-degrees',type=float,default=30); ap.add_argument('--texture-size',type=int,default=1536); ap.add_argument('--expected-source-sha',required=True)
    a=ap.parse_args(); source=a.source.resolve(); out=a.out.resolve()
    if sha(source)!=a.expected_source_sha: raise ValueError('Source SHA mismatch; originals are immutable')
    if out.exists(): raise ValueError('Output already exists')
    if not 0<a.cut_y<1 or not 0<a.transition<=.4 or not 0<a.pivot_y<1 or not 1<=a.max_degrees<=60 or not 512<=a.texture_size<=2048: raise ValueError('Pose geometry parameters outside profile')
    glb=Glb(source); primitive=glb.doc['meshes'][0]['primitives'][0]
    pos=glb.read(primitive['attributes']['POSITION']).astype(np.float64); nor=glb.read(primitive['attributes']['NORMAL']).astype(np.float64); nor/=np.maximum(np.linalg.norm(nor,axis=1,keepdims=True),1e-12)
    uv=glb.read(primitive['attributes']['TEXCOORD_0']).astype(np.float64); faces=glb.read(primitive['indices']).reshape(-1,3).astype(np.int64)
    if len(pos)>=20000 or len(faces)>=30000: raise ValueError('Source already exceeds the runtime geometry cap')
    body_p,body_n,body_u,body_f,body_keys=clip_mesh(pos,nor,uv,faces,a.cut_y,True)
    head_p,head_n,head_u,head_f,head_keys=clip_mesh(pos,nor,uv,faces,a.cut_y,False)
    if len(body_p)+len(head_p)>20000 or len(body_f)+len(head_f)>30000: raise ValueError(f'Split exceeds runtime budget: {len(body_p)+len(head_p)} vertices, {(len(body_f)+len(head_f))} triangles')
    pivot=np.array([0,a.pivot_y,0],dtype=np.float64); head_abs=head_p.copy(); head_local=head_abs-pivot[None,:]
    axis_limits={'pitch':min(20.0,a.max_degrees),'yaw':a.max_degrees,'roll':min(15.0,a.max_degrees)}
    morphs=[np.zeros_like(head_local,dtype='<f4') for _ in range(3)]+correction_targets(head_abs,pivot,a.cut_y,a.transition,axis_limits)
    body={'positions':body_p.astype('<f4'),'normals':body_n.astype('<f4'),'uv':body_u.astype('<f4'),'faces':body_f}
    head={'positions':head_local.astype('<f4'),'normals':head_n.astype('<f4'),'uv':head_u.astype('<f4'),'faces':head_f,'targets':morphs}
    # Source material maps are converted to the runtime's strict RGB PNG atlas profile.
    mat=glb.doc['materials'][primitive.get('material',0)]; pbr=mat['pbrMetallicRoughness']
    base_i=glb.doc['textures'][pbr['baseColorTexture']['index']]['source']; rm_i=glb.doc['textures'][pbr['metallicRoughnessTexture']['index']]['source']; normal_i=glb.doc['textures'][mat['normalTexture']['index']]['source']
    base,base_size=png_rgb(glb,base_i,a.texture_size); normal,normal_size=png_rgb(glb,normal_i,a.texture_size,True); orm,orm_size=png_rgb(glb,rm_i,a.texture_size)
    out.mkdir(parents=True); writer=Writer(); model=out/'character.glb'; writer.write(model,body,head,pivot,images=((base,'baseColor'),(normal,'normal'),(orm,'metallicRoughness')))
    digest=sha(model)
    manifest={'schemaVersion':1,'id':'cloud-static-body-head','displayName':'Cloud static body / articulated head preview','model':'character.glb','modelSha256':digest,
      'inputSchema':'mediapipe-face-blendshapes-v1','normalPolicy':'recompute-deformed','materialProfile':'mirror-pbr-v1',
      'coordinates':{'up':'+Y','forward':'+Z','subjectLeft':'+X','units':'meters','rootScale':1,'headPivot':pivot.tolist()},
      'rig':{'headNode':'Head','jawMode':'morph','jawAttachmentNode':'JawAttachments','jawAxis':[1,0,0],'jawOpenDegrees':22,'jawLateralMeters':.00845,'jawForwardMeters':.0078,
             'gazeMode':'joint','leftEyeNode':'LeftEye','rightEyeNode':'RightEye','gazeYawDegrees':18,'gazePitchDegrees':15},
      'bindings':[{'source':n,'mesh':'HeadNeck','target':n,'gain':1} for n in TARGETS[:3]],
      'headPoseCorrections':[{'axis':axis,'sign':sign,'mesh':'HeadNeck','target':name,'maxDegrees':axis_limits[axis]} for axis,sign,name in CORRECTIONS],
      'previewOnly':True,'bodyPolicy':'StaticBody stays at Root; HeadNeck uses fixed-ring correction morphs. Facial targets are placeholders until expression authoring.'}
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n','utf-8')
    poses={'neutral':(0,0,0),'yaw-right-30':(0,30,0),'yaw-left-30':(0,-30,0),'pitch-down-20':(20,0,0)}
    preview=out/'poses'; preview.mkdir()
    for name,angles in poses.items():
        R=composite_rotation(angles); static=np.clip((a.cut_y+a.transition-head_abs[:,1])/a.transition,0,1); posed=head_local+static[:,None]*((head_local@R)-head_local)
        ph=dict(head);ph['positions']=posed.astype('<f4'); ph['targets']=[]; pw=Writer(); pw.write(preview/(name+'.glb'),body,ph,pivot,head_angles=angles,targets=False,images=((base,'baseColor'),(normal,'normal'),(orm,'metallicRoughness')))
    report={'source':str(source),'sourceSha256':a.expected_source_sha,'model':str(model),'modelSha256':digest,'cutY':a.cut_y,'pivot':pivot.tolist(),'transition':a.transition,'axisCorrectionDegrees':axis_limits,
      'bodyVertices':len(body_p),'headVertices':len(head_p),'totalVertices':len(body_p)+len(head_p),'bodyTriangles':len(body_f),'headTriangles':len(head_f),'totalTriangles':len(body_f)+len(head_f),
      'boundaryVerticesBody':sum(k[0]=='e' for k in body_keys),'boundaryVerticesHead':sum(k[0]=='e' for k in head_keys),'textureSizes':{'baseColor':base_size,'normal':normal_size,'orm':orm_size},
      'scope':'Structural preview: fixed body, Head node, six angle-driven neck correction targets; facial expression targets are placeholders and require separate authoring.'}
    (out/'binding-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8'); print(json.dumps(report,ensure_ascii=False),flush=True)

if __name__=='__main__': main()
