"""Render the actual driven authoring rig and save a neutral workfile with instructions."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import bpy


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',required=True)
    parser.add_argument('--workfile-out',required=True);parser.add_argument('--instructions',required=True)
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    out=Path(args.out);workfile=Path(args.workfile_out)
    assert not out.exists() and not workfile.exists(),'Preserve previous previews and workfiles'
    out.mkdir(parents=True);controls=bpy.data.objects['FaceCaptureControls']
    names=[key for key in controls.keys() if key.startswith(('eye','brow','cheek','jaw','mouth','nose')) or key=='_neutral']
    assert len(names)==52
    scene=bpy.context.scene;scene.render.resolution_x=640;scene.render.resolution_y=640
    scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG'
    poses=[('neutral',{}),('blink-half',{'eyeBlinkLeft':.5,'eyeBlinkRight':.5}),
            ('blink-wide',{'eyeBlinkLeft':1,'eyeBlinkRight':1,'eyeWideLeft':1,'eyeWideRight':1}),
            ('blink-squint',{'eyeBlinkLeft':1,'eyeBlinkRight':1,'eyeSquintLeft':1,'eyeSquintRight':1}),
            ('jaw-close',{'jawOpen':.8,'mouthClose':.8}),
            ('smile-jaw',{'jawOpen':.35,'mouthSmileLeft':.6,'mouthSmileRight':.6}),
            ('wink-gaze',{'eyeBlinkLeft':1,'eyeLookOutLeft':.6,'eyeLookInRight':.6,'head_yaw':18})]
    def reset():
        for name in names:controls[name]=0.0
        for name in ('head_pitch','head_yaw','head_roll'):controls[name]=0.0
        controls['mirror_motion']=True;controls['expression_gain']=2.5
    rows=[]
    for name,inputs in poses:
        reset()
        for key,value in inputs.items():controls[key]=value
        controls.update_tag();bpy.context.view_layer.update()
        path=out/(name+'.png');scene.render.filepath=str(path.resolve());bpy.ops.render.render(write_still=True)
        weights={key.name:key.value for key in bpy.data.objects['Face'].data.shape_keys.key_blocks if key.name!='Basis'}
        rows.append({'pose':name,'controls':inputs,'image':str(path.resolve()),
                'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'evaluatedWeights':weights})
        print('DRIVEN_FACE_RENDERED '+name,flush=True)
    reset();controls.update_tag();bpy.context.view_layer.update()
    text=bpy.data.texts.new('面部精修操作说明.md');text.write(Path(args.instructions).read_text('utf-8'))
    bpy.ops.object.select_all(action='DESELECT');controls.select_set(True);bpy.context.view_layer.objects.active=controls
    for screen in bpy.data.screens:
        for area in screen.areas:
            if area.type=='PROPERTIES':area.spaces.active.context='OBJECT'
            elif area.type=='VIEW_3D':
                area.spaces.active.shading.type='MATERIAL'
                area.spaces.active.region_3d.view_perspective='CAMERA'
    bpy.ops.wm.save_as_mainfile(filepath=str(workfile.resolve()))
    result={'workfile':str(workfile.resolve()),'workfileSha256':hashlib.sha256(workfile.read_bytes()).hexdigest(),
            'sourceModelSha256':controls['source_model_sha256'],'mirror':True,'expressionGain':2.5,
            'poses':rows,'scope':'Offline Blender images of the actual driven rig; lighting differs from device, no detector/FPS/optical acceptance'}
    (out/'render-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print('DRIVEN_FACE_PREVIEW_COMPLETE '+str(workfile.resolve()),flush=True)


if __name__=='__main__':main()
