"""Fixed normalization cut: actual operators, closed dependencies and exact restoration."""
import copy
import sys
import unittest
from pathlib import Path
import numpy as np
import onnx
from onnx import helper, numpy_helper

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from diagnose_normalized_boundary import (BOUNDARY, INPUT, SOURCE_SHA, COPIED_SHA,
    split_fixed_boundary, verify_partition, check_closed, round_boundary, run_rows,
    session, metric, sha256)

ROOT = Path(__file__).resolve().parents[2] / 'output/mirror-program/20261003'
SOURCE = ROOT / 'blendshape-broadcast/gamma-conv-mulpow-v1/gamma-conv-mulpow.onnx'
COPIED = ROOT / 'blendshape-numerical-diagnosis/all-ln-negmean-copy-v1/candidate.onnx'


class BoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert sha256(SOURCE) == SOURCE_SHA and sha256(COPIED) == COPIED_SHA
        cls.original = onnx.load(str(SOURCE))
        cls.copied = onnx.load(str(COPIED))

    def test_actual_partition_bytes_and_counts(self):
        for model in (self.original, self.copied):
            before = model.SerializeToString()
            front, suffix = split_fixed_boundary(model)
            verify_partition(model, front, suffix)
            self.assertEqual(before, model.SerializeToString())
            self.assertEqual(len(front.graph.node), 8)
            self.assertEqual(len(suffix.graph.node), len(model.graph.node) - 8)
            self.assertEqual([x.SerializeToString() for x in suffix.graph.initializer],
                             [x.SerializeToString() for x in model.graph.initializer])
        f0, _ = split_fixed_boundary(self.original)
        f1, _ = split_fixed_boundary(self.copied)
        self.assertEqual([n.SerializeToString() for n in f0.graph.node],
                         [n.SerializeToString() for n in f1.graph.node])

    def test_actual_unrounded_cut_and_copied_suffix_same_final(self):
        x = np.random.RandomState(838).uniform(40, 350, (1, 1, 146, 2)).astype('f4')
        front, suffix = split_fixed_boundary(self.original)
        _, copied_suffix = split_fixed_boundary(self.copied)
        normalized = run_rows(session(front), x, INPUT, (1, 146, 2))
        baseline = run_rows(session(self.original), x, INPUT, (52,))
        for model in (suffix, copied_suffix):
            self.assertEqual(run_rows(session(model), normalized, BOUNDARY, (52,)).tobytes(), baseline.tobytes())

    def test_cut_name_shape_type_and_order_mutations_rejected(self):
        for mode in ('name', 'shape', 'type', 'order', 'duplicate'):
            front, suffix = split_fixed_boundary(self.original)
            if mode == 'name': suffix.graph.input[0].name += 'wrong'
            elif mode == 'shape': suffix.graph.input[0].type.tensor_type.shape.dim[1].dim_value = 2
            elif mode == 'type': suffix.graph.input[0].type.tensor_type.elem_type = 10
            elif mode == 'order':
                n = copy.deepcopy(suffix.graph.node[0]); suffix.graph.node[0].CopyFrom(suffix.graph.node[1]); suffix.graph.node[1].CopyFrom(n)
            else: suffix.graph.node.append(copy.deepcopy(suffix.graph.node[-1]))
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError): verify_partition(self.original, front, suffix)

    def test_unchanged_weights_and_unrelated_attrs_required(self):
        for mode in ('weight', 'op', 'metadata'):
            front, suffix = split_fixed_boundary(self.original)
            if mode == 'weight':
                c = suffix.graph.initializer[0]; a = numpy_helper.to_array(c).copy(); a.flat[0] += 1
                c.CopyFrom(numpy_helper.from_array(a, c.name))
            elif mode == 'op': suffix.graph.node[0].name += '-changed'
            else: suffix.producer_name += '-changed'
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError): verify_partition(self.original, front, suffix)

    def test_raw_input_escape_and_missing_and_duplicate_producer_rejected(self):
        _, suffix = split_fixed_boundary(self.original)
        for source in (INPUT, 'unknown-external'):
            m = copy.deepcopy(suffix); m.graph.node[0].input[0] = source
            with self.assertRaises(ValueError): check_closed(m)
        m = copy.deepcopy(suffix)
        m.graph.node.append(helper.make_node('Identity', [BOUNDARY], [m.graph.node[0].output[0]]))
        with self.assertRaises(ValueError): check_closed(m)

    def test_fixed_boundary_not_arbitrary_position(self):
        for mode in ('missing', 'wrong_producer', 'reordered'):
            m = copy.deepcopy(self.original)
            if mode == 'missing': m.graph.node[7].output[0] += '-missing'
            elif mode == 'wrong_producer': m.graph.node[7].op_type = 'Add'
            else:
                n = copy.deepcopy(m.graph.node[6]); m.graph.node[6].CopyFrom(m.graph.node[7]); m.graph.node[7].CopyFrom(n)
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError): split_fixed_boundary(m)

    def test_rounding_independent_half_bits_and_invalid_tensor_rejected(self):
        x = np.zeros((1, 1, 146, 2), dtype='f4')
        x.flat[:8] = [0., -0., 1., 1.00048828125, 1.00146484375, -1., 2 ** -24, -(2 ** -24)]
        y = round_boundary(x)
        self.assertEqual(y.ravel()[:8].tobytes(), np.array([0., -0., 1., 1., 1.001953125, -1., 2 ** -24, -(2 ** -24)], dtype='f4').tobytes())
        for bad in (x.reshape(1, 1, 2, 146), x.astype('f8'), np.full_like(x, np.nan), np.full_like(x, 1e6)):
            with self.assertRaises(ValueError): round_boundary(bad)

    def test_metrics_preserve_worst_case_channel_and_original_gates(self):
        reference = np.zeros((2, 52), 'f4'); actual = reference.copy(); actual[1, 21] = .02
        result = metric(actual, reference, [8, 86])
        self.assertEqual(result['worst']['case_index'], 86)
        self.assertEqual(result['worst']['channel'], 21)
        self.assertEqual(result['values_gt_0_01'], 1)
        self.assertFalse(result['original_1e_5_gate'])
        self.assertFalse(result['device_numeric_gate'])


if __name__ == '__main__':
    unittest.main()
