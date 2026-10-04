"""Offline adapter: preserve originals, rotate an observed +X-facing head and bake base colour.

No inference or claims of a complete facial rig. The production factor-only loader
can validate this geometric stage; facial targets are a later separate stage.
"""
from pathlib import Path
import io,json,struct
import numpy as np

def rotate_x_front(points):
    return np.asarray(points)@np.array([[0,0,1],[0,1,0],[-1,0,0]])

def srgb_to_linear(values):
    values=np.asarray(values,dtype=np.float64)
    return np.where(values<=.04045,values/12.92,((values+.055)/1.055)**2.4)

class Glb:
    def __init__(self,path):
        self.raw=Path(path).read_bytes();raw=self.raw
        assert 28<=len(raw)<=64*1024*1024
        assert struct.unpack_from('<III',raw)==(0x46546c67,2,len(raw))
        size,kind=struct.unpack_from('<II',raw,12);assert kind==0x4e4f534a
        self.doc=json.loads(raw[20:20+size]);self.start=28+size
        n,kind=struct.unpack_from('<II',raw,20+size);assert kind==0x004e4942 and self.start+n==len(raw)
    def read(self,index):
        a=self.doc['accessors'][index];assert 'sparse' not in a and a['count']>0
        v=self.doc['bufferViews'][a['bufferView']]
        dtype=np.dtype({5121:'u1',5123:'<u2',5125:'<u4',5126:'<f4'}[a['componentType']])
        width={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}[a['type']]
        stride=v.get('byteStride',width*dtype.itemsize);off=a.get('byteOffset',0)
        assert off+(a['count']-1)*stride+width*dtype.itemsize<=v['byteLength']
        result=np.ndarray((a['count'],width),dtype=dtype,buffer=self.raw,offset=self.start+v.get('byteOffset',0)+off,strides=(stride,dtype.itemsize)).copy()
        assert np.isfinite(result).all()
        if a.get('normalized'):result=result.astype(np.float64)/np.iinfo(dtype).max
        return result
    def image(self,index):
        from PIL import Image
        image=self.doc['images'][index];assert 'uri' not in image
        view=self.doc['bufferViews'][image['bufferView']];off=self.start+view.get('byteOffset',0)
        result=Image.open(io.BytesIO(self.raw[off:off+view['byteLength']]))
        assert result.width<=8192 and result.height<=8192
        return np.asarray(result.convert('RGB'),dtype=np.float64)/255
    def bake_base_colour(self,primitive):
        return self.sample_base_colour(self.read(primitive['attributes']['TEXCOORD_0']),primitive['material'])
    def sample_base_colour(self,uv,material_index):
        material=self.doc['materials'][material_index]
        pbr=material['pbrMetallicRoughness'];texture=self.doc['textures'][pbr['baseColorTexture']['index']]
        if not hasattr(self,'_colour_images'):self._colour_images={}
        if texture['source'] not in self._colour_images:self._colour_images[texture['source']]=srgb_to_linear(self.image(texture['source']))
        image=self._colour_images[texture['source']];h,w=image.shape[:2]
        # glTF UV origin is the upper-left image corner. Sample repeating texels at their centres.
        x=(uv[:,0]%1)*w-.5;y=(uv[:,1]%1)*h-.5;ix=np.floor(x).astype(int);iy=np.floor(y).astype(int)
        fx=x-ix;fy=y-iy
        rgb=image[iy%h,ix%w]*(1-fx[:,None])*(1-fy[:,None])+image[iy%h,(ix+1)%w]*fx[:,None]*(1-fy[:,None])
        rgb+=image[(iy+1)%h,ix%w]*(1-fx[:,None])*fy[:,None]+image[(iy+1)%h,(ix+1)%w]*fx[:,None]*fy[:,None]
        factor=np.array(pbr.get('baseColorFactor',[1,1,1,1]))
        return np.c_[rgb*factor[:3],np.full(len(rgb),factor[3])].astype('<f4')

def refine_texture_samples(positions,normals,uv,indices,sampler,*,max_vertices=20000,levels=4,region=None):
    """Insert interior samples only: surface boundaries and winding remain unchanged."""
    positions=np.array(positions);normals=np.array(normals);uv=np.array(uv);indices=np.array(indices,dtype=np.int32)
    assert len(positions)<=max_vertices<=20000 and 0<=levels<=6
    colours=sampler(uv)
    bary=np.array([[1/3,1/3,1/3],[.6,.2,.2],[.2,.6,.2],[.2,.2,.6]])
    for _ in range(levels):
        remaining=min(max_vertices-len(positions),(30000-len(indices))//2)
        if remaining<=0:break
        candidates=indices if region is None else indices[region(positions[indices].mean(axis=1))]
        if len(candidates)==0:break
        samples=np.einsum('sc,fcv->fsv',bary,uv[candidates]);actual=sampler(samples.reshape(-1,2)).reshape(len(candidates),len(bary),4)
        expected=np.einsum('sc,fcv->fsv',bary,colours[candidates]);errors=np.max(abs(actual[:,:,:3]-expected[:,:,:3]),axis=2)
        best=errors.argmax(axis=1);score=errors[np.arange(len(candidates)),best]
        chosen=np.where(score>.04)[0];chosen=chosen[np.argsort(score[chosen])[::-1][:remaining]]
        if len(chosen)==0:break
        selected=candidates[chosen];weights=bary[best[chosen]];off=len(positions)
        new_positions=np.einsum('fc,fcv->fv',weights,positions[selected]);new_normals=np.einsum('fc,fcv->fv',weights,normals[selected])
        new_normals/=np.linalg.norm(new_normals,axis=1)[:,None];new_uv=np.einsum('fc,fcv->fv',weights,uv[selected])
        new_indices=np.arange(off,off+len(selected));replacement=np.concatenate([np.c_[selected[:,0],selected[:,1],new_indices],np.c_[selected[:,1],selected[:,2],new_indices],np.c_[selected[:,2],selected[:,0],new_indices]])
        removed={tuple(t) for t in selected};keep=np.array([tuple(t) not in removed for t in indices])
        positions=np.concatenate([positions,new_positions]);normals=np.concatenate([normals,new_normals]);uv=np.concatenate([uv,new_uv]);colours=np.concatenate([colours,actual[chosen,best[chosen]]])
        indices=np.concatenate([indices[keep],replacement])
    return positions,normals,uv,indices,colours

def write_glb(path,positions,normals,colours,indices,*,head_pivot=(0,0,0),morphs=None,uv=None,atlas_png=None,base_colour_factor=(1,1,1,1)):
    from PIL import Image
    positions=np.asarray(positions,dtype='<f4');normals=np.asarray(normals,dtype='<f4');colours=np.asarray(colours,dtype='<f4')
    indices=np.asarray(indices,dtype='<u2' if len(positions)<65535 else '<u4').reshape(-1)
    assert len(positions)<=20000 and len(indices)//3<=30000 and len(indices)%3==0
    assert positions.shape==normals.shape==(len(positions),3) and colours.shape==(len(positions),4)
    assert indices.max()<len(positions) and all(np.isfinite(a).all() for a in (positions,normals,colours))
    binary=bytearray();views=[];accessors=[]
    def add(array,kind):
        while len(binary)%4:binary.append(0)
        off=len(binary);binary.extend(array.tobytes());view=len(views)
        views.append({'buffer':0,'byteOffset':off,'byteLength':array.nbytes})
        accessor={'bufferView':view,'componentType':5126 if array.dtype.kind=='f' else 5123 if array.dtype.itemsize==2 else 5125,'count':len(array),'type':kind}
        if kind=='VEC3':accessor.update(min=array.min(axis=0).tolist(),max=array.max(axis=0).tolist())
        index=len(accessors);accessors.append(accessor);return index
    primitive={'attributes':{'POSITION':add(positions,'VEC3'),'NORMAL':add(normals,'VEC3'),'COLOR_0':add(colours,'VEC4')},'indices':add(indices,'SCALAR'),'material':0,'mode':4}
    mesh={'name':'Face','primitives':[primitive]}
    if atlas_png is not None:
        uv=np.asarray(uv,dtype='<f4');assert uv.shape==(len(positions),2) and np.isfinite(uv).all()
        assert (uv>=0).all() and (uv<=1).all(),'Atlas uses clamp sampling; validate source UV bounds'
        check=Image.open(io.BytesIO(atlas_png));assert check.mode=='RGB' and check.width<=2048 and check.height<=2048 and len(atlas_png)<=4*1024*1024
        primitive['attributes']['TEXCOORD_0']=add(uv,'VEC2')
    if morphs:
        assert len(morphs)<=64
        primitive['targets']=[{'POSITION':add(np.asarray(delta,dtype='<f4'),'VEC3')} for delta in morphs.values()]
        mesh.update(weights=[0.]*len(morphs),extras={'targetNames':list(morphs)})
    atlas_view=None
    if atlas_png is not None:
        while len(binary)%4:binary.append(0)
        atlas_view=len(views);views.append({'buffer':0,'byteOffset':len(binary),'byteLength':len(atlas_png)});binary.extend(atlas_png)
    logical=len(binary)
    while len(binary)%4:binary.append(0)
    doc={'asset':{'version':'2.0','generator':'Mirror offline head adapter'},'scene':0,'scenes':[{'nodes':[0]}],
         'nodes':[{'name':'Root','children':[1]},{'name':'Head','translation':list(map(float,head_pivot)),'children':[2,3]},{'name':'Face','mesh':0},{'name':'JawAttachments'}],
         'meshes':[mesh],'materials':[{'name':'BakedBaseColour','pbrMetallicRoughness':{'baseColorFactor':[1,1,1,1],'metallicFactor':0,'roughnessFactor':.75}}],
         'buffers':[{'byteLength':logical}],'bufferViews':views,'accessors':accessors}
    if atlas_view is not None:
        assert len(base_colour_factor)==4 and base_colour_factor[3]==1
        doc.update(extras={'mirrorAlbedoAtlas':1},images=[{'bufferView':atlas_view,'mimeType':'image/png'}],textures=[{'source':0}])
        pbr=doc['materials'][0]['pbrMetallicRoughness'];pbr['baseColorTexture']={'index':0,'texCoord':0};pbr['baseColorFactor']=list(base_colour_factor)
    encoded=json.dumps(doc,separators=(',',':')).encode('utf-8')
    encoded+=b' '*((-len(encoded))%4)
    raw=struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary
    Path(path).write_bytes(raw)

def bake_head(source,output,detail_levels=0,atlas=False):
    from PIL import Image
    import hashlib
    source=Path(source);output=Path(output);output.mkdir(parents=True,exist_ok=True)
    glb=Glb(source)
    assert len(glb.doc['nodes'])==1 and set(glb.doc['nodes'][0])<=set(('mesh','name')),'Validate a transformed/multi-node source separately'
    assert not any(glb.doc.get(k) for k in ('skins','animations','extensionsRequired'))
    assert len(glb.doc['meshes'])==1 and len(glb.doc['meshes'][0]['primitives'])==1
    primitive=glb.doc['meshes'][0]['primitives'][0]
    positions=rotate_x_front(glb.read(primitive['attributes']['POSITION'])).astype('<f4')
    scale=.24/np.ptp(positions[:,1]);positions*=scale
    normals=rotate_x_front(glb.read(primitive['attributes']['NORMAL'])).astype('<f4')
    normals/=np.linalg.norm(normals,axis=1)[:,None]
    colours=np.ones((len(positions),4),dtype='<f4') if atlas else glb.bake_base_colour(primitive)
    indices=glb.read(primitive['indices']).reshape(-1,3)
    uv=glb.read(primitive['attributes']['TEXCOORD_0']);source_vertices=len(positions)
    assert not (atlas and detail_levels),'Atlas requires no colour refinement'
    if detail_levels:
        positions,normals,uv,indices,colours=refine_texture_samples(positions,normals,uv,indices,lambda q:glb.sample_base_colour(q,primitive['material']),levels=detail_levels,
                region=lambda q:(q[:,2]>.025)&(abs(q[:,0])<.08)&(q[:,1]>-.10)&(q[:,1]<.075))
    pivot=np.array([0,float(positions[:,1].min())+.025,0],dtype=np.float32)
    atlas_png=None;factor=(1,1,1,1)
    if atlas:
        pbr=glb.doc['materials'][primitive['material']]['pbrMetallicRoughness'];factor=pbr.get('baseColorFactor',factor)
        texture=glb.doc['textures'][pbr['baseColorTexture']['index']]
        pixels=np.rint(glb.image(texture['source'])*255).astype(np.uint8)
        encoded=io.BytesIO();Image.fromarray(pixels).save(encoded,format='PNG',optimize=True,compress_level=9);atlas_png=encoded.getvalue()
        assert np.array_equal(np.asarray(Image.open(io.BytesIO(atlas_png))),pixels),'Lossless atlas conversion must retain every RGB texel'
    path=output/'head-base.glb';write_glb(path,positions-pivot,normals,colours,indices,head_pivot=pivot,uv=uv if atlas else None,atlas_png=atlas_png,base_colour_factor=factor)
    np.savez_compressed(output/'head-base.npz',positions=positions,normals=normals,colours=colours,indices=indices,uv=uv,head_pivot=pivot,scale=scale)
    report={'source':str(source.resolve()),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'output':str(path.resolve()),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
       'observed_source_front':'+X','rotation':'proper rotation: original (x,y,z) -> (-z,y,x)','scale':float(scale),'vertices':len(positions),'triangles':len(indices),
       'texture_conversion':'original sRGB base-colour retained as one embedded RGB8 PNG; normal/ORM not retained' if atlas else 'linear base-colour texels sampled bilinearly at vertices; normal/ORM textures not retained','facial_targets':0,'head_pivot':pivot.tolist(),
       'added_texture_detail_vertices':len(positions)-source_vertices,'detail_levels':detail_levels,
       'atlas_png_bytes':len(atlas_png) if atlas_png is not None else None,
       'atlas_lossless_rgb_verified':True if atlas_png is not None else None,
       'scope':'Compatible geometry stage only; not an importable completed facial avatar'}
    (output/'base-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return report

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--out',required=True);parser.add_argument('--detail-levels',type=int,default=0);parser.add_argument('--atlas',action='store_true')
    args=parser.parse_args();print(json.dumps(bake_head(args.source,args.out,args.detail_levels,args.atlas),ensure_ascii=False,indent=2))
