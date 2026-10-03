"""Frozen normalized input/ref contract and failure-safe host-only backend calls."""
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from experiment_normalized_suffix import prepare, validate_prepared, run_backend, INPUT, MODEL_SHA, TOOLKIT_API_SHA

ROOT = Path(__file__).resolve().parents[2] / 'output/mirror-program/20261003'
BOUNDARY = ROOT/'blendshape-numerical-diagnosis/normalized-boundary-host-v1'
VALIDATION = ROOT/'blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json'


class Backend:
    def __init__(self, expected, fail=None, release_fail=False):
        self.expected=expected; self.calls=[]; self.i=0; self.fail=fail; self.release_fail=release_fail
    def stage(self, name, kwargs):
        self.calls.append((name, kwargs)); return -77 if self.fail==name else 0
    def config(self, **kwargs): return self.stage('config', kwargs)
    def load_onnx(self, **kwargs): return self.stage('load', kwargs)
    def build(self, **kwargs): return self.stage('build', kwargs)
    def export_rknn(self, path):
        rc=self.stage('export', dict(path=path))
        if rc==0 and self.fail!='empty_export': Path(path).write_bytes(b'test-only-artifact')
        return rc
    def init_runtime(self, **kwargs): return self.stage('simulator_init', kwargs)
    def inference(self, **kwargs):
        self.calls.append(('inference', copy.deepcopy(kwargs)))
        result=self.expected[self.i].copy(); self.i+=1; return [result]
    def release(self):
        self.calls.append(('release', {}))
        if self.release_fail: raise RuntimeError('injected release failure')


class SuffixTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(); cls.prepared=Path(cls.temp.name)/'prepared'
        cls.report=prepare(BOUNDARY/'diagnostic.json', VALIDATION, cls.prepared)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def copied_manifest(self, directory):
        dest=directory/'prepared';shutil.copytree(self.prepared,dest);return dest/'diagnostic.json'
    def test_real_prepare_fp32_chain_and_fixed_model(self):
        report,model,inputs,expected=validate_prepared(self.prepared/'diagnostic.json')
        self.assertEqual(report['model_sha256'],MODEL_SHA)
        self.assertEqual(report['input_name'],INPUT)
        self.assertEqual(inputs.shape,(92,1,146,2));self.assertEqual(expected.shape,(92,52))
        self.assertTrue(report['full_chain_byte_identity']);self.assertLessEqual(report['original_tflite_max_abs'],1e-5)
        self.assertEqual((self.prepared/'inputs.f32').read_bytes(),(BOUNDARY/'normalized_fp32.f32').read_bytes())
        self.assertNotEqual((self.prepared/'inputs.f32').read_bytes(),(BOUNDARY/'normalized_half_roundtrip.f32').read_bytes())
    def test_half_input_wrong_order_modified_model_and_reference_rejected(self):
        for filename,mode in [('inputs.f32','half'),('inputs.f32','reverse'),('candidate.onnx','byte'),('original-tflite52.f32','byte')]:
            with tempfile.TemporaryDirectory() as temp:
                manifest=self.copied_manifest(Path(temp));p=manifest.parent/filename
                if mode=='half': p.write_bytes((BOUNDARY/'normalized_half_roundtrip.f32').read_bytes())
                elif mode=='reverse': p.write_bytes(np.fromfile(p,dtype='<f4').reshape(92,292)[::-1].copy().tobytes())
                else:
                    data=bytearray(p.read_bytes());data[-1]^=1;p.write_bytes(data)
                with self.subTest(filename=filename,mode=mode):
                    with self.assertRaises(ValueError): validate_prepared(manifest)
    def test_original_reference_gate_and_contract_not_relaxed(self):
        for key,value in [('atol',.01),('rtol',.001),('input_name','serving_default_input_points:0'),('input_shape',[1,2,146]),('full_chain_byte_identity',False),('original_tflite_max_abs',.02)]:
            with tempfile.TemporaryDirectory() as temp:
                manifest=self.copied_manifest(Path(temp));d=json.loads(manifest.read_text());d[key]=value;manifest.write_text(json.dumps(d))
                with self.subTest(key=key):
                    with self.assertRaises(ValueError):validate_prepared(manifest)
    def call_backend(self,mode,backend,output):
        with patch('experiment_normalized_suffix._toolkit',return_value=(lambda **kwargs:backend,
                   {'distribution_version':'1.3.0-11912b58','api_sha256':TOOLKIT_API_SHA,'test_boundary_only':True})):
            return run_backend(mode,self.prepared/'diagnostic.json',output)
    def test_simulation_uses_exact_normalized_f32_pass0_and_target_none(self):
        expected=np.fromfile(self.prepared/'reference-fp32-final52.f32',dtype='<f4').reshape(92,52)
        backend=Backend(expected)
        with tempfile.TemporaryDirectory() as temp:
            report=self.call_backend('simulate',backend,Path(temp)/'sim')
        self.assertEqual(report['status'],'completed');self.assertFalse(report['application_eligible'])
        self.assertEqual(next(k for n,k in backend.calls if n=='simulator_init'),dict(target=None))
        rows=[k for n,k in backend.calls if n=='inference'];self.assertEqual(len(rows),92)
        inputs=np.fromfile(self.prepared/'inputs.f32',dtype='<f4').reshape(92,1,146,2)
        for i,k in enumerate(rows):
            self.assertEqual(k['inputs_pass_through'],[0]);self.assertEqual(k['inputs'][0].dtype,np.float32)
            self.assertEqual(k['inputs'][0].tobytes(),inputs[i].tobytes())
        self.assertEqual(backend.calls[-1][0],'release')
    def test_backend_failure_and_cleanup_preserve_both_errors(self):
        for stage in ('config','load','build','export','simulator_init'):
            backend=Backend(None,fail=stage,release_fail=True)
            with tempfile.TemporaryDirectory() as temp:
                report=self.call_backend('simulate' if stage=='simulator_init' else 'compile',backend,Path(temp)/'run')
            self.assertEqual(report['status'],'error');self.assertIn(stage,report['error'])
            self.assertIn('release failure',report['cleanup_error']);self.assertEqual(backend.calls[-1][0],'release')
    def test_empty_export_and_release_failure_never_compiled(self):
        for mode in ('empty_export','cleanup'):
            backend=Backend(None,fail='empty_export' if mode=='empty_export' else None,release_fail=mode=='cleanup')
            with tempfile.TemporaryDirectory() as temp:
                report=self.call_backend('compile',backend,Path(temp)/'run')
            self.assertEqual(report['status'],'error')
    def test_existing_output_and_unknown_mode_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(FileExistsError): run_backend('compile',self.prepared/'diagnostic.json',Path(temp))
            with self.assertRaises(ValueError): run_backend('device',self.prepared/'diagnostic.json',Path(temp)/'bad')


if __name__=='__main__':unittest.main()
