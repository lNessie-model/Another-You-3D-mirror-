"""Add editable, self-contained face controls to a copy of the packed Blender workfile.

Expressions use Blender's simple-driver subset, without a Python driver namespace.
This is an authoring preview, not an asset export or detector/temporal-filter emulator.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import bpy


class ControlDriver:
    def __init__(self,curve,controls,names,permutation):
        self.driver=curve.driver;self.driver.type='SCRIPTED'
        self.controls=controls;self.names=names;self.permutation=permutation;self.variables={}
        self.mirror=self.variable('mirror_motion');self.gain=self.variable('expression_gain')

    def variable(self,property_name):
        if property_name not in self.variables:
            name='v'+str(len(self.variables));variable=self.driver.variables.new()
            variable.name=name;variable.type='SINGLE_PROP'
            variable.targets[0].id_type='OBJECT';variable.targets[0].id=self.controls
            variable.targets[0].data_path='['+json.dumps(property_name)+']'
            self.variables[property_name]=name
        return self.variables[property_name]

    def source(self,name):
        index=self.names.index(name);other=self.names[self.permutation[index]]
        direct=self.variable(name);reflected=self.variable(other)
        value=direct if direct==reflected else '('+reflected+' if '+self.mirror+' else '+direct+')'
        value='min(1,max(0,'+value+'))'
        # Match the independent artist response in actual FacePlayback.
        if name.startswith('eyeBlink'):
            value='((1-cos(pi*min(1,max(0,('+value+'-.04)/.68))))*.5)'
        elif name.startswith('eyeWide'):value='(1-pow(1-'+value+',1.65))'
        elif name.startswith('browDown'):
            up='min(1,max(0,'+self.variable('browInnerUp')+'))'
            value='(max(0,('+value+'-.12)/.88)*pow(1-'+up+',2*'+self.gain+'))'
        else:
            amplify=index!=0 and not name.startswith(('eye','cheekSquint')) and name!='mouthClose'
            if amplify:value='(1-pow(1-'+value+','+self.gain+'))'
        return value

    def set(self,expression):
        self.driver.expression=expression
        assert self.driver.expression==expression,'Blender truncated a driver expression'
        assert self.driver.is_simple_expression,'Driver needs Python execution: '+expression


def joint_controls(controls,names,permutation,rig):
    config=rig['rig']
    assert config['gazeMode']=='joint' and config['jawMode']=='morph'
    assert config['jawAxis']==[1,0,0],'Only the current +X attachment pivot is supported'
    # glTF C(x,y,z)=(x,-z,y): Rz(roll) Ry(yaw) Rx(pitch) becomes
    # Ry(-roll) Rz(yaw) Rx(pitch), i.e. Blender XZY Euler order.
    for name in [config['headNode'],config['leftEyeNode'],config['rightEyeNode'],config['jawAttachmentNode']]:
        obj=bpy.data.objects[name]
        assert not obj.animation_data or not obj.animation_data.drivers,'Pre-existing joint drivers'
        basis=obj.matrix_basis.to_3x3()
        assert max(abs(basis[r][c]-(1 if r==c else 0)) for r in range(3) for c in range(3))<1e-6,'Unexpected base joint orientation'
        obj.rotation_mode='XZY';obj.rotation_euler=(0,0,0)
    head=bpy.data.objects[config['headNode']]
    for index,name,reflect in [(0,'head_pitch',False),(1,'head_roll',True),(2,'head_yaw',True)]:
        driver=ControlDriver(head.driver_add('rotation_euler',index),controls,names,permutation)
        angle=driver.variable(name)
        if reflect:angle='(-'+angle+' if '+driver.mirror+' else '+angle+')'
        if index==1:angle='-('+angle+')'
        driver.set('radians('+angle+')')
    for side,node,outward,inward in [('Left',config['leftEyeNode'],'eyeLookOutLeft','eyeLookInLeft'),
            ('Right',config['rightEyeNode'],'eyeLookInRight','eyeLookOutRight')]:
        obj=bpy.data.objects[node]
        for index,positive,negative,amount in [(0,'eyeLookDown'+side,'eyeLookUp'+side,config['gazePitchDegrees']),
                (2,outward,inward,config['gazeYawDegrees'])]:
            driver=ControlDriver(obj.driver_add('rotation_euler',index),controls,names,permutation)
            driver.set('radians('+repr(amount)+'*('+driver.source(positive)+'-'+driver.source(negative)+'))')
    jaw=bpy.data.objects[config['jawAttachmentNode']]
    driver=ControlDriver(jaw.driver_add('rotation_euler',0),controls,names,permutation)
    driver.set('radians('+repr(config['jawOpenDegrees'])+'*'+driver.source('jawOpen')+')')
    for index,amount,positive,negative in [(0,config['jawLateralMeters'],'jawLeft','jawRight'),
            (1,-config['jawForwardMeters'],'jawForward',None)]:
        base=jaw.location[index];driver=ControlDriver(jaw.driver_add('location',index),controls,names,permutation)
        value=driver.source(positive)
        if negative:value+='-'+driver.source(negative)
        driver.set(repr(base)+'+'+repr(amount)+'*('+value+')')


def build(reference,manifest,out):
    out=Path(out);assert not out.exists(),'Preserve previous editable workfiles'
    assert 'FaceCaptureControls' not in bpy.data.objects,'Controls already installed'
    data=json.loads(Path(reference).read_text('utf-8'));rig=json.loads(Path(manifest).read_text('utf-8'))
    assert rig['schemaVersion']==2 and rig['modelSha256']==data['modelSha256']
    assert rig['inputSchema']=='mediapipe-face-blendshapes-v1'
    names=data['inputNames'];permutation=data['mirrorPermutation']
    assert len(names)==52 and len(permutation)==52
    assert all(permutation[permutation[i]]==i for i in range(52))
    controls=bpy.data.objects.new('FaceCaptureControls',None);bpy.context.scene.collection.objects.link(controls)
    controls.empty_display_type='PLAIN_AXES';controls.empty_display_size=.025
    controls.hide_render=True;controls.lock_location=(True,True,True)
    controls.lock_rotation=(True,True,True);controls.lock_scale=(True,True,True)
    labels={'eyeBlinkLeft':'角色左眼闭眼','eyeBlinkRight':'角色右眼闭眼','jawOpen':'张嘴',
            'mouthClose':'闭唇修正输入','mouthSmileLeft':'角色左嘴角微笑','mouthSmileRight':'角色右嘴角微笑',
            'browInnerUp':'眉毛内侧抬起','_neutral':'中性置信度，不驱动表情'}
    for name in names:
        controls[name]=0.0
        controls.id_properties_ui(name).update(min=0,max=1,soft_min=0,soft_max=1,
                description=labels.get(name,name)+'；已校准并平滑的面捕系数，范围0–1')
    controls['mirror_motion']=True
    controls.id_properties_ui('mirror_motion').update(description='与设备镜子模式相同：交换左右表情和眼球输入，反转偏航/侧倾')
    controls['expression_gain']=2.5
    controls.id_properties_ui('expression_gain').update(min=.5,max=4,soft_min=.5,soft_max=4,
            description='嘴眉上挑强度；眉压下、闭眼和睁大使用独立响应，视线/眯眼/mouthClose不放大')
    for name,label in [('head_pitch','点头'),('head_yaw','左右转头'),('head_roll','左右歪头')]:
        controls[name]=0.0
        controls.id_properties_ui(name).update(min=-90,max=90,soft_min=-45,soft_max=45,description=label+'；角度制')
    controls['source_model_sha256']=data['modelSha256']
    controls['source_reference_sha256']=hashlib.sha256(Path(reference).read_bytes()).hexdigest()
    assigned=set()
    for binding in rig['bindings']:
        keys=bpy.data.objects[binding['mesh']].data.shape_keys
        key=keys.key_blocks[binding['target']]
        assert not keys.animation_data or not keys.animation_data.drivers.find(key.path_from_id('value'))
        driver=ControlDriver(key.driver_add('value'),controls,names,permutation)
        value=driver.source(binding['source'])
        dead=binding.get('deadZone',0);gamma=binding.get('gamma',1)
        value='pow(max(0,('+value+'-'+repr(dead)+')/(1-'+repr(dead)+')),'+repr(gamma)+')'
        value='max('+repr(binding.get('min',0))+',min('+repr(binding.get('max',1))+','+value+'*'+repr(binding.get('gain',1))+'+'+repr(binding.get('bias',0))+'))'
        driver.set(value);assigned.add((binding['mesh'],binding['target']))
    for binding in rig['derivedBindings']:
        assert binding['operation']=='product'
        key=bpy.data.objects[binding['mesh']].data.shape_keys.key_blocks[binding['target']]
        driver=ControlDriver(key.driver_add('value'),controls,names,permutation)
        driver.set('*'.join('('+driver.source(name)+')' for name in binding['sources'])+'*'+repr(binding.get('gain',1)))
        assigned.add((binding['mesh'],binding['target']))
    # Legacy gaze shape keys must stay disabled: current gaze moves actual eye objects.
    for obj in bpy.data.objects:
        if obj.type=='MESH' and obj.data.shape_keys:
            for key in obj.data.shape_keys.key_blocks:
                if key.name!='Basis' and (obj.name,key.name) not in assigned:
                    assert key.value==0,'Unexpected nonzero unmapped shape '+key.name
                    ControlDriver(key.driver_add('value'),controls,names,permutation).set('0')
    joint_controls(controls,names,permutation,rig)
    bpy.ops.object.select_all(action='DESELECT');controls.select_set(True);bpy.context.view_layer.objects.active=controls
    controls.update_tag();bpy.context.view_layer.update()
    out.parent.mkdir(parents=True,exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=str(out.resolve()))
    print('BLENDER_FACE_CONTROLS_SAVED '+str(out.resolve()),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--reference',required=True)
    parser.add_argument('--manifest',required=True);parser.add_argument('--out',required=True)
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    build(args.reference,args.manifest,args.out)
