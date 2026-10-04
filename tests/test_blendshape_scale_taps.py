"""Actual fixed-graph scale-branch tests. ORT only, no RKNN/device calls."""
import copy
import os
from pathlib import Path
import sys
import unittest
import numpy as np
import onnx
from onnx import helper, numpy_helper
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from prepare_blendshape_scale_taps import attach_scale_taps,TAPS,SOURCE_TAPS,TOTAL_ELEMENTS,SOURCE_SHA,P
from prepare_blendshape_ln_taps import attach_ln_taps
from compile_blendshape_loweropt import sha256
DEFAULT_SOURCE=Path(__file__).resolve().parents[2]/'output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/gamma-conv-mulpow.onnx'

class ScaleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path=Path(os.environ.get('BLENDSHAPE_SOURCE',str(DEFAULT_SOURCE)))
        if sha256(path)!=SOURCE_SHA:raise ValueError('Tests require frozen actual model')
        cls.original=onnx.load(str(path))

    def test_only_four_observation_identities_no_parameter_changes(self):
        original=self.original;before=original.SerializeToString();model=attach_scale_taps(original)
        self.assertEqual(original.SerializeToString(),before)
        self.assertEqual([n.SerializeToString() for n in original.graph.node],[n.SerializeToString() for n in model.graph.node[:-4]])
        self.assertEqual([n.SerializeToString() for n in original.graph.initializer],[n.SerializeToString() for n in model.graph.initializer])
        self.assertEqual([n.op_type for n in model.graph.node[-4:]],['Identity']*4)
        self.assertEqual([n.input[0] for n in model.graph.node[-4:]],[t[1] for t in SOURCE_TAPS[:4]])
        self.assertEqual([v.name for v in model.graph.output],[t[1] for t in TAPS])
        self.assertEqual(TOTAL_ELEMENTS,24884)
        self.assertTrue(all(len(name.encode('utf-8'))<128 for _,name,_ in TAPS))

    def test_real_ort_gamma_axes_two_products_and_original_final(self):
        import onnxruntime as ort
        opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
        original=ort.InferenceSession(self.original.SerializeToString(),opts)
        ln=ort.InferenceSession(attach_ln_taps(self.original).SerializeToString(),opts)
        scale=ort.InferenceSession(attach_scale_taps(self.original).SerializeToString(),opts)
        gamma=numpy_helper.to_array(next(c for c in self.original.graph.initializer if c.name=='const_fold_opt__388'))
        rng=np.random.RandomState(604)
        for x in [rng.uniform(5,240,(1,146,2)).astype('f4'),rng.normal(125,20,(1,146,2)).astype('f4')]:
            feed={'serving_default_input_points:0':x};a=scale.run(None,feed);b=ln.run(None,feed)
            self.assertEqual(a[-1].tobytes(),original.run(None,feed)[0].tobytes())
            for v,(_,_,shape) in zip(a,TAPS):
                self.assertEqual(v.shape,tuple(shape));self.assertTrue(np.isfinite(v).all())
            expected_gamma=b[2].reshape(1,1,97,1)*gamma.reshape(1,64,1,1)
            np.testing.assert_allclose(a[0],expected_gamma,rtol=1e-6,atol=1e-7)
            self.assertEqual(a[1].tobytes(),a[0].transpose(0,3,2,1).copy().tobytes())
            np.testing.assert_allclose(a[2],b[0].transpose(0,2,3,1)*a[1],rtol=1e-6,atol=1e-7)
            np.testing.assert_allclose(a[3],(-b[1]).reshape(1,1,97,1)*a[1],rtol=1e-6,atol=1e-7)
            # Host FP32 identity only. This is not a replacement for a future
            # device Add observation or a relaxation of the final52 gate.
            np.testing.assert_allclose(a[2]+a[3],b[3],rtol=1e-6,atol=1e-7)

    def test_gamma_transpose_and_products_cannot_be_substituted(self):
        for suffix in ('batchnorm/mul/gamma_conv:0','batchnorm/mul','batchnorm/mul_1','batchnorm/mul_2'):
            model=copy.deepcopy(self.original);node=next(n for n in model.graph.node if P+suffix in n.output)
            node.input[0]='unrelated'
            with self.subTest(suffix=suffix):
                with self.assertRaises(ValueError):attach_scale_taps(model)
        model=copy.deepcopy(self.original);node=next(n for n in model.graph.node if P+'batchnorm/mul' in n.output)
        next(a for a in node.attribute if a.name=='perm').ints[1]=2
        with self.assertRaises(ValueError):attach_scale_taps(model)

    def test_same_shape_aliases_still_bind_distinct_source_outputs(self):
        model=attach_scale_taps(self.original)
        self.assertEqual(model.graph.node[-2].input[0],P+'batchnorm/mul_1')
        self.assertEqual(model.graph.node[-1].input[0],P+'batchnorm/mul_2')
        for alias in [t[1] for t in TAPS[:4]]:
            model=copy.deepcopy(self.original)
            model.graph.node.append(helper.make_node('Identity',[SOURCE_TAPS[0][1]],[alias],name='collision'))
            with self.assertRaises(ValueError):attach_scale_taps(model)

    def test_statistics_parameters_and_order_remain_guarded(self):
        for name in ('const_fold_opt__389','const_fold_opt__388'):
            model=copy.deepcopy(self.original);c=next(c for c in model.graph.initializer if c.name==name)
            a=numpy_helper.to_array(c).copy();a.flat[0]=np.nextafter(a.flat[0],np.float32(100));c.CopyFrom(numpy_helper.from_array(a,name))
            with self.assertRaises(ValueError):attach_scale_taps(model)
        model=copy.deepcopy(self.original);node=next(n for n in model.graph.node if P+'moments/mean' in n.output)
        next(a for a in node.attribute if a.name=='axes').ints[0]=3
        with self.assertRaises(ValueError):attach_scale_taps(model)

    def test_duplicate_missing_and_wrong_io_rejected(self):
        for mode in ('duplicate','missing','input','output'):
            model=copy.deepcopy(self.original)
            if mode=='duplicate':model.graph.node.append(helper.make_node('Identity',[SOURCE_TAPS[0][1]],[SOURCE_TAPS[0][1]],name='duplicate'))
            elif mode=='missing':next(n for n in model.graph.node if SOURCE_TAPS[0][1] in n.output).output[0]='wrong'
            else:getattr(model.graph,mode)[0].type.tensor_type.shape.dim[-1].dim_value=7
            with self.assertRaises(ValueError):attach_scale_taps(model)

if __name__=='__main__':unittest.main()
