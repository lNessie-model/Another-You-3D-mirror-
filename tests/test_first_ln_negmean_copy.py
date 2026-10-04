"""First-LN-only explicit replication: actual rewrite and independent ORT formula."""
import copy,os,sys,unittest
from pathlib import Path
import numpy as np
import onnx
from onnx import helper,numpy_helper,TensorProto
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from experiment_first_ln_negmean_copy import (rewrite_first_negative_mean,verify_single_rewrite,
    replication_nodes,attach_variant_taps,EXPAND_INPUT,EXPAND_OUTPUT,EXPAND_WEIGHT,P,SOURCE_SHA)
from compile_blendshape_loweropt import sha256
SOURCE=Path(__file__).resolve().parents[2]/'output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/gamma-conv-mulpow.onnx'

class CopyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path=Path(os.environ.get('BLENDSHAPE_SOURCE',str(SOURCE)))
        if sha256(path)!=SOURCE_SHA:raise ValueError('Expected frozen original graph')
        cls.original=onnx.load(str(path))
    def test_only_target_input_two_nodes_and_one_constant_change(self):
        original=self.original;before=original.SerializeToString();candidate=rewrite_first_negative_mean(original)
        self.assertEqual(before,original.SerializeToString());verify_single_rewrite(original,candidate)
        self.assertEqual(len(candidate.graph.node),len(original.graph.node)+2)
        self.assertEqual(len(candidate.graph.initializer),len(original.graph.initializer)+1)
        target=next(n for n in candidate.graph.node if P+'batchnorm/mul_2' in n.output)
        self.assertEqual(list(target.input),[EXPAND_OUTPUT,P+'batchnorm/mul'])
        weights=numpy_helper.to_array(next(c for c in candidate.graph.initializer if c.name==EXPAND_WEIGHT))
        self.assertEqual(weights.dtype,np.float32);self.assertEqual(weights.shape,(64,1,1,1))
        self.assertEqual(weights.tobytes(),np.ones((64,1,1,1),np.float32).tobytes())
    def test_actual_replication_nodes_match_independent_formula(self):
        import onnxruntime as ort
        nodes,weights=replication_nodes()
        model=helper.make_model(helper.make_graph(nodes,'replicate',
            [helper.make_tensor_value_info(EXPAND_INPUT,TensorProto.FLOAT,[1,1,97,1])],
            [helper.make_tensor_value_info(EXPAND_OUTPUT,TensorProto.FLOAT,[1,1,97,64])],[weights]),
            opset_imports=[helper.make_opsetid('',11)])
        opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
        session=ort.InferenceSession(model.SerializeToString(),opts)
        value=np.linspace(-.05,.05,97,dtype='f4').reshape(1,1,97,1)
        value.flat[0]=np.float32(-0.0);value.flat[1]=np.float32(0.0)
        actual=session.run(None,{EXPAND_INPUT:value})[0]
        # Numerical replication includes zeros. Do not claim universal signed-zero
        # bit preservation by a Conv accumulation; final92 is separately bit-gated.
        np.testing.assert_array_equal(actual,np.repeat(value,64,axis=3))
        with self.assertRaises(Exception):session.run(None,{EXPAND_INPUT:value.reshape(1,1,1,97)})
    def test_real_final_identity_before_and_after_observation(self):
        import onnxruntime as ort
        opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
        candidate=rewrite_first_negative_mean(self.original)
        sessions=[ort.InferenceSession(m.SerializeToString(),opts) for m in [self.original,candidate,attach_variant_taps(candidate)]]
        x=np.random.RandomState(923).uniform(10,250,(1,146,2)).astype('f4');feed={'serving_default_input_points:0':x}
        expected=sessions[0].run(None,feed)[0].tobytes()
        self.assertEqual(sessions[1].run(None,feed)[0].tobytes(),expected)
        self.assertEqual(sessions[2].run(None,feed)[-1].tobytes(),expected)
    def test_wrong_input_shape_statistics_and_target_order_rejected(self):
        for mode in ('input','reshape','target','axis'):
            m=copy.deepcopy(self.original)
            if mode=='input':m.graph.input[0].type.tensor_type.shape.dim[-1].dim_value=3
            elif mode=='reshape':
                c=next(c for c in m.graph.initializer if c.name=='new_shape__333')
                c.CopyFrom(numpy_helper.from_array(np.array([1,97,1,1],dtype='i8'),c.name))
            elif mode=='target':
                n=next(n for n in m.graph.node if P+'batchnorm/mul_2' in n.output)
                n.input[0]=P+'batchnorm/mul'
            else:
                n=next(n for n in m.graph.node if P+'moments/mean' in n.output)
                next(a for a in n.attribute if a.name=='axes').ints[0]=3
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError):rewrite_first_negative_mean(m)
    def test_name_collision_and_double_rewrite_rejected(self):
        candidate=rewrite_first_negative_mean(self.original)
        with self.assertRaises(ValueError):rewrite_first_negative_mean(candidate)
        for name in (EXPAND_OUTPUT,EXPAND_WEIGHT):
            m=copy.deepcopy(self.original)
            m.graph.node.append(helper.make_node('Identity',[EXPAND_INPUT],[name],name='collision'))
            with self.assertRaises(ValueError):rewrite_first_negative_mean(m)
    def test_restore_verifier_catches_unrelated_changes_or_weight_change(self):
        for mode in ('weight','other_node','original_initializer'):
            m=rewrite_first_negative_mean(self.original)
            if mode=='weight':
                c=next(c for c in m.graph.initializer if c.name==EXPAND_WEIGHT)
                v=numpy_helper.to_array(c).copy();v.flat[1]=2;c.CopyFrom(numpy_helper.from_array(v,c.name))
            elif mode=='other_node':m.graph.node[0].name+='changed'
            else:m.graph.initializer[0].name+='changed'
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError):verify_single_rewrite(self.original,m)

if __name__=='__main__':unittest.main()
