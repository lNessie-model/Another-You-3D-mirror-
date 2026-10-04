"""Run in real Blender, including with --disable-autoexec, against a production Java oracle."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import bpy
from mathutils import Matrix
import numpy as np


def fingerprint():
    digest=hashlib.sha256()
    for obj in sorted((o for o in bpy.data.objects if o.type=='MESH'),key=lambda o:o.name):
        mesh=obj.data
        digest.update(obj.name.encode())
        for collection,attribute,count,dtype in [(mesh.vertices,'co',3,'<f4'),
                (mesh.loops,'vertex_index',1,'<i4'),(mesh.polygons,'material_index',1,'<i4')]:
            values=np.empty(len(collection)*count,dtype=dtype);collection.foreach_get(attribute,values);digest.update(values.tobytes())
        for uv in mesh.uv_layers:
            values=np.empty(len(uv.data)*2,dtype='<f4');uv.data.foreach_get('uv',values)
            digest.update(uv.name.encode());digest.update(values.tobytes())
        for color in mesh.color_attributes:
            values=np.empty(len(color.data)*4,dtype='<f4');color.data.foreach_get('color',values)
            digest.update(color.name.encode());digest.update(values.tobytes())
        if mesh.shape_keys:
            for key in mesh.shape_keys.key_blocks:
                values=np.empty(len(key.data)*3,dtype='<f4');key.data.foreach_get('co',values)
                digest.update(key.name.encode());digest.update(key.relative_key.name.encode());digest.update(values.tobytes())
        digest.update(json.dumps([m.name for m in mesh.materials]).encode())
    for image in sorted(bpy.data.images,key=lambda i:i.name):
        if image.packed_file:
            digest.update(image.name.encode());digest.update(image.packed_file.data)
    return digest.hexdigest()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--reference',required=True)
    parser.add_argument('--baseline');parser.add_argument('--report');parser.add_argument('--weights-only',action='store_true')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    reference=json.loads(Path(args.reference).read_text('utf-8'));workfile=bpy.data.filepath
    controls=bpy.data.objects.get('FaceCaptureControls')
    assert controls is not None,'Editable project lacks actual-runtime face controls'
    baseline=None
    if args.baseline:
        bpy.ops.wm.open_mainfile(filepath=str(Path(args.baseline).resolve()));baseline=fingerprint()
        bpy.ops.wm.open_mainfile(filepath=workfile);controls=bpy.data.objects['FaceCaptureControls']
        assert fingerprint()==baseline,'Adding controls changed geometry, topology, UVs, colors, shapes or packed textures'
    assert controls['source_model_sha256']==reference['modelSha256']
    for name in reference['inputNames']:assert name in controls,'Missing input '+name
    conversion=Matrix(((1,0,0,0),(0,0,-1,0),(0,1,0,0),(0,0,0,1)))
    inverse=conversion.inverted();max_weight_error=0;max_matrix_error=0
    checks=0
    for case in reference['cases']:
        for name,value in zip(reference['inputNames'],case['source']):controls[name]=value
        controls['mirror_motion']=case['mirror'];controls['expression_gain']=case['gain']
        for name,value in zip(('head_pitch','head_yaw','head_roll'),case['headAngles']):controls[name]=value
        controls.update_tag();bpy.context.view_layer.update()
        for mesh in case['meshes']:
            obj=bpy.data.objects.get(mesh['name'])
            if not mesh['targetNames']:continue
            keys=obj.data.shape_keys.key_blocks
            for name,expected in zip(mesh['targetNames'],mesh['weights']):
                error=abs(keys[name].value-expected);max_weight_error=max(max_weight_error,error)
                assert error<=2e-6,(case['name'],name,keys[name].value,expected)
                checks+=1
        if not args.weights_only:
            for node in case['nodes']:
                values=node['worldMatrixColumnMajor']
                expected=conversion@Matrix(tuple(tuple(values[c*4+r] for c in range(4)) for r in range(4)))@inverse
                actual=bpy.data.objects[node['name']].matrix_world
                error=max(abs(actual[r][c]-expected[r][c]) for r in range(4) for c in range(4))
                max_matrix_error=max(max_matrix_error,error)
                assert error<=2e-6,(case['name'],node['name'],error)
                checks+=16
    drivers=[f for obj in bpy.data.objects if obj.animation_data for f in obj.animation_data.drivers]
    drivers += [f for key in bpy.data.shape_keys if key.animation_data for f in key.animation_data.drivers]
    assert drivers
    for f in drivers:
        assert f.driver.is_valid,(f.data_path,'Invalid driver')
        assert f.driver.is_simple_expression,(f.data_path,'Driver requires Python auto execution')
    result={'workfile':workfile,'blender':bpy.app.version_string,'cases':len(reference['cases']),
            'checks':checks,'drivers':len(drivers),'maxWeightError':max_weight_error,'maxMatrixError':max_matrix_error,
            'weightsOnly':args.weights_only,'preservedAssetFingerprint':baseline,
            'autoexecEnabled':bpy.context.preferences.filepaths.use_scripts_auto_execute,
            'scope':'Actual shape values and node transforms; no lighting, detector, temporal response or optical equivalence'}
    if args.report:
        path=Path(args.report);assert not path.exists();path.write_text(json.dumps(result,indent=2),'utf-8')
    print('BLENDER_FACE_CONTROL_CHECK '+json.dumps(result),flush=True)


if __name__=='__main__':main()
