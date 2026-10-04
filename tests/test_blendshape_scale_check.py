"""Fixed fourth profile: actual layout identities, full offsets and unchanged evidence gates."""
import copy
import json
from pathlib import Path
import sys
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
import test_blendshape_tap_check as fixture
import blendshape_tap_check as t

class ScaleCheckTest(unittest.TestCase):
    def setUp(self):
        self.front=fixture.TapCheckTest();self.front.setUp();self.addCleanup(self.front.tearDown)
        self.b=self.front.bundle;self.e=self.front.evidence;self.rows=self.front.rows
        self.p=t.SCALE_PROFILE
        self.actual=[np.tile(np.linspace(-2,2,n,dtype='<f4'),(92,1)) for n in self.p.elements]
        self.actual[0]=np.tile(np.linspace(3,35,6208,dtype='<f4'),(92,1));self.actual[1]=self.actual[0].copy();self.actual[4][:]=.125
        self.m=self.front.manifest;self.m['kind']=self.p.kind;self.m['contract']=t.fixed_contract(self.p)
        payload={self.p.model:b'model','rknn_tap_probe':b'\x7fELFhelper',
            'compiled-contract.json':json.dumps(self.m['contract']).encode(),
            'inputs.f32':(self.b/'inputs.f32').read_bytes(),'original-tflite52.f32':(self.b/'original-tflite52.f32').read_bytes()}
        payload.update({f'reference-{label}.f32':a.tobytes() for label,a in zip(self.p.labels,self.actual)})
        for old in self.b.iterdir():old.unlink()
        for name,data in payload.items():(self.b/name).write_bytes(data)
        self.m['files']={name:dict(sha256=t.sha(data),bytes=len(data)) for name,data in payload.items()}
        self.save_manifest()
        for r in self.rows:
            if r['event']=='start':r.update(kind=self.p.kind,output_floats_per_case=24884)
            if r['event']=='tensor_attr' and r['kind']=='output':
                i=r['index'];r.update(name=self.p.names[i],n_dims=len(self.p.shapes[i][0]),dims=self.p.shapes[i][0],n_elems=self.p.elements[i],size=self.p.elements[i]*2)
            if r['event']=='tensor_result':
                i=r['index'];n=self.p.elements[i];a=self.actual[i][r['iteration']]
                r.update(label=self.p.labels[i],file=f'output-{self.p.labels[i]}.f32',elements=n,returned_bytes=n*4,
                    offset_floats=-1 if r['phase']=='warmup' else r['iteration']*n,minimum=float(a.min()),maximum=float(a.max()))
        self.save()
    def save_manifest(self):(self.b/'manifest.json').write_text(json.dumps(self.m))
    def save(self):
        for label,a in zip(self.p.labels,self.actual):(self.e/f'output-{label}.f32').write_bytes(a.tobytes())
        (self.e/'report.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in self.rows))
        hashes={name:self.m['files'][name]['sha256'] for name in self.p.device_files};hashes['librknnrt.so']=t.RUNTIME_SHA
        (self.e/'before.sha256').write_text(''.join(f'{h}  /tmp/{n}\n' for n,h in hashes.items()))
        hashes.update({f'output-{label}.f32':t.file_sha(self.e/f'output-{label}.f32') for label in self.p.labels});hashes['report.jsonl']=t.file_sha(self.e/'report.jsonl')
        (self.e/'after.sha256').write_text(''.join(f'{h}  /tmp/{n}\n' for n,h in hashes.items()))
    def selected(self,event,**where):return next(r for r in self.rows if r['event']==event and all(r.get(k)==v for k,v in where.items()))
    def test_complete_24884_only_diagnostic_and_no_false_echo(self):
        r=t.compare_bundle(self.b,self.e)
        self.assertTrue(r['execution_completed']);self.assertFalse(r['application_eligible']);self.assertFalse(r['performance_evidence'])
        self.assertFalse(r['echo']['available']);self.assertNotIn('bitwise_equal',r['echo']);self.assertNotIn('stem',r['echo']['scope'])
        self.assertEqual([x['metrics']['values'] for x in r['taps']],[92*6208,92*6208,92*6208,92*6208,92*52])
        self.assertTrue(r['final52']['original_tflite']['passed']);self.assertTrue(r['final52']['diagnostic_onnx']['passed'])
        for tap in r['taps'][:4]:self.assertNotIn('passed',tap)
    def test_same_size_identity_and_layout_cannot_be_swapped(self):
        original=copy.deepcopy(self.rows)
        for index,key,value in [(0,'dims',[1,1,97,64]),(3,'dims',[1,64,1,97]),(1,'name','diagnostic_scale_x'),
                (2,'name','diagnostic_scale_restored'),(1,'dims',[1,97,1,1]),(1,'name','diagnostic_scale_restored_extra'),(2,'n_dims',3)]:
            with self.subTest(index=index,key=key):
                self.rows=copy.deepcopy(original);self.selected('tensor_attr',kind='output',index=index)[key]=value;self.save()
                with self.assertRaises(ValueError):t.compare_bundle(self.b,self.e)
    def test_last_case_each_output_offset_and_byte_length_enforced(self):
        original=copy.deepcopy(self.rows)
        for i,n in enumerate((6208,6208,6208,6208,52)):
            for key,value in [('offset_floats',91*n-1),('returned_bytes',n*2),('returned_index',(i+1)%5)]:
                self.rows=copy.deepcopy(original);self.selected('tensor_result',phase='measurement',iteration=91,index=i)[key]=value;self.save()
                with self.assertRaises(ValueError):t.compare_bundle(self.b,self.e)
    def test_no_extension_alias_or_arbitrary_profile(self):
        original=t.fixed_contract(self.p)
        for i in range(5):
            c=copy.deepcopy(original);c['outputs'][i]['allowed_runtime_shapes'].append([1,*self.p.shapes[i][0]])
            with self.assertRaises(ValueError):t.validate_contract(c)
        for kind in (t.KIND,t.STEM_PROFILE.kind,t.LN_PROFILE.kind,'fixed_scale_anywhere'):
            c=copy.deepcopy(original);c['kind']=kind
            with self.assertRaises(ValueError):t.validate_contract(c)
        c=copy.deepcopy(original);c['output_elements_per_case']=7036
        with self.assertRaises(ValueError):t.validate_contract(c)
    def test_final_probability_and_precision_gates_not_intermediate_probability(self):
        self.actual[4][10,4]=.99
        self.selected('tensor_result',phase='measurement',iteration=10,index=4)['maximum']=float(self.actual[4][10].max());self.save()
        r=t.compare_bundle(self.b,self.e)
        self.assertFalse(r['final52']['original_tflite']['passed']);self.assertFalse(r['final52']['diagnostic_onnx']['passed'])
        self.assertEqual(r['thresholds'],dict(max_absolute_error=.01,global_mean_absolute_error=.002,range_tolerance=1e-5))
    def test_independent_process_exit_and_log_hash_still_required(self):
        (self.e/'native-exit.json').write_text('{"exit_code":16}')
        with self.assertRaisesRegex(ValueError,'process'):t.compare_bundle(self.b,self.e)
        (self.e/'native-exit.json').write_text('{"exit_code":0}')
        with (self.e/'report.jsonl').open('a') as f:f.write('\n')
        with self.assertRaisesRegex(ValueError,'report'):t.compare_bundle(self.b,self.e)
    def test_nonfinite_final_fails_and_nonfinite_intermediate_is_reported(self):
        for index in (0,4):
            self.actual[index][7,9]=np.nan;r=self.selected('tensor_result',phase='measurement',iteration=7,index=index)
            a=self.actual[index][7];r.update(nonfinite=1,minimum=float(np.nanmin(a)),maximum=float(np.nanmax(a)))
        self.save();r=t.compare_bundle(self.b,self.e)
        self.assertEqual(r['taps'][0]['metrics']['nonfinite_values'],1);self.assertFalse(r['final52']['original_tflite']['passed'])
        json.dumps(r,allow_nan=False)
    def test_prepare_requires_exact_scale_helper_profile_and_binds_provenance(self):
        for label,a in zip(t.LABELS,self.front.arrays):(self.b/f'reference-{label}.f32').write_bytes(a.tobytes())
        args=self.front.preparation('scale-prepare');source,compilation,log,validation,model,helper,bundle=args
        old=json.loads((source/'diagnostic.json').read_text());old['kind']=self.p.kind;old['output_elements_per_case']=24884
        (source/'front-taps.onnx').rename(source/'scale-taps.onnx')
        c=t.fixed_contract(self.p);c['acceptance']=dict(final52=t.THRESHOLDS,model_acceptance=False,intermediate_metrics_only=True)
        c['input'].update(file='inputs.f32',sha256=t.file_sha(source/'inputs.f32'))
        c['original_tflite52']=dict(file='original-tflite52.f32',sha256=t.file_sha(source/'original-tflite52.f32'))
        for i,label in enumerate(self.p.labels):
            f=source/f'reference-{label}.f32';f.write_bytes(self.actual[i].tobytes());c['outputs'][i].update(file=f.name,sha256=t.file_sha(f),bytes=f.stat().st_size,shape=self.p.shapes[i][0])
        old['outputs']=copy.deepcopy(c['outputs']);(source/'diagnostic.json').write_text(json.dumps(old))
        comp=json.loads(compilation.read_text());comp['manifest_sha256']=t.file_sha(source/'diagnostic.json');compilation.write_text(json.dumps(comp))
        c.update(model_sha256=t.file_sha(model),model_bytes=model.stat().st_size,prepare_report_sha256=t.file_sha(source/'diagnostic.json'),compile_report_sha256=t.file_sha(compilation),compiler_host_log_sha256=t.file_sha(log))
        (source/'compiled-contract.json').write_text(json.dumps(c))
        for wrong in ('front','stem','ln'):
            (helper.parent/'build.json').write_text(json.dumps(dict(profile=wrong,helper_sha256=t.file_sha(helper),bytes=helper.stat().st_size)))
            with self.assertRaises(ValueError):t.prepare_bundle(*args)
            self.assertFalse(bundle.exists())
        (helper.parent/'build.json').write_text(json.dumps(dict(profile='scale',helper_sha256=t.file_sha(helper),bytes=helper.stat().st_size)))
        result=t.prepare_bundle(*args);self.assertEqual(result['kind'],self.p.kind);self.assertIn('scale-taps.rknn',result['files'])
        self.assertIn(str((helper.parent/'build.json').resolve()),result['provenance'])

if __name__=='__main__':unittest.main()
