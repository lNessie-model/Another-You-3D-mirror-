"""Actual ONNX shape rewrite tests; uses existing WSL ONNX, never RKNN or Android."""
import sys
import unittest
from pathlib import Path
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from blendshape_broadcast import explicit_gamma_rank, gamma_conv, reciprocal_div, neg_multiply, sqrt_pow

def fixture():
    nodes, initializers, outputs = [], [], []
    for index in range(8):
        gamma = np.linspace(.5 + index * .01, 1.5 + index * .01, 64, dtype=np.float32)
        initializers.append(numpy_helper.from_array(gamma, "gamma" + str(index)))
        name = "model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/MixerBlock_%d/layer_norm%d/batchnorm/mul" % (index // 2, index % 2 + 1)
        nodes.append(helper.make_node("Mul", ["variance", "gamma" + str(index)], ["out" + str(index)], name=name))
        outputs.append(helper.make_tensor_value_info("out" + str(index), TensorProto.FLOAT, [1, 1, 97, 64]))
    graph = helper.make_graph(nodes, "fixture",
        [helper.make_tensor_value_info("variance", TensorProto.FLOAT, [1, 1, 97, 1])], outputs, initializers)
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", 11)])

class BroadcastTests(unittest.TestCase):
    def test_div_multiply_pow_preserves_all_nine_numerators_and_broadcasts(self):
        from blendshape_broadcast import div_multiply_pow
        import onnxruntime as ort
        prefix = "model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/"
        numerator = "model_1/tf.math.subtract/Sub"
        denominator = "model_1/tf.math.reduce_mean_1/Mean"
        nodes = [helper.make_node("Sub", ["points", "zero"], [numerator], name=numerator),
                 helper.make_node("ReduceMean", ["scale"], [denominator], name=denominator, axes=[1], keepdims=1),
                 helper.make_node("Div", [numerator, denominator], ["normalized"], name="model_1/tf.math.truediv_1/truediv")]
        outputs = [helper.make_tensor_value_info("normalized", TensorProto.FLOAT, [1, 146, 2])]
        constants = [numpy_helper.from_array(np.asarray([0], np.float32), "zero")]
        for i in range(8):
            parent = prefix + "MixerBlock_%d/layer_norm%d/batchnorm/Rsqrt" % (i//2, i%2+1)
            name = parent + "__" + str(43 + 8*i)
            constants += [numpy_helper.from_array(np.asarray([.5], np.float32), parent + "/literal_half"),
                          numpy_helper.from_array(np.asarray([1.], np.float32), name + "/literal_one")]
            nodes.append(helper.make_node("Pow", ["variance", parent+"/literal_half"], [parent], name=parent))
            nodes.append(helper.make_node("Div", [name+"/literal_one", parent], ["out"+str(i)], name=name))
            outputs.append(helper.make_tensor_value_info("out"+str(i), TensorProto.FLOAT, [1, 1, 97, 1]))
        source = helper.make_model(helper.make_graph(nodes, "division", [
            helper.make_tensor_value_info("points", TensorProto.FLOAT, [1,146,2]),
            helper.make_tensor_value_info("scale", TensorProto.FLOAT, [1,146,1]),
            helper.make_tensor_value_info("variance", TensorProto.FLOAT, [1,1,97,1])], outputs, constants),
            opset_imports=[helper.make_opsetid("",11)])
        before = source.SerializeToString()
        candidate, changes = div_multiply_pow(source)
        self.assertEqual(source.SerializeToString(), before)
        self.assertEqual(len(changes),9)
        self.assertEqual(sum(n.op_type=="Div" for n in candidate.graph.node),0)
        self.assertEqual(sum(n.op_type=="Pow" for n in candidate.graph.node),17)
        self.assertEqual([v.SerializeToString() for v in source.graph.initializer],
                         [v.SerializeToString() for v in candidate.graph.initializer[:len(source.graph.initializer)]])
        opts = ort.SessionOptions(); opts.intra_op_num_threads = opts.inter_op_num_threads = 1
        a=ort.InferenceSession(source.SerializeToString(),opts); b=ort.InferenceSession(candidate.SerializeToString(),opts)
        data={"points":np.linspace(-50,50,292,dtype=np.float32).reshape(1,146,2),
              "scale":np.geomspace(1.2,83.5,146).astype(np.float32).reshape(1,146,1),
              "variance":np.geomspace(.028**2,3.6**2,97).astype(np.float32).reshape(1,1,97,1)}
        for expected,actual in zip(a.run(None,data),b.run(None,data)):
            np.testing.assert_allclose(expected,actual,atol=1e-5,rtol=0)
        source.graph.node[2].input[:] = list(reversed(source.graph.node[2].input))
        with self.assertRaises(ValueError):div_multiply_pow(source)
        source.ParseFromString(before);source.graph.initializer[2].CopyFrom(
            numpy_helper.from_array(np.asarray([2.],np.float32),source.graph.initializer[2].name))
        with self.assertRaises(ValueError):div_multiply_pow(source)
        source.ParseFromString(before);source.graph.node.pop()
        with self.assertRaises(ValueError):div_multiply_pow(source)
        source.ParseFromString(before);source.graph.node[2].name="unexpected/div"
        with self.assertRaises(ValueError):div_multiply_pow(source)
        source.ParseFromString(before);source.graph.node.append(helper.make_node(
            "Identity",["points"],["unused"],name="model_1/tf.math.truediv_1/truediv/reciprocal_pow"))
        with self.assertRaises(ValueError):div_multiply_pow(source)

    def test_sqrt_pow_covers_norm_and_all_layer_norms(self):
        import onnxruntime as ort
        names = ["model_1/tf.compat.v1.norm/norm/Sqrt"] + [
            "model_1/model/MixerBlock_%d/layer_norm%d/batchnorm/Rsqrt" % (i // 2, i % 2 + 1)
            for i in range(8)]
        nodes = [helper.make_node("Sqrt", ["input"], ["out" + str(i)], name=name) for i, name in enumerate(names)]
        outputs = [helper.make_tensor_value_info("out" + str(i), TensorProto.FLOAT, [1, 1, 1, 8]) for i in range(9)]
        source = helper.make_model(helper.make_graph(nodes, "sqrt",
            [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 1, 1, 8])], outputs),
            opset_imports=[helper.make_opsetid("", 11)])
        before = source.SerializeToString()
        candidate, changes = sqrt_pow(source)
        self.assertEqual(source.SerializeToString(), before)
        self.assertEqual(len(changes), 9)
        self.assertTrue(all(n.op_type == "Pow" for n in candidate.graph.node))
        opts = ort.SessionOptions(); opts.intra_op_num_threads = opts.inter_op_num_threads = 1
        a = ort.InferenceSession(source.SerializeToString(), opts)
        b = ort.InferenceSession(candidate.SerializeToString(), opts)
        inputs = {"input": np.asarray([0., 1e-6, 1e-4, .1, .5, 1., 2., 100.], np.float32).reshape(1, 1, 1, 8)}
        for expected, actual in zip(a.run(None, inputs), b.run(None, inputs)):
            # ORT 1.6 Sqrt and Pow(.5) differ by up to 1.1920929e-7 on these inputs.
            # Apply the pre-existing absolute gate; do not claim bitwise equivalence.
            np.testing.assert_allclose(expected, actual, atol=1e-5, rtol=0)
        source.graph.node[0].name = "unexpected/Sqrt"
        with self.assertRaises(ValueError): sqrt_pow(source)
        source.graph.node[0].name = names[0]
        source.graph.node.pop()
        with self.assertRaises(ValueError): sqrt_pow(source)

    def test_neg_multiply_keeps_float_values_and_signed_zeros(self):
        import onnxruntime as ort
        nodes, outputs = [], []
        for i in range(8):
            name = "model/MixerBlock_%d/layer_norm%d/batchnorm/Neg" % (i // 2, i % 2 + 1)
            nodes.append(helper.make_node("Neg", ["input"], ["out" + str(i)], name=name))
            outputs.append(helper.make_tensor_value_info("out" + str(i), TensorProto.FLOAT, [1, 1, 1, 7]))
        source = helper.make_model(helper.make_graph(nodes, "neg",
            [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 1, 1, 7])], outputs),
            opset_imports=[helper.make_opsetid("", 11)])
        before = source.SerializeToString()
        candidate, changes = neg_multiply(source)
        self.assertEqual(source.SerializeToString(), before)
        self.assertEqual(len(changes), 8)
        self.assertTrue(all(n.op_type == "Mul" for n in candidate.graph.node))
        opts = ort.SessionOptions(); opts.intra_op_num_threads = opts.inter_op_num_threads = 1
        a = ort.InferenceSession(source.SerializeToString(), opts)
        b = ort.InferenceSession(candidate.SerializeToString(), opts)
        inputs = {"input": np.asarray([0., -0., 1., -3., 1e-20, -1e-20, 1e20], np.float32).reshape(1, 1, 1, 7)}
        for expected, actual in zip(a.run(None, inputs), b.run(None, inputs)):
            self.assertEqual(expected.tobytes(), actual.tobytes())
        source.graph.node[0].name = "unexpected"
        with self.assertRaises(ValueError): neg_multiply(source)

    def test_reciprocal_div_keeps_sqrt_and_exact_elementwise_results(self):
        import onnxruntime as ort
        nodes, outputs = [], []
        for i in range(8):
            name = "model/MixerBlock_%d/layer_norm%d/batchnorm/Rsqrt" % (i // 2, i % 2 + 1)
            nodes.append(helper.make_node("Sqrt", ["input"], ["sqrt" + str(i)], name=name))
            nodes.append(helper.make_node("Reciprocal", ["sqrt" + str(i)], ["out" + str(i)], name=name + "__" + str(i)))
            outputs.append(helper.make_tensor_value_info("out" + str(i), TensorProto.FLOAT, [1, 97, 1, 1]))
        source = helper.make_model(helper.make_graph(nodes, "reciprocal",
            [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 97, 1, 1])], outputs),
            opset_imports=[helper.make_opsetid("", 11)])
        before = source.SerializeToString()
        candidate, changes = reciprocal_div(source)
        self.assertEqual(source.SerializeToString(), before)
        self.assertEqual(len(changes), 8)
        self.assertEqual([n.op_type for n in candidate.graph.node], ["Sqrt", "Div"] * 8)
        for a, b in zip(source.graph.node[::2], candidate.graph.node[::2]):
            self.assertEqual(a.SerializeToString(), b.SerializeToString())
        opts = ort.SessionOptions(); opts.intra_op_num_threads = opts.inter_op_num_threads = 1
        a = ort.InferenceSession(source.SerializeToString(), opts)
        b = ort.InferenceSession(candidate.SerializeToString(), opts)
        for value in (np.geomspace(1e-6, 1e3, 97).astype(np.float32), np.arange(1, 98, dtype=np.float32)):
            inputs = {"input": value.reshape(1, 97, 1, 1)}
            for expected, actual in zip(a.run(None, inputs), b.run(None, inputs)):
                np.testing.assert_array_equal(expected, actual)
        source.graph.node[0].op_type = "Identity"
        with self.assertRaises(ValueError): reciprocal_div(source)

    def test_single_channel_conv_preserves_all_gamma_values_and_axes(self):
        import onnxruntime as ort
        source = fixture(); before = source.SerializeToString()
        candidate, changes = gamma_conv(source)
        self.assertEqual(source.SerializeToString(), before)
        self.assertEqual(len(changes), 8)
        self.assertEqual([n.op_type for n in candidate.graph.node], ["Conv", "Transpose"] * 8)
        for a, b in zip(source.graph.initializer, candidate.graph.initializer):
            self.assertEqual(list(b.dims), [64, 1, 1, 1])
            self.assertEqual(numpy_helper.to_array(a).tobytes(), numpy_helper.to_array(b).tobytes())
        onnx.checker.check_model(candidate)
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        expected_session = ort.InferenceSession(source.SerializeToString(), options)
        actual_session = ort.InferenceSession(candidate.SerializeToString(), options)
        # Position-coded input detects axis permutation; random signed values test arithmetic.
        inputs = [np.arange(97, dtype=np.float32).reshape(1, 1, 97, 1),
                  np.random.RandomState(19).normal(size=(1, 1, 97, 1)).astype(np.float32)]
        for value in inputs:
            expected = expected_session.run(None, {"variance": value})
            actual = actual_session.run(None, {"variance": value})
            self.assertEqual(len(actual), 8)
            for a, b in zip(expected, actual):
                self.assertEqual(b.shape, (1, 1, 97, 64))
                np.testing.assert_array_equal(a, b)

    def test_conv_rewrite_rejects_collision_and_invalid_source(self):
        source = fixture()
        source.graph.node.append(helper.make_node("Identity", ["variance"], ["unused"],
                                 name=source.graph.node[0].name + "/gamma_conv"))
        with self.assertRaises(ValueError): gamma_conv(source)
        source = fixture(); source.graph.input[0].type.tensor_type.shape.dim[1].dim_value = 2
        with self.assertRaises(ValueError): gamma_conv(source)

    def test_rank_change_is_exact_and_source_immutable(self):
        source = fixture(); before = source.SerializeToString()
        candidate, changes = explicit_gamma_rank(source)
        self.assertEqual(source.SerializeToString(), before)
        self.assertEqual(len(changes), 8)
        random = np.random.RandomState(1003)
        x = random.normal(size=(1, 1, 97, 1)).astype(np.float32)
        for a, b in zip(source.graph.initializer, candidate.graph.initializer):
            expected = numpy_helper.to_array(a)
            actual = numpy_helper.to_array(b)
            self.assertEqual(actual.shape, (1, 1, 1, 64))
            self.assertEqual(expected.tobytes(), actual.tobytes())
            self.assertEqual((x * expected).tobytes(), (x * actual).tobytes())
        self.assertEqual([n.SerializeToString() for n in source.graph.node],
                         [n.SerializeToString() for n in candidate.graph.node])
        onnx.checker.check_model(candidate)

    def test_missing_or_extra_target_rejected(self):
        source = fixture(); del source.graph.node[-1]
        with self.assertRaises(ValueError): explicit_gamma_rank(source)

    def test_wrong_gamma_shape_rejected(self):
        source = fixture(); source.graph.initializer[0].dims[0] = 32
        with self.assertRaises((ValueError, RuntimeError)): explicit_gamma_rank(source)

    def test_shared_constant_rejected(self):
        source = fixture(); source.graph.node[1].input[1] = "gamma0"
        with self.assertRaises(ValueError): explicit_gamma_rank(source)

    def test_nonfinite_constant_rejected(self):
        source = fixture(); values = np.ones(64, dtype=np.float32); values[3] = np.nan
        source.graph.initializer[0].CopyFrom(numpy_helper.from_array(values, "gamma0"))
        with self.assertRaises(ValueError): explicit_gamma_rank(source)

    def test_changed_variable_layout_rejected(self):
        source = fixture()
        source.graph.input[0].type.tensor_type.shape.dim[3].dim_value = 64
        with self.assertRaises(ValueError): explicit_gamma_rank(source)

    def test_literal_reshape_proves_shape_without_native_inference(self):
        source = fixture()
        source.graph.initializer.append(numpy_helper.from_array(np.asarray([1, 1, 97, 1], np.int64), "shape"))
        source.graph.node.insert(0, helper.make_node("Reshape", ["variance", "shape"], ["reshaped"], name="reshape"))
        for node in list(source.graph.node)[1:]:
            node.input[0] = "reshaped"
        candidate, changes = explicit_gamma_rank(source)
        self.assertEqual(len(changes), 8)
        self.assertTrue(all(c["variable_shape_origin"]["kind"] == "literal_reshape_shape" for c in changes))
        onnx.checker.check_model(candidate)
        source.graph.initializer[-1].CopyFrom(numpy_helper.from_array(np.asarray([1, 1, -1, 1], np.int64), "shape"))
        with self.assertRaises(ValueError): explicit_gamma_rank(source)

if __name__ == "__main__":
    unittest.main()
