import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import numpy as np

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'blendshape_device_check.py'
spec = importlib.util.spec_from_file_location('device_check', SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class CheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.inputs = np.arange(92*292, dtype=np.float32).reshape(92,1,146,2) / 100
        self.outputs = np.full((92,52), .5, dtype=np.float32)
        self.fixtures = self.root/'fixtures.npz'
        np.savez(self.fixtures,inputs=self.inputs,outputs=self.outputs)
        self.model=self.root/'candidate.rknn';self.model.write_bytes(b'fake model for host structural tests')
        self.helper=self.root/'probe';self.helper.write_bytes(b'\x7fELF'+b'host fixture only')
        self.validation=self.root/'validation.json'
        cases=[dict(fixture=f'npu-{i}' if i<72 else f'synthetic-{i-72}',input_sha256=mod.sha(self.inputs[i].astype('<f4').tobytes()),reference_sha256=mod.sha(self.outputs[i].astype('<f4').tobytes())) for i in range(92)]
        self.validation.write_text(json.dumps(dict(status='passed',source_sha256=mod.TFLITE_SHA,reference='original TFLite, no rewritten reference',case_count=92,fixture_archive_sha256=mod.file_sha(self.fixtures),cases=cases)))
        self.compilation=self.root/'compile.json'
        self.compilation.write_text(json.dumps(dict(status='compiled',validation_sha256=mod.file_sha(self.validation),artifact=dict(sha256=mod.file_sha(self.model),bytes=self.model.stat().st_size))))
        self.bundle=self.root/'bundle'
        mod.prepare_bundle(self.fixtures,self.validation,self.model,self.compilation,self.helper,self.bundle)
        self.manifest=json.loads((self.bundle/'manifest.json').read_text())
        self.actual=self.root/'actual.f32';self.actual.write_bytes(self.outputs.astype('<f4').tobytes())
        self.log=self.root/'report.jsonl'
        self.rows=[dict(event='start',schema_version=1,cases=92,cycles=1,warmup=5,input_floats=292,output_floats=52,runtime_path='/vendor/lib64/librknnrt.so'),
            dict(event='init',rc=0,model_bytes=self.model.stat().st_size,input_bytes=92*292*4,elapsed_ms=8,flags=0),
            dict(event='sdk',rc=0,api='1.3.0 host fixture',driver='0.7.2'),dict(event='io_count',rc=0,inputs=1,outputs=1),
            dict(event='tensor_attr',kind='input',rc=0,n_elems=292,dims=[1,146,2],fmt=0),
            dict(event='tensor_attr',kind='output',rc=0,n_elems=52,dims=[1,52],fmt=0),
            dict(event='feed_contract',type=0,fmt=0,pass_through=0,want_float=1)]
        for phase,count in [('warmup',5),('measurement',92)]:
            for i in range(count):self.rows.append(dict(event='iteration',phase=phase,iteration=i,fixture=i%92,cycle=0,return_codes=dict(inputs_set=0,run=0,outputs_get=0,outputs_release=0),timing_ms=dict(inputs_set=.1,run=2.,outputs_get=.2,outputs_release=.1,total=2.4),output_bytes=208,nonfinite=0,output_min=.5,output_max=.5,output_offset_floats=-1 if phase=='warmup' else i*52,ok=True))
        self.rows.append(dict(event='finish',status='success',exit_code=0,completed_measurements=92,expected_measurements=92,destroy_rc=0))
        self.before=self.root/'before.sha256';self.after=self.root/'after.sha256'
        self.before.write_text(''.join(f'{entry["sha256"]}  /isolated/{name}\n' for name,entry in self.manifest['files'].items() if name!='references.f32')+f'{mod.RUNTIME_SHA}  /vendor/lib64/librknnrt.so\n')
        self.refresh()
    def tearDown(self):self.temp.cleanup()
    def refresh(self):
        self.log.write_text(''.join(json.dumps(row)+'\n' for row in self.rows))
        self.after.write_text(self.before.read_text()+f'{mod.file_sha(self.actual)}  /isolated/outputs.f32\n'+f'{mod.file_sha(self.log)}  /isolated/report.jsonl\n')
    def check_result(self):
        self.refresh();return mod.compare_bundle(self.bundle,self.log,self.actual,self.before,self.after)
    def test_complete_zero_error(self):
        r=self.check_result();self.assertTrue(r['passed']);self.assertEqual(r['groups']['real']['cases'],72);self.assertEqual(r['groups']['synthetic']['cases'],20);self.assertEqual(len(r['channels']),52)
    def test_recorded_threshold_not_posthoc(self):
        self.assertEqual(self.manifest['thresholds'],dict(max_absolute_error=.01,global_mean_absolute_error=.002,range_tolerance=1e-5))
        values=self.outputs.copy();values[3,17]+=.02;self.actual.write_bytes(values.astype('<f4').tobytes())
        r=self.check_result();self.assertFalse(r['passed']);self.assertGreater(r['channels'][17]['max_absolute_error'],.019)
    def test_nan_rejects(self):
        values=self.outputs.copy();values[0,2]=np.nan;self.actual.write_bytes(values.astype('<f4').tobytes());r=self.check_result();self.assertFalse(r['passed']);self.assertEqual(r['nonfinite_values'],1)
    def test_range_rejects_even_when_small_reference_error(self):
        values=self.outputs.copy();values[0,2]=1.001;self.actual.write_bytes(values.astype('<f4').tobytes());self.assertFalse(self.check_result()['passed'])
    def test_missing_sample_does_not_pass(self):
        self.rows.pop(-2)
        with self.assertRaises(ValueError):self.check_result()
    def test_warmup_release_failure_does_not_pass(self):
        self.rows[7]['return_codes']['outputs_release']=-5
        with self.assertRaises(ValueError):self.check_result()
    def test_reordered_output_identity_does_not_pass(self):
        self.rows[12]['fixture']=1
        with self.assertRaises(ValueError):self.check_result()
    def test_missing_finish_does_not_pass(self):
        self.rows.pop()
        with self.assertRaises(ValueError):self.check_result()
    def test_wrong_device_hash_does_not_pass(self):
        self.before.write_text(self.before.read_text().replace(mod.RUNTIME_SHA,'0'*64))
        with self.assertRaises(ValueError):self.check_result()
    def test_truncated_output_does_not_pass(self):
        self.actual.write_bytes(self.actual.read_bytes()[:-4])
        with self.assertRaises(ValueError):self.check_result()
    def test_no_silent_transpose(self):
        self.rows[4]['dims']=[1,2,146]
        with self.assertRaises(ValueError):self.check_result()
    def test_prepare_rejects_reference_hash_mismatch(self):
        d=json.loads(self.validation.read_text());d['cases'][0]['reference_sha256']='0'*64;self.validation.write_text(json.dumps(d))
        with self.assertRaises(ValueError):mod.prepare_bundle(self.fixtures,self.validation,self.model,self.compilation,self.helper,self.root/'bad')
        self.assertFalse((self.root/'bad').exists())
    def test_prepare_never_overwrites(self):
        with self.assertRaises(FileExistsError):mod.prepare_bundle(self.fixtures,self.validation,self.model,self.compilation,self.helper,self.bundle)
    def test_mean_gate_independent_of_max_gate(self):
        self.actual.write_bytes((self.outputs+.003).astype('<f4').tobytes())
        r=self.check_result();self.assertFalse(r['passed']);self.assertLess(r['max_absolute_error'],.01);self.assertGreater(r['mean_absolute_error'],.002)
    def test_small_finite_error_passes_and_names_match(self):
        self.actual.write_bytes((self.outputs+.001).astype('<f4').tobytes())
        r=self.check_result();self.assertTrue(r['passed']);self.assertEqual(r['channels'][25]['name'],'jawOpen')
        self.assertAlmostEqual(r['groups']['synthetic']['mean_absolute_error'],.001,places=6)
    def test_warmup_range_is_checked(self):
        self.rows[7]['output_max']=1.1
        self.assertFalse(self.check_result()['passed'])
    def test_destroy_failure_rejects(self):
        self.rows[-1]['destroy_rc']=-1
        with self.assertRaises(ValueError):self.check_result()
    def test_unsupported_format_rejects(self):
        self.rows[4]['fmt']=2;self.rows[6]['fmt']=2
        with self.assertRaises(ValueError):self.check_result()
    def test_multiple_cycles_keep_fixture_identity(self):
        cycle=[json.loads(json.dumps(row)) for row in self.rows[12:-1]]
        for row in cycle:
            row['iteration']+=92;row['cycle']=1;row['output_offset_floats']+=92*52
        self.rows[-1:-1]=cycle;self.rows[0]['cycles']=2
        self.rows[-1]['completed_measurements']=184;self.rows[-1]['expected_measurements']=184
        self.actual.write_bytes(np.tile(self.outputs,(2,1)).astype('<f4').tobytes())
        r=self.check_result();self.assertTrue(r['passed']);self.assertEqual(r['groups']['real']['measurements'],144)
    def test_channel_names_match_production_schema(self):
        import re
        schema=(SCRIPT.parents[1]/'app/src/main/java/com/mirror/bench/BlendshapeSchema.java').read_text()
        names=re.findall(r'"([A-Za-z_]+)"',schema.split('private static final String[] NAMES = {',1)[1].split('};',1)[0])
        self.assertEqual(mod.CHANNELS,names)
    def test_old_log_with_other_timing_cannot_match_output(self):
        self.refresh()
        self.log.write_text(self.log.read_text().replace('"run": 2.0','"run": 20.0'))
        with self.assertRaises(ValueError):mod.compare_bundle(self.bundle,self.log,self.actual,self.before,self.after)
    def test_observed_undefined_xy_tensor_is_supported_without_transpose(self):
        self.rows[4].update(fmt=3,n_dims=3,dims=[1,146,2],type=1,size=584)
        self.rows[5].update(fmt=3,n_dims=1,dims=[52],type=1,size=104)
        self.rows[6]['fmt']=3
        self.assertTrue(self.check_result()['passed'])
    def test_undefined_format_only_accepts_exact_observed_shape(self):
        for dims in ([146,2],[146,1,2],[1,146,2,1],[1,1,146,2]):
            with self.subTest(dims=dims):
                self.rows[4].update(fmt=3,dims=dims,n_dims=len(dims));self.rows[6]['fmt']=3
                with self.assertRaises(ValueError):self.check_result()

if __name__=='__main__':unittest.main()
