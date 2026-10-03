"""Actual ONNX shape rewrite tests; uses existing WSL ONNX, never RKNN or Android."""
import sys
import unittest
from pathlib import Path
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from blendshape_broadcast import explicit_gamma_rank, gamma_conv, reciprocal_div, neg_multiply

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
