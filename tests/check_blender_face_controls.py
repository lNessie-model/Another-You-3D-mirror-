"""Run in real Blender, including with --disable-autoexec, against a production Java oracle."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import bpy
import bmesh
from mathutils import Matrix
import numpy as np


def fingerprint():
    digest=hashlib.sha256()
    used_materials=set()
    for obj in sorted((o for o in bpy.data.objects if o.type=='MESH'),key=lambda o:o.name):
        mesh=obj.data
        digest.update(obj.name.encode())
        for collection,attribute,count,dtype in [(mesh.vertices,'co',3,'<f4'),
                (mesh.loops,'vertex_index',1,'<i4'),(mesh.polygons,'loop_start',1,'<i4'),
                (mesh.polygons,'loop_total',1,'<i4'),(mesh.polygons,'material_index',1,'<i4'),
                (mesh.corner_normals,'vector',3,'<f4')]:
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
                digest.update(json.dumps([key.vertex_group,key.slider_min,key.slider_max,key.mute]).encode())
        digest.update(json.dumps([m.name for m in mesh.materials]).encode())
        used_materials.update(m.name for m in mesh.materials)
    # Blender discards unused default materials on save; they are not part of the head.
    for material in sorted((m for m in bpy.data.materials if m.name in used_materials),key=lambda m:m.name):
        digest.update(material.name.encode())
        if not material.node_tree:continue
        tree=material.node_tree
        for node in sorted(tree.nodes,key=lambda n:n.name):
            digest.update(json.dumps([node.name,node.type]).encode())
            if node.type=='TEX_IMAGE':digest.update(json.dumps([node.image.name,node.interpolation,node.extension]).encode())
            for socket in node.inputs:
                if not hasattr(socket,'default_value'):continue
                value=socket.default_value
                if not isinstance(value,(str,int,float,bool)):value=list(value)
                digest.update(json.dumps([socket.identifier,value]).encode())
        digest.update(json.dumps(sorted((link.from_node.name,link.from_socket.identifier,
                link.to_node.name,link.to_socket.identifier) for link in tree.links)).encode())
    for image in sorted(bpy.data.images,key=lambda i:i.name):
        if image.packed_file:
            digest.update(json.dumps([image.name,image.colorspace_settings.name,list(image.size)]).encode())
            digest.update(image.packed_file.data)
    return digest.hexdigest()


def check_manual_edit(controls,names):
    """Actually enter edit mode: a driven key must remain editable without altering Basis/other keys."""
    obj=bpy.data.objects['Face'];keys=obj.data.shape_keys.key_blocks
    for name in names:controls[name]=0.0
    for name in ('head_pitch','head_yaw','head_roll'):controls[name]=0.0
    controls['mirror_motion']=False;controls['expression_gain']=1.0;controls['eyeBlinkLeft']=1.0
    controls.update_tag();bpy.context.view_layer.update()
    before={}
    for key in keys:
        values=np.empty(len(key.data)*3,dtype='<f4');key.data.foreach_get('co',values);before[key.name]=values.reshape(-1,3)
    index=int(np.argmax(np.linalg.norm(before['eyeBlinkLeft']-before['Basis'],axis=1)))
    bpy.ops.object.select_all(action='DESELECT');obj.select_set(True);bpy.context.view_layer.objects.active=obj
    obj.active_shape_key_index=keys.find('eyeBlinkLeft');obj.use_shape_key_edit_mode=False
    bpy.ops.object.mode_set(mode='EDIT')
    bm=bmesh.from_edit_mesh(obj.data);bm.verts.ensure_lookup_table()
    bm.verts[index].co.z+=.001;bmesh.update_edit_mesh(obj.data)
    bpy.ops.object.mode_set(mode='OBJECT');bpy.context.view_layer.update()
    for key in keys:
        values=np.empty(len(key.data)*3,dtype='<f4');key.data.foreach_get('co',values);values=values.reshape(-1,3)
        if key.name=='eyeBlinkLeft':
            changed=np.flatnonzero(np.any(values!=before[key.name],axis=1))
            assert changed.tolist()==[index],'Edited vertices escaped active key'
            assert abs(float(values[index,2]-before[key.name][index,2])-.001)<1e-7
        else:assert np.array_equal(values,before[key.name]),'Manual edit changed '+key.name
    evaluated=obj.evaluated_get(bpy.context.evaluated_depsgraph_get()).to_mesh()
    assert np.max(np.abs(np.array(evaluated.vertices[index].co)-np.array(keys['eyeBlinkLeft'].data[index].co)))<1e-7
    obj.evaluated_get(bpy.context.evaluated_depsgraph_get()).to_mesh_clear()
    # Restore in memory; this checker never saves the input project.
    bpy.ops.object.mode_set(mode='EDIT');bm=bmesh.from_edit_mesh(obj.data);bm.verts.ensure_lookup_table()
    bm.verts[index].co=before['eyeBlinkLeft'][index];bmesh.update_edit_mesh(obj.data);bpy.ops.object.mode_set(mode='OBJECT')
    return {'shape':'eyeBlinkLeft','editedVertex':index,'deltaMeters':.001,
            'basisAndOtherShapesUnchanged':True,'evaluatedShapeMatched':True,'inputFileSaved':False}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--reference',required=True)
    parser.add_argument('--baseline');parser.add_argument('--report');parser.add_argument('--weights-only',action='store_true')
    parser.add_argument('--manual-edit',action='store_true')
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
    edit_result=check_manual_edit(controls,reference['inputNames']) if args.manual_edit else None
    result={'workfile':workfile,'workfileSha256':hashlib.sha256(Path(workfile).read_bytes()).hexdigest(),
            'sourceModelSha256':reference['modelSha256'],
            'referenceSha256':hashlib.sha256(Path(args.reference).read_bytes()).hexdigest(),
            'blender':bpy.app.version_string,'cases':len(reference['cases']),
            'checks':checks,'drivers':len(drivers),'maxWeightError':max_weight_error,'maxMatrixError':max_matrix_error,
            'weightsOnly':args.weights_only,'preservedAssetFingerprint':baseline,
            'manualEdit':edit_result,
            'autoexecEnabled':bpy.context.preferences.filepaths.use_scripts_auto_execute,
            'scope':'Actual shape values and node transforms; no lighting, detector, temporal response or optical equivalence'}
    if args.report:
        path=Path(args.report);assert not path.exists();path.write_text(json.dumps(result,indent=2),'utf-8')
    print('BLENDER_FACE_CONTROL_CHECK '+json.dumps(result),flush=True)


if __name__=='__main__':main()
