"""Open a separate user workfile at an editable blink, without modifying its file."""
import json
import os
from pathlib import Path
import sys

import bpy

sys.path.insert(0,str(Path(__file__).resolve().parent))
import blender_manual_face_panel as panel


def start():
    panel.register()
    result=bpy.ops.mirror.edit_shape(shape_name='eyeBlinkLeft')
    assert result=={'FINISHED'}
    face=bpy.context.object
    assert face.mode=='EDIT' and face.active_shape_key.name=='eyeBlinkLeft'
    # Select this eyelid's moved vertices so frame-selected lands on its eye.
    import bmesh
    bm=bmesh.from_edit_mesh(face.data);bm.verts.ensure_lookup_table()
    basis=face.data.shape_keys.key_blocks['Basis'];shape=face.data.shape_keys.key_blocks['eyeBlinkLeft']
    selected=[]
    for vertex in bm.verts:
        vertex.select=(shape.data[vertex.index].co-basis.data[vertex.index].co).length>5e-5
        if vertex.select:selected.append(vertex.index)
    assert selected,'Blink has no editable eyelid vertices'
    bmesh.update_edit_mesh(face.data)
    framed=[]
    for area in bpy.context.screen.areas:
        if area.type!='VIEW_3D':continue
        space=area.spaces.active;space.show_region_ui=True;space.shading.type='MATERIAL'
        space.region_3d.view_rotation=(.7071067811865476,.7071067811865476,0,0)
        space.region_3d.view_perspective='ORTHO'
        region=next(r for r in area.regions if r.type=='WINDOW')
        with bpy.context.temp_override(area=area,region=region):
            assert bpy.ops.view3d.view_selected()=={'FINISHED'}
        # Frame the eye with surrounding skin, rather than the whole head.
        space.region_3d.view_distance*=1.6
        framed.append({'width':area.width,'height':area.height})
    assert framed,'No editable viewport'
    report={'pid':os.getpid(),'workfile':bpy.data.filepath,'mode':face.mode,'shape':face.active_shape_key.name,
            'selectedVertices':len(selected),'framedViewports':framed,'sidebarRegistered':hasattr(bpy.types,'MIRROR_PT_manual_sidebar'),
            'inputWorkfileSaved':False,'foregroundVerified':False}
    (Path(bpy.data.filepath).parent/'eye-edit-ready.json').write_text(json.dumps(report,indent=2),'utf-8')
    return None


if __name__=='__main__':bpy.app.timers.register(start,first_interval=1)
