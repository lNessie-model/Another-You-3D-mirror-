"""Host checks for actual diagnostic helpers; no RKNN/device calls."""
from pathlib import Path
import hashlib
import json
import sys
import tempfile
from types import SimpleNamespace
import unittest
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from diagnose_blendshape_numerics import analyze, errors, distribution
from prepare_blendshape_taps import attach_taps, TAPS


def fixture():
    nodes=[helper.make_node('Sub',['serving_default_input_points:0','zero'],[TAPS[1][1]],name=TAPS[1][1]),
           helper.make_node('ReduceMean',[TAPS[1][1]],[TAPS[2][1]],name=TAPS[2][1],axes=[1,2],keepdims=1),
           helper.make_node('Mul',[TAPS[1][1],TAPS[2][1]],[TAPS[3][1]],name=TAPS[3][1]),
           helper.make_node('Identity',['fixed_final'],[TAPS[4][1]],name='final')]
    constants=[numpy_helper.from_array(np.asarray([0],np.float32),'zero'),
               numpy_helper.from_array(np.linspace(0,1,52,dtype=np.float32),'fixed_final')]
    return helper.make_model(helper.make_graph(nodes,'fixture',[
        helper.make_tensor_value_info('serving_default_input_points:0',TensorProto.FLOAT,[1,146,2])],
        [helper.make_tensor_value_info(TAPS[4][1],TensorProto.FLOAT,[52])],constants),
        opset_imports=[helper.make_opsetid('',11)])


class DiagnosticTests(unittest.TestCase):
    def test_analyze_rejects_unrelated_device_inputs_and_compilation(self):
        # Run the actual analysis entry with actual ORT: valid output/log hashes
        # must not allow evidence from a different input or compiled candidate.
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);model=root/'candidate.onnx'
            model.write_bytes(fixture().SerializeToString())
            validation=root/'validation.json';validation.write_text('{}',encoding='utf-8')
            artifact=root/'face_blendshapes.rknn';artifact.write_bytes(b'test artifact')
            digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
            inputs=np.arange(92*292,dtype=np.float32).reshape(92,1,146,2)/10
            expected=np.tile(np.linspace(0,1,52,dtype=np.float32),(92,1))
            (root/'outputs.f32').write_bytes(expected.tobytes())
            (root/'report.jsonl').write_text(json.dumps(dict(event='finish',status='success',completed_measurements=92))+'\n',encoding='utf-8')
            (root/'native-exit.json').write_text('{"exit_code":0}',encoding='utf-8')
            input_sha=hashlib.sha256(inputs.tobytes()).hexdigest()
            hashes={name:digest(root/name) for name in ('outputs.f32','report.jsonl','face_blendshapes.rknn')}
            hashes['inputs.f32']=input_sha
            compiled=dict(status='compiled',candidate='gamma-conv-mulpow',model_sha256=digest(model),
                validation_sha256=digest(validation),artifact=dict(sha256=digest(artifact),bytes=artifact.stat().st_size),
                steps={name:dict(return_code=0) for name in ('config','load','build','export')})
            compilation=root/'compile-report.json'
            def write_sources(values,record):
                for filename in ('before.sha256','after.sha256'):
                    (root/filename).write_text(''.join(h+'  /device/'+name+'\n' for name,h in values.items()),encoding='utf-8')
                compilation.write_text(json.dumps(record),encoding='utf-8')
            args=SimpleNamespace(device_directory=root,compilation=compilation,validation=validation,candidate='gamma-conv-mulpow')
            write_sources(hashes,compiled)
            report={};analyze(args,report,model,inputs,expected)
            self.assertEqual(report['device_vs_original_tflite']['max_abs'],0)
            for field in ('inputs.f32','face_blendshapes.rknn'):
                with self.subTest(device_hash=field):
                    changed=dict(hashes);changed[field]='0'*64;write_sources(changed,compiled)
                    with self.assertRaises(ValueError):analyze(args,{},model,inputs,expected)
            for field in ('model_sha256','validation_sha256','candidate','status'):
                with self.subTest(compilation=field):
                    changed=dict(compiled);changed[field]='unrelated';write_sources(hashes,changed)
                    with self.assertRaises(ValueError):analyze(args,{},model,inputs,expected)

    def test_taps_only_add_identity_and_outputs(self):
        source=fixture();before=source.SerializeToString();candidate=attach_taps(source)
        self.assertEqual(source.SerializeToString(),before)
        self.assertEqual([n.SerializeToString() for n in source.graph.node],
                         [n.SerializeToString() for n in candidate.graph.node[:-1]])
        self.assertEqual([n.SerializeToString() for n in source.graph.initializer],
                         [n.SerializeToString() for n in candidate.graph.initializer])
        self.assertEqual([o.name for o in candidate.graph.output],[x[1] for x in TAPS])
        self.assertEqual(sum(int(np.prod(x[2])) for x in TAPS),929)
        self.assertEqual(candidate.graph.node[-1].op_type,'Identity')

    def test_taps_actual_ort_echo_and_final_unchanged(self):
        import onnxruntime as ort
        source=fixture();candidate=attach_taps(source)
        opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
        a=ort.InferenceSession(source.SerializeToString(),opts)
        b=ort.InferenceSession(candidate.SerializeToString(),opts)
        value=np.arange(292,dtype=np.float32).reshape(1,146,2)
        expected=a.run(None,{'serving_default_input_points:0':value})[0]
        actual=b.run(None,{'serving_default_input_points:0':value})
        self.assertEqual(actual[0].tobytes(),value.tobytes())
        self.assertEqual(actual[-1].tobytes(),expected.tobytes())
        for result,(_,_,shape) in zip(actual,TAPS):self.assertEqual(result.shape,tuple(shape))

    def test_unknown_front_operator_is_rejected(self):
        source=fixture();source.graph.node[2].op_type='Div'
        with self.assertRaises(ValueError):attach_taps(source)

    def test_collision_is_rejected(self):
        source=fixture();source.graph.node.append(helper.make_node('Identity',['zero'],[TAPS[0][1]],name='collision'))
        with self.assertRaises(ValueError):attach_taps(source)

    def test_missing_tensor_and_wrong_io_rejected(self):
        source=fixture();source.graph.node[1].output[0]='changed'
        with self.assertRaises(ValueError):attach_taps(source)
        source=fixture();source.graph.output[0].name='changed'
        with self.assertRaises(ValueError):attach_taps(source)

    def test_error_statistics_use_full_values_and_float64_difference(self):
        expected=np.asarray([[0,1],[.5,.25]],np.float32)
        actual=np.asarray([[.25,.75],[.5,.5]],np.float32)
        result=errors(actual,expected)
        self.assertEqual(result['max_abs'],.25)
        self.assertEqual(result['mean_abs'],.1875)
        self.assertAlmostEqual(result['rms'],np.sqrt(3*.25**2/4))

    def test_nonfinite_is_not_a_pass_or_finite_error(self):
        a=np.asarray([np.nan,np.inf],np.float32)
        result=errors(a,np.zeros(2,np.float32))
        self.assertEqual(result['nonfinite'],2)
        self.assertNotIn('max_abs',result)
        with self.assertRaises(ValueError):errors(a,np.zeros(3,np.float32))
        with self.assertRaises(ValueError):distribution(a.reshape(1,2))


if __name__=='__main__':unittest.main()
