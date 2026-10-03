"""A second fixed profile, including large-tensor offsets and cross-profile rejection."""
import copy
import json
from pathlib import Path
import sys
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
from test_blendshape_tap_check import TapCheckTest
import blendshape_tap_check as t

class StemCheckTest(unittest.TestCase):
    def setUp(self):
        self.front=TapCheckTest();self.front.setUp()
        self.b=self.front.bundle;self.e=self.front.evidence;self.rows=self.front.rows
        self.p=t.STEM_PROFILE
        self.actual=[np.tile(np.linspace(-.25,.25,n,dtype='<f4'),(92,1)) for n in self.p.elements]
        self.actual[4][:]=.125
        m=self.front.manifest;m['kind']=self.p.kind;m['contract']=t.fixed_contract(self.p)
        payload={'stem-taps.rknn':b'model','rknn_tap_probe':b'\x7fELFhelper',
            'compiled-contract.json':json.dumps(m['contract']).encode(),
            'inputs.f32':(self.b/'inputs.f32').read_bytes(),'original-tflite52.f32':(self.b/'original-tflite52.f32').read_bytes()}
        payload.update({f'reference-{label}.f32':a.tobytes() for label,a in zip(self.p.labels,self.actual)})
        for old in self.b.iterdir():old.unlink()
        for name,data in payload.items():(self.b/name).write_bytes(data)
        m['files']={name:dict(sha256=t.sha(data),bytes=len(data)) for name,data in payload.items()};self.m=m
        self.save_manifest()
        for r in self.rows:
            if r['event']=='start':r.update(kind=self.p.kind,output_floats_per_case=7036)
            if r['event']=='tensor_attr' and r['kind']=='output':
                i=r['index'];r.update(name=self.p.names[i],n_dims=len(self.p.shapes[i][0]),dims=self.p.shapes[i][0],n_elems=self.p.elements[i],size=self.p.elements[i]*2)
            if r['event']=='tensor_result':
                i=r['index'];n=self.p.elements[i];a=self.actual[i][r['iteration']]
                r.update(label=self.p.labels[i],file=f'output-{self.p.labels[i]}.f32',elements=n,returned_bytes=n*4,
                    offset_floats=-1 if r['phase']=='warmup' else r['iteration']*n,minimum=float(a.min()),maximum=float(a.max()))
        self.save()
    def tearDown(self):self.front.tearDown()
    def save_manifest(self):(self.b/'manifest.json').write_text(json.dumps(self.m))
    def save(self):
        for label,a in zip(self.p.labels,self.actual):(self.e/f'output-{label}.f32').write_bytes(a.tobytes())
        (self.e/'report.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in self.rows))
        hashes={name:self.m['files'][name]['sha256'] for name in self.p.device_files};hashes['librknnrt.so']=t.RUNTIME_SHA
        (self.e/'before.sha256').write_text(''.join(f'{h}  /tmp/{n}\n' for n,h in hashes.items()))
        hashes.update({f'output-{label}.f32':t.file_sha(self.e/f'output-{label}.f32') for label in self.p.labels});hashes['report.jsonl']=t.file_sha(self.e/'report.jsonl')
        (self.e/'after.sha256').write_text(''.join(f'{h}  /tmp/{n}\n' for n,h in hashes.items()))
    def selected(self,event,**where):return next(r for r in self.rows if r['event']==event and all(r.get(k)==v for k,v in where.items()))
    def test_fixed_7036_complete_without_false_echo_claim(self):
        r=t.compare_bundle(self.b,self.e);self.assertTrue(r['execution_completed']);self.assertFalse(r['application_eligible'])
        self.assertFalse(r['echo']['available']);self.assertNotIn('bitwise_equal',r['echo'])
        self.assertTrue(r['final52']['original_tflite']['passed']);self.assertEqual(len(r['taps'][3]['cases']),92)
        self.assertEqual(r['taps'][3]['metrics']['values'],92*6208)
    def test_short_name_exact_no_v1_prefix_or_alias(self):
        for name in ['diagnostic_stem_input_bad','model_1/GhumMarkerPoserMlpMixerGeneral/conv2d/BiasAdd;model_1/GhumMarkerPoserMlpMixerGeneral/conv2d/Conv2D;model_1/GhumMark__0']:
            self.selected('tensor_attr',kind='output',index=1)['name']=name;self.save()
            with self.assertRaises(ValueError):t.compare_bundle(self.b,self.e)
    def test_stem_rank_and_axis_order_are_not_interchangeable(self):
        for dims in [[1,146,2,1],[1,2,1,146],[146,1,2]]:
            r=self.selected('tensor_attr',kind='output',index=1);r.update(dims=dims,n_dims=len(dims));self.save()
            with self.assertRaises(ValueError):t.compare_bundle(self.b,self.e)
    def test_large_tensor_offset_size_and_index_errors_reject(self):
        original=copy.deepcopy(self.rows)
        for key,value in [('offset_floats',91*292),('returned_bytes',6208*2),('returned_index',2),('elements',929)]:
            self.rows=copy.deepcopy(original);self.selected('tensor_result',phase='measurement',iteration=91,index=3)[key]=value;self.save()
            with self.assertRaises(ValueError):t.compare_bundle(self.b,self.e)
    def test_front_profile_with_stem_evidence_rejects(self):
        self.m['kind']=t.KIND;self.m['contract']=t.fixed_contract();self.save_manifest()
        with self.assertRaises(ValueError):t.compare_bundle(self.b,self.e)
    def test_arbitrary_third_profile_or_extended_whitelist_rejects(self):
        for kind in ['fixed_stem_five_tap_diagnostic_v3','arbitrary']:
            c=t.fixed_contract(self.p);c['kind']=kind
            with self.assertRaises(ValueError):t.validate_contract(c)
        c=t.fixed_contract(self.p);c['outputs'][1]['allowed_runtime_names'].append('v1alias')
        with self.assertRaises(ValueError):t.validate_contract(c)
    def test_probability_gate_still_applies_only_to_final(self):
        self.actual[4][10,4]=.99;rr=self.selected('tensor_result',phase='measurement',iteration=10,index=4);rr['maximum']=float(self.actual[4][10].max());self.save()
        r=t.compare_bundle(self.b,self.e);self.assertFalse(r['final52']['original_tflite']['passed']);self.assertNotIn('passed',r['taps'][0])
    def test_prepare_stem_provenance_and_helper_profile(self):
        # Restore source-only front fixture references used to construct the provenance fixture.
        for label,a in zip(t.LABELS,self.front.arrays):(self.b/f'reference-{label}.f32').write_bytes(a.tobytes())
        args=self.front.preparation('stem-prepare');source,compilation,log,validation,model,helper,bundle=args
        old=json.loads((source/'diagnostic.json').read_text());old['kind']=self.p.kind;old['output_elements_per_case']=7036
        (source/'front-taps.onnx').rename(source/'stem-taps.onnx')
        c=t.fixed_contract(self.p);c['acceptance']=dict(final52=t.THRESHOLDS,model_acceptance=False,intermediate_metrics_only=True)
        c['input'].update(file='inputs.f32',sha256=t.file_sha(source/'inputs.f32'))
        c['original_tflite52']=dict(file='original-tflite52.f32',sha256=t.file_sha(source/'original-tflite52.f32'))
        for i,label in enumerate(self.p.labels):
            f=source/f'reference-{label}.f32';f.write_bytes(self.actual[i].tobytes());c['outputs'][i].update(file=f.name,sha256=t.file_sha(f),bytes=f.stat().st_size,shape=self.p.shapes[i][0])
        old['outputs']=copy.deepcopy(c['outputs']);(source/'diagnostic.json').write_text(json.dumps(old))
        comp=json.loads(compilation.read_text());comp['manifest_sha256']=t.file_sha(source/'diagnostic.json');compilation.write_text(json.dumps(comp))
        c.update(model_sha256=t.file_sha(model),model_bytes=model.stat().st_size,prepare_report_sha256=t.file_sha(source/'diagnostic.json'),compile_report_sha256=t.file_sha(compilation),compiler_host_log_sha256=t.file_sha(log))
        (source/'compiled-contract.json').write_text(json.dumps(c))
        (helper.parent/'build.json').write_text(json.dumps(dict(profile='stem',helper_sha256=t.file_sha(helper),bytes=helper.stat().st_size)))
        result=t.prepare_bundle(*args);self.assertEqual(result['kind'],self.p.kind);self.assertIn('stem-taps.rknn',result['files'])
        (helper.parent/'build.json').write_text(json.dumps(dict(profile='front',helper_sha256=t.file_sha(helper),bytes=helper.stat().st_size)))
        args[-1]=bundle.parent/'rejected'
        with self.assertRaises(ValueError):t.prepare_bundle(*args)

if __name__=='__main__':unittest.main()
