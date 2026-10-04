"""Actual frozen-graph LayerNorm observation tests. Offline ORT only; no RKNN.

BLENDSHAPE_SOURCE may point to the frozen fbfe... model; tests require its exact
SHA instead of silently replacing the real contract with a synthetic graph.
"""
import copy
import os
from pathlib import Path
import sys
import unittest
import numpy as np
import onnx
from onnx import helper, numpy_helper
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from prepare_blendshape_ln_taps import (
    attach_ln_taps, audit_ln_semantics, TAPS, SOURCE_TAPS, TOTAL_ELEMENTS, SOURCE_SHA, P,
)
from compile_blendshape_loweropt import sha256

DEFAULT_SOURCE = (Path(__file__).resolve().parents[2] / 'output/mirror-program/20261003/'
                  'blendshape-broadcast/gamma-conv-mulpow-v1/gamma-conv-mulpow.onnx')


class LayerNormTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(os.environ.get('BLENDSHAPE_SOURCE', str(DEFAULT_SOURCE)))
        if sha256(path) != SOURCE_SHA:
            raise ValueError('Tests require the fixed model and its real LN topology')
        cls.original = onnx.load(str(path))

    def test_observation_only_and_real_shape_contract(self):
        original = self.original
        before = original.SerializeToString()
        result = attach_ln_taps(original)
        self.assertEqual(before, original.SerializeToString())
        self.assertEqual([n.SerializeToString() for n in original.graph.node],
                         [n.SerializeToString() for n in result.graph.node[:-4]])
        self.assertEqual([n.SerializeToString() for n in original.graph.initializer],
                         [n.SerializeToString() for n in result.graph.initializer])
        self.assertEqual([n.op_type for n in result.graph.node[-4:]], ['Identity'] * 4)
        self.assertEqual([n.input[0] for n in result.graph.node[-4:]], [s[1] for s in SOURCE_TAPS[:4]])
        self.assertEqual([o.name for o in result.graph.output], [t[1] for t in TAPS])
        self.assertEqual(TOTAL_ELEMENTS, 12662)
        self.assertTrue(all(len(name.encode('utf-8')) < 128 for _, name, _ in TAPS))

    def test_real_statistics_and_operation_order(self):
        audit = audit_ln_semantics(self.original)
        self.assertEqual(audit['reduce_axes'], [1])
        self.assertEqual(audit['keepdims'], 1)
        self.assertEqual(audit['epsilon_value'], 1.0132789611816406e-6)
        self.assertEqual(audit['affine_order'], 'x*scale + (-mean)*scale')
        self.assertEqual(audit['gamma_shape'], [64, 1, 1, 1])

    def test_actual_ort_final_identical_and_statistics_match(self):
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        original = ort.InferenceSession(self.original.SerializeToString(), options)
        tapped = ort.InferenceSession(attach_ln_taps(self.original).SerializeToString(), options)
        x = np.random.RandomState(407).uniform(10, 250, (1, 146, 2)).astype(np.float32)
        result = tapped.run(None, {'serving_default_input_points:0': x})
        self.assertEqual(result[-1].tobytes(), original.run(None, {'serving_default_input_points:0': x})[0].tobytes())
        for data, (_, _, shape) in zip(result, TAPS):
            self.assertEqual(data.shape, tuple(shape))
            self.assertTrue(np.isfinite(data).all())
        token, mean, invstd = result[:3]
        # This independent formula checks axes and interpretation, not bitwise
        # equivalence of NumPy reduction with ORT's reduction implementation.
        np.testing.assert_allclose(mean, token.mean(axis=1, keepdims=True), atol=2e-7, rtol=1e-6)
        var = np.mean((token - mean) ** 2, axis=1, keepdims=True)
        np.testing.assert_allclose(invstd, 1 / np.sqrt(var + np.float32(1.0132789611816406e-6)), atol=2e-5, rtol=1e-6)

    def test_statistics_axis_and_order_mutations_rejected(self):
        for suffix in ('moments/mean', 'moments/variance'):
            with self.subTest(suffix=suffix):
                model = copy.deepcopy(self.original)
                node = next(n for n in model.graph.node if P + suffix in n.output)
                next(a for a in node.attribute if a.name == 'axes').ints[0] = 3
                with self.assertRaises(ValueError): attach_ln_taps(model)
        model = copy.deepcopy(self.original)
        node = next(n for n in model.graph.node if P + 'batchnorm/add_1' in n.output)
        reversed_inputs = list(node.input)[::-1]
        del node.input[:]
        node.input.extend(reversed_inputs)
        with self.assertRaises(ValueError): attach_ln_taps(model)

    def test_epsilon_gamma_and_pow_mutations_rejected(self):
        for name in ('const_fold_opt__389', 'const_fold_opt__388'):
            with self.subTest(name=name):
                model = copy.deepcopy(self.original)
                c = next(c for c in model.graph.initializer if c.name == name)
                data = numpy_helper.to_array(c).copy()
                data.flat[0] = np.nextafter(data.flat[0], np.float32(100))
                c.CopyFrom(numpy_helper.from_array(data, name))
                with self.assertRaises(ValueError): attach_ln_taps(model)
        model = copy.deepcopy(self.original)
        node = next(n for n in model.graph.node if P + 'batchnorm/Rsqrt' in n.output)
        node.op_type = 'Mul'
        with self.assertRaises(ValueError): attach_ln_taps(model)

    def test_alias_collision_duplicate_and_wrong_io_rejected(self):
        for mode in ('alias', 'node_name', 'duplicate', 'input_shape', 'output_shape'):
            with self.subTest(mode=mode):
                model = copy.deepcopy(self.original)
                if mode in ('alias', 'node_name', 'duplicate'):
                    output = TAPS[0][1] if mode == 'alias' else ('other' if mode == 'node_name' else SOURCE_TAPS[0][1])
                    name = TAPS[0][1] + '_identity' if mode == 'node_name' else 'extra'
                    model.graph.node.append(helper.make_node('Identity', [SOURCE_TAPS[0][1]], [output], name=name))
                else:
                    sequence = model.graph.input if mode == 'input_shape' else model.graph.output
                    sequence[0].type.tensor_type.shape.dim[-1].dim_value = 7
                with self.assertRaises(ValueError): attach_ln_taps(model)


if __name__ == '__main__': unittest.main()
