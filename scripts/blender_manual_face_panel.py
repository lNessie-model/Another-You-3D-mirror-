"""Session-local Blender UI for the delivered face rig; no add-on installation."""
import bpy
import json
import os
from pathlib import Path
from bpy.props import EnumProperty, StringProperty


def controls():
    obj=bpy.data.objects.get('FaceCaptureControls')
    if obj is None:raise RuntimeError('Open the delivered face-control workfile first')
    return obj


def select_face():
    if bpy.context.object and bpy.context.object.mode!='OBJECT':bpy.ops.object.mode_set(mode='OBJECT')
    face=bpy.data.objects['Face'];bpy.ops.object.select_all(action='DESELECT')
    face.select_set(True);bpy.context.view_layer.objects.active=face
    for screen in bpy.data.screens:
        for area in screen.areas:
            if area.type=='PROPERTIES':area.spaces.active.context='DATA'
    return face


def neutral():
    obj=controls()
    for name in obj.keys():
        if name=='_neutral' or name.startswith(('eye','brow','cheek','jaw','mouth','nose','head_')):obj[name]=0.0
    obj['mirror_motion']=False;obj['expression_gain']=1.0
    obj.update_tag();bpy.context.view_layer.update()


class MIRROR_OT_focus_face(bpy.types.Operator):
    bl_idname='mirror.focus_face';bl_label='显示头部形态键'
    def execute(self,context):select_face();return {'FINISHED'}


class MIRROR_OT_neutral(bpy.types.Operator):
    bl_idname='mirror.neutral_face';bl_label='清零预览数值';bl_options={'REGISTER','UNDO'}
    def execute(self,context):select_face();neutral();return {'FINISHED'}


class MIRROR_OT_edit_shape(bpy.types.Operator):
    bl_idname='mirror.edit_shape';bl_label='进入表情形状编辑';bl_options={'REGISTER','UNDO'}
    shape_name:StringProperty(default='')
    def execute(self,context):
        name=self.shape_name or context.window_manager.mirror_manual_shape
        face=select_face();obj=controls()
        if name not in obj or name.startswith('eyeLook'):
            self.report({'ERROR'},'此入口只编辑直接面部形态键；眼球视线使用刚性节点')
            return {'CANCELLED'}
        neutral();obj[name]=1.0;obj.update_tag();context.view_layer.update()
        face.active_shape_key_index=face.data.shape_keys.key_blocks.find(name)
        face.show_only_shape_key=False;face.use_shape_key_edit_mode=False
        bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='DESELECT')
        self.report({'INFO'},'修改 '+name+'：选顶点，用G移动；Tab或完成按钮返回预览')
        return {'FINISHED'}


class MIRROR_OT_finish_shape(bpy.types.Operator):
    bl_idname='mirror.finish_shape';bl_label='完成形状编辑，返回预览';bl_options={'REGISTER','UNDO'}
    def execute(self,context):select_face();return {'FINISHED'}


class MIRROR_PT_manual_face(bpy.types.Panel):
    bl_label='面捕手调：预览 / 修改表情形状'
    bl_space_type='PROPERTIES';bl_region_type='WINDOW';bl_context='data';bl_order=-10000
    @classmethod
    def poll(cls,context):return 'FaceCaptureControls' in bpy.data.objects and context.object and context.object.type=='MESH'
    def draw(self,context):
        layout=self.layout;obj=controls();editing=context.object.mode=='EDIT'
        layout.label(text='Left 是角色自己的左侧（画面右侧）')
        layout.label(text='手工预览：镜像关、强度1；设备参数不变')
        if editing:
            layout.label(text='选顶点 → G移动；O开启衰减编辑',icon='EDITMODE_HLT')
            layout.operator('mirror.finish_shape',icon='CHECKMARK')
        else:
            box=layout.box();box.label(text='① 拖动数字，只预览表情')
            for left,right,label in [('eyeBlinkLeft','eyeBlinkRight','闭眼'),('eyeSquintLeft','eyeSquintRight','眯眼'),
                                     ('mouthSmileLeft','mouthSmileRight','微笑')]:
                row=box.row(align=True);row.prop(obj,'["'+left+'"]',text='左'+label,slider=True)
                row.prop(obj,'["'+right+'"]',text='右'+label,slider=True)
            box.prop(obj,'["jawOpen"]',text='张嘴',slider=True);box.prop(obj,'["browInnerUp"]',text='眉头上抬',slider=True)
            box.operator('mirror.neutral_face',icon='LOOP_BACK')
            box=layout.box();box.label(text='② 选表情，再修改顶点')
            box.prop(context.window_manager,'mirror_manual_shape',text='要改的表情')
            box.operator('mirror.edit_shape',icon='EDITMODE_HLT')
        layout.label(text='Ctrl+Shift+S 另存副本；不会自动下发设备')
        layout.label(text='下方原生“形态键”列表仍可使用')


class MIRROR_PT_manual_sidebar(bpy.types.Panel):
    bl_label='面捕手调：预览 / 修改表情形状'
    bl_space_type='VIEW_3D';bl_region_type='UI';bl_category='Item';bl_order=-10000
    @classmethod
    def poll(cls,context):return MIRROR_PT_manual_face.poll(context)
    def draw(self,context):MIRROR_PT_manual_face.draw(self,context)


CLASSES=(MIRROR_OT_focus_face,MIRROR_OT_neutral,MIRROR_OT_edit_shape,MIRROR_OT_finish_shape,MIRROR_PT_manual_face,MIRROR_PT_manual_sidebar)


def register():
    obj=controls();face=bpy.data.objects['Face']
    names=[key.name for key in face.data.shape_keys.key_blocks if key.name in obj and not key.name.startswith('eyeLook')]
    assert 'eyeBlinkLeft' in names
    labels={'eyeBlinkLeft':'左眼闭合','eyeBlinkRight':'右眼闭合','eyeSquintLeft':'左眼眯眼','eyeSquintRight':'右眼眯眼',
            'jawOpen':'张嘴','mouthSmileLeft':'左嘴角微笑','mouthSmileRight':'右嘴角微笑','browInnerUp':'眉头上抬'}
    bpy.types.WindowManager.mirror_manual_shape=EnumProperty(name='表情',items=[(name,labels.get(name,name)+' ('+name+')' if name in labels else name,'只修改这个表情的顶点') for name in names],default='eyeBlinkLeft')
    for cls in CLASSES:bpy.utils.register_class(cls)
    select_face();neutral()
    if not bpy.app.background:
        report={'pid':os.getpid(),'workfile':bpy.data.filepath,'activeObject':bpy.context.view_layer.objects.active.name,
                'panelRegistered':True,'propertiesContexts':[area.spaces.active.context for area in bpy.context.screen.areas if area.type=='PROPERTIES'],
                'mirror':False,'expressionGain':1,'scope':'Live Blender session initialization, not a screenshot or user-edit confirmation'}
        (Path(bpy.data.filepath).parent/('manual-ui-ready-'+str(os.getpid())+'.json')).write_text(json.dumps(report,indent=2),'utf-8')


if __name__=='__main__':register()
