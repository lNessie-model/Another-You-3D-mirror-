"""Exercise the real UI operators and edit a vertex without changing other keys."""
import argparse
import json
from pathlib import Path
import runpy
import sys

import bpy
import bmesh
import numpy as np

parser=argparse.ArgumentParser();parser.add_argument('--panel',required=True);parser.add_argument('--report',required=True)
args=parser.parse_args(sys.argv[sys.argv.index('--')+1:])
runpy.run_path(args.panel,run_name='__main__')
assert hasattr(bpy.types,'MIRROR_PT_manual_sidebar'),'Missing viewport hand-edit entry'
face=bpy.data.objects['Face'];keys=face.data.shape_keys.key_blocks
before={key.name:np.array([v.co[:] for v in key.data]) for key in keys}
assert bpy.ops.mirror.edit_shape(shape_name='eyeBlinkLeft')=={'FINISHED'}
assert face.mode=='EDIT' and face.active_shape_key.name=='eyeBlinkLeft'
assert bpy.data.objects['FaceCaptureControls']['eyeBlinkLeft']==1
assert bpy.data.objects['FaceCaptureControls']['expression_gain']==1
assert not bpy.data.objects['FaceCaptureControls']['mirror_motion']
bm=bmesh.from_edit_mesh(face.data);bm.verts.ensure_lookup_table()
index=int(np.argmax(np.linalg.norm(before['eyeBlinkLeft']-before['Basis'],axis=1)))
bm.verts[index].co.z+=.001;bmesh.update_edit_mesh(face.data)
assert bpy.ops.mirror.finish_shape()=={'FINISHED'} and face.mode=='OBJECT'
for key in keys:
    actual=np.array([v.co[:] for v in key.data])
    if key.name=='eyeBlinkLeft':
        changed=np.flatnonzero(np.any(actual!=before[key.name],axis=1));assert changed.tolist()==[index]
        assert abs(float(actual[index,2]-before[key.name][index,2])-.001)<1e-7
    else:assert np.array_equal(actual,before[key.name]),key.name
assert bpy.ops.mirror.neutral_face()=={'FINISHED'}
assert all(bpy.data.objects['FaceCaptureControls'][name]==0 for name in ('eyeBlinkLeft','eyeBlinkRight','jawOpen'))
path=Path(args.report);assert not path.exists()
report={'workfile':bpy.data.filepath,'operatorsExercised':['mirror.edit_shape','mirror.finish_shape','mirror.neutral_face'],
        'activeEditedKey':'eyeBlinkLeft','editedVertex':index,'deltaMeters':.001,'basisAndOtherKeysUnchanged':True,
        'inputWorkfileSaved':False,'scope':'Actual Blender UI operators and isolated shape edit; no device export or art acceptance'}
path.write_text(json.dumps(report,indent=2),'utf-8');print('MANUAL_PANEL_CHECK '+json.dumps(report))
