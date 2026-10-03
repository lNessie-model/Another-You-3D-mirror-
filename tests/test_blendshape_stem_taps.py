"""Actual fixed stem instrumentation and ORT checks; no RKNN or device calls."""
from pathlib import Path
import sys
import unittest
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from prepare_blendshape_stem_taps import attach_stem_taps, TAPS, SOURCE_TAPS, TOTAL_ELEMENTS


def fixture():
    names=[t[1] for t in SOURCE_TAPS]
    constants=[numpy_helper.from_array(v,k) for k,v in [
        ('one',np.asarray(1,np.float32)),('half',np.asarray(.5,np.float32)),
        ('shape',np.asarray([1,146,1,2],np.int64)),
        ('stem_weights',np.ones((96,146,1,1),np.float32)/146),
        ('embed_weights',np.tile(np.asarray([1,0],np.float32),(64,1)).reshape(64,2,1,1)),
        ('extra',np.zeros((1,64,1,1),np.float32)),
        ('final_values',np.linspace(0,1,52,dtype=np.float32))]]
    nodes=[helper.make_node('Mul',['serving_default_input_points:0','one'],[names[0]],name=names[0]),
        helper.make_node('Mul',[names[0],'half'],['scaled'],name='scaled'),
        helper.make_node('Reshape',['scaled','shape'],[names[1]],name=names[1]),
        helper.make_node('Conv',[names[1],'stem_weights'],[names[2]],name=names[2]),
        helper.make_node('Transpose',[names[2]],['transposed'],name='transposed',perm=[0,3,2,1]),
        helper.make_node('Conv',['transposed','embed_weights'],['embedded'],name='embedded'),
        helper.make_node('Concat',['extra','embedded'],[names[3]],name=names[3],axis=3),
        helper.make_node('Identity',['final_values'],[names[4]],name='final')]
    return helper.make_model(helper.make_graph(nodes,'test_stem',[
        helper.make_tensor_value_info('serving_default_input_points:0',TensorProto.FLOAT,[1,146,2])],
        [helper.make_tensor_value_info(names[-1],TensorProto.FLOAT,[52])],constants),
        opset_imports=[helper.make_opsetid('',11)])


class StemTests(unittest.TestCase):
    def test_observation_names_fit_actual_compiler_output_limit(self):
        self.assertTrue(all(len(name.encode('utf-8'))<128 for _,name,_ in TAPS),
                        'Long output names caused a real RKNN simulator KeyError after truncation')

    def test_graph_only_adds_two_observation_identities_and_output_metadata(self):
        original=fixture();before=original.SerializeToString();result=attach_stem_taps(original)
        self.assertEqual(original.SerializeToString(),before)
        self.assertEqual([n.SerializeToString() for n in original.graph.node],
                         [n.SerializeToString() for n in result.graph.node[:-2]])
        self.assertEqual([n.op_type for n in result.graph.node[-2:]],['Identity','Identity'])
        self.assertEqual([n.input[0] for n in result.graph.node[-2:]],[SOURCE_TAPS[i][1] for i in (1,2)])
        self.assertEqual([n.SerializeToString() for n in original.graph.initializer],
                         [n.SerializeToString() for n in result.graph.initializer])
        self.assertEqual([o.name for o in result.graph.output],[t[1] for t in TAPS])
        self.assertEqual(TOTAL_ELEMENTS,7036)
        self.assertTrue(all(len(name.encode('utf-8'))<256 for _,name,_ in TAPS))

    def test_actual_ort_keeps_final_and_taps_correct_stem_axes(self):
        import onnxruntime as ort
        original=fixture();result=attach_stem_taps(original)
        options=ort.SessionOptions();options.intra_op_num_threads=options.inter_op_num_threads=1
        a=ort.InferenceSession(original.SerializeToString(),options)
        b=ort.InferenceSession(result.SerializeToString(),options)
        value=np.arange(292,dtype=np.float32).reshape(1,146,2)
        baseline=a.run(None,{'serving_default_input_points:0':value})[0]
        actual=b.run(None,{'serving_default_input_points:0':value})
        for data,(_,_,shape) in zip(actual,TAPS):self.assertEqual(data.shape,tuple(shape))
        self.assertEqual(actual[-1].tobytes(),baseline.tobytes())
        self.assertEqual(actual[0].tobytes(),value.tobytes())
        np.testing.assert_array_equal(actual[1],(value*.5).reshape(1,146,1,2))
        np.testing.assert_allclose(actual[2][0,:,0,:],np.tile(value.mean(axis=1)[0]/2,(96,1)),rtol=1e-6)
        np.testing.assert_array_equal(actual[3][0,:,0,0],np.zeros(64,np.float32))
        np.testing.assert_allclose(actual[3][0,:,0,1:],np.full((64,96),72.5,np.float32),rtol=1e-6)

    def test_unknown_missing_or_duplicate_producer_rejected(self):
        for mode in ('wrong_op','missing','duplicate'):
            with self.subTest(mode=mode):
                model=fixture()
                if mode=='wrong_op':model.graph.node[3].op_type='Add'
                if mode=='missing':model.graph.node[3].output[0]='other'
                if mode=='duplicate':model.graph.node.append(helper.make_node('Identity',['one'],[TAPS[0][1]],name='duplicate'))
                with self.assertRaises(ValueError):attach_stem_taps(model)

    def test_wrong_input_and_final_shape_rejected(self):
        for which in ('input','output'):
            with self.subTest(which=which):
                model=fixture()
                getattr(model.graph,which)[0].type.tensor_type.shape.dim[-1].dim_value=7
                with self.assertRaises(ValueError):attach_stem_taps(model)

    def test_observation_alias_collision_rejected(self):
        model=fixture()
        model.graph.node.append(helper.make_node('Identity',['one'],[TAPS[1][1]],name='existing_alias'))
        with self.assertRaises(ValueError):attach_stem_taps(model)


if __name__=='__main__':unittest.main()
