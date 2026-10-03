"""Pure evidence gate, including plausible wrong-context recovery; never uses ADB."""
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import run_runtime_check as runtime


def row(t,session,generation,frames,completed,state='INTERACTIVE'):
    return dict(updated_monotonic_ns=t*10**9,updated_elapsed_ns=(t+100)*10**9,
        activity_instance_id='11111111-1111-1111-1111-111111111111',runtime_session_id=('aaaaaaaa' if session=='old' else 'bbbbbbbb')+'-1111-1111-1111-111111111111',runtime_epoch=1 if session=='old' else 2,
        input='camera_replay',state=state,face_present=state=='INTERACTIVE',result_age_ms=100,error='',render_error='',control_error='',
        render_fault=False,processing_fault=False,gl_context_policy=dict(release_on_pause_requested=True,preserve_on_pause_requested=False,preserve_on_pause_actual=False),
        view_count=16,view_width=400,view_height=720,
        renderer=dict(context_generation=generation,frame_generation=generation if state=='INTERACTIVE' else 0,
            runtime_gl_frame_ready=state=='INTERACTIVE',frames=frames,error='',view_count=16,view_width=400,view_height=720,
            multiview_fbo_actual='legacy',camera_vp_actual='per_frame',avatar=dict(model_sha256='a'*64)),
        metrics=dict(completed_frames=completed,face_frames=completed,landmarks=478,blendshapes=52),
        npu=dict(mesh_calls=completed,post_calls=completed),source=dict(received_images=completed*2,error=''))


class GlContextRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.rows=[row(10,'old',1,100,20),row(20,'new',1,100,0,'WAITING'),row(23,'new',2,120,2),row(28,'new',2,200,80)]
        self.actions=[dict(action='home',device_elapsed_before_ns=115*10**9,device_elapsed_after_ns=116*10**9),
                      dict(action='resume',device_elapsed_before_ns=120*10**9,device_elapsed_after_ns=121*10**9)]
    def gate(self):return runtime.analyze_gl_context_recovery(self.rows,self.actions,True)
    def test_new_context_same_activity_and_new_input_progress_pass(self):
        value=self.gate();self.assertTrue(value['verified']);self.assertTrue(value['waiting_for_new_frame_observed'])
        self.assertEqual(value['context_before'],1);self.assertEqual(value['context_after'],2)
    def test_default_or_missing_home_never_certifies_recreation(self):
        self.assertFalse(runtime.analyze_gl_context_recovery([],[],False)['verified'])
        self.assertFalse(runtime.analyze_gl_context_recovery(self.rows,[],True)['verified'])
    def test_preserved_context_activity_recreation_and_wrong_frame_generation_fail(self):
        for change in ('same_context','new_activity','wrong_frame','same_input_session','policy'):
            with self.subTest(change=change):
                self.setUp()
                for x in self.rows[1:]:
                    if change=='same_context':x['renderer'].update(context_generation=1,frame_generation=1)
                    if change=='new_activity':x['activity_instance_id']='22222222-2222-2222-2222-222222222222'
                    if change=='wrong_frame':x['renderer']['frame_generation']=1
                    if change=='same_input_session':x.update(runtime_session_id=self.rows[0]['runtime_session_id'],runtime_epoch=1)
                    if change=='policy':x['gl_context_policy']['preserve_on_pause_actual']=True
                self.assertFalse(self.gate()['verified'])
    def test_old_metadata_no_face_stalled_camera_or_npu_cannot_pass(self):
        for target in ('identity','no_face','stale','mesh','usb','landmarks','model','view_count','error'):
            with self.subTest(target=target):
                self.setUp();last=self.rows[-1]
                if target=='identity':last.pop('activity_instance_id')
                elif target=='no_face':last['face_present']=False
                elif target=='stale':last['result_age_ms']=900
                elif target=='mesh':last['npu']['mesh_calls']=2
                elif target=='usb':last['source']['received_images']=4
                elif target=='landmarks':last['metrics']['landmarks']=0
                elif target=='model':last['renderer']['avatar']['model_sha256']='b'*64
                elif target=='view_count':last['renderer']['view_count']=20
                elif target=='error':last['renderer']['error']='lost context'
                self.assertFalse(self.gate()['verified'])
    def test_one_ready_sample_or_invalid_action_clock_is_incomplete(self):
        self.rows=self.rows[:-1];self.assertFalse(self.gate()['verified'])
        self.setUp();self.actions[1]['device_elapsed_before_ns']=None;self.assertFalse(self.gate()['verified'])
    def test_error_state_without_text_between_progress_samples_cannot_pass(self):
        self.rows.insert(3,row(24,'new',2,130,3,'ERROR'))
        self.assertFalse(self.gate()['verified'])
    def test_missing_transient_waiting_is_disclosed_without_inventing_observation(self):
        self.rows.pop(1);value=self.gate();self.assertTrue(value['verified']);self.assertFalse(value['waiting_for_new_frame_observed'])
    def test_incomplete_initial_identity_or_invalid_new_epoch_fails_closed(self):
        for target in ('initial_model','initial_policy','initial_view','invalid_session','invalid_epoch'):
            with self.subTest(target=target):
                self.setUp()
                if target=='initial_model':
                    for x in self.rows:x['renderer']['avatar'].pop('model_sha256')
                if target=='initial_policy':self.rows[0].pop('gl_context_policy')
                if target=='initial_view':
                    for x in self.rows:x.pop('view_count');x['renderer'].pop('view_count')
                if target=='invalid_session':
                    for x in self.rows[1:]:x['runtime_session_id']='broken'
                if target=='invalid_epoch':
                    for x in self.rows[1:]:x['runtime_epoch']=1
                self.assertFalse(self.gate()['verified'])

if __name__=='__main__':unittest.main()
