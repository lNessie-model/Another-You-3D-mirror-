"""Actual fixed-eight rewrite, independent copy formulas and full-graph guards."""
import copy,sys,unittest
from pathlib import Path
import numpy as np
import onnx
from onnx import helper,numpy_helper,TensorProto
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from experiment_all_ln_negmean_copy import (PREFIXES,enumerate_branches,rewrite_all_negative_mean,
    verify_all_rewrites,restore_original,replication_nodes,attach_variant_taps,SOURCE_SHA)
from compile_blendshape_loweropt import sha256
SOURCE=Path(__file__).resolve().parents[2]/'output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/gamma-conv-mulpow.onnx'

class AllCopyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert sha256(SOURCE)==SOURCE_SHA
        cls.original=onnx.load(str(SOURCE))
    def test_exact_eight_enumeration(self):
        rows=enumerate_branches(self.original)
        self.assertEqual([r['prefix'] for r in rows],list(PREFIXES))
        for row in rows:
            self.assertEqual(row['mean_axes'],[1]);self.assertEqual(row['keepdims'],1)
            self.assertEqual(row['negative_shape'],[1,1,97,1]);self.assertEqual(row['scale_shape'],[1,1,97,64])
            self.assertEqual(row['gamma_shape'],[64,1,1,1]);self.assertEqual(row['restore_perm'],[0,3,2,1])
    def test_restore_all_bytes_and_first_branch_same_verified_candidate(self):
        from experiment_first_ln_negmean_copy import replication_nodes as first_nodes
        original=self.original.SerializeToString();m=rewrite_all_negative_mean(self.original)
        self.assertEqual(original,self.original.SerializeToString());verify_all_rewrites(self.original,m)
        self.assertEqual(restore_original(m).SerializeToString(),original)
        self.assertEqual(len(m.graph.node),len(self.original.graph.node)+16)
        self.assertEqual(len(m.graph.initializer),len(self.original.graph.initializer)+8)
        ns,w=replication_nodes(0);old,ow=first_nodes()
        self.assertEqual([n.SerializeToString() for n in ns],[n.SerializeToString() for n in old]);self.assertEqual(w.SerializeToString(),ow.SerializeToString())
    def test_all_actual_copy_nodes_match_independent_formula(self):
        import onnxruntime as ort
        opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
        x=np.linspace(-2,2,97,dtype='f4').reshape(1,1,97,1);x.flat[0]=-0.0;x.flat[1]=0.0
        for i in range(8):
            ns,w=replication_nodes(i);input_name=ns[0].input[0];output=ns[1].output[0]
            m=helper.make_model(helper.make_graph(ns,'copy',[helper.make_tensor_value_info(input_name,TensorProto.FLOAT,[1,1,97,1])],
                [helper.make_tensor_value_info(output,TensorProto.FLOAT,[1,1,97,64])],[w]),opset_imports=[helper.make_opsetid('',11)])
            session=ort.InferenceSession(m.SerializeToString(),opts)
            np.testing.assert_array_equal(session.run(None,{input_name:x})[0],np.repeat(x,64,axis=3))
            with self.assertRaises(Exception):session.run(None,{input_name:x.reshape(1,97,1,1)})
    def test_actual_full_final_with_and_without_observation(self):
        import onnxruntime as ort
        opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
        m=rewrite_all_negative_mean(self.original)
        sessions=[ort.InferenceSession(v.SerializeToString(),opts) for v in [self.original,m,attach_variant_taps(m)]]
        x=np.random.RandomState(138).uniform(5,255,(1,146,2)).astype('f4');feed={'serving_default_input_points:0':x}
        ref=sessions[0].run(None,feed)[0].tobytes()
        self.assertEqual(sessions[1].run(None,feed)[0].tobytes(),ref)
        self.assertEqual(sessions[2].run(None,feed)[-1].tobytes(),ref)
    def test_each_branch_wrong_axis_wiring_gamma_or_restore_rejected(self):
        for prefix in PREFIXES:
            for mode in ['axis','order','gamma','perm']:
                m=copy.deepcopy(self.original);nodes={o:n for n in m.graph.node for o in n.output}
                if mode=='axis':next(a for a in nodes[prefix+'moments/mean'].attribute if a.name=='axes').ints[0]=3
                elif mode=='order':
                    n=nodes[prefix+'batchnorm/mul_2'];old=list(n.input);del n.input[:];n.input.extend(reversed(old))
                elif mode=='gamma':
                    key=nodes[prefix+'batchnorm/mul/gamma_conv:0'].input[1];c=next(c for c in m.graph.initializer if c.name==key)
                    a=numpy_helper.to_array(c).copy();a.flat[0]+=1;c.CopyFrom(numpy_helper.from_array(a,c.name))
                else:next(a for a in nodes[prefix+'batchnorm/mul'].attribute if a.name=='perm').ints[1]=2
                with self.subTest(prefix=prefix,mode=mode):
                    with self.assertRaises(ValueError):rewrite_all_negative_mean(m)
    def test_restore_rejects_extra_change_missing_replication_or_nonunit_weights(self):
        for mode in ['unrelated','missing','weight','order']:
            m=rewrite_all_negative_mean(self.original)
            if mode=='unrelated':m.graph.node[0].name+='changed'
            elif mode=='missing':del m.graph.node[next(i for i,n in enumerate(m.graph.node) if n.name==replication_nodes(7)[0][0].name)]
            elif mode=='weight':
                c=m.graph.initializer[-1];a=numpy_helper.to_array(c).copy();a.flat[2]=2;c.CopyFrom(numpy_helper.from_array(a,c.name))
            else:m.graph.initializer[-1].CopyFrom(m.graph.initializer[-2])
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError):verify_all_rewrites(self.original,m)
    def test_duplicate_rewrite_and_unknown_branch_rejected(self):
        with self.assertRaises(ValueError):rewrite_all_negative_mean(rewrite_all_negative_mean(self.original))
        m=copy.deepcopy(self.original);m.graph.node.append(helper.make_node('Mul',[PREFIXES[0]+'batchnorm/Neg',PREFIXES[0]+'batchnorm/mul'],
            ['extra/layer_norm1/batchnorm/mul_2'],name='extra/layer_norm1/batchnorm/mul_2'))
        with self.assertRaises(ValueError):rewrite_all_negative_mean(m)
    def test_invalid_replication_index_rejected(self):
        for value in [-1,8,True,1.5]:
            with self.assertRaises(ValueError):replication_nodes(value)

if __name__=='__main__':unittest.main()
