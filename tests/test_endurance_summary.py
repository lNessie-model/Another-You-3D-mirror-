"""Focused tests for FPS attribution; no device imports or operations."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("endurance", Path(__file__).parents[1] / "scripts" / "summarize_runtime_endurance.py")
endurance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(endurance)


class AttributionTest(unittest.TestCase):
    def test_excludes_idle_and_weights_by_measured_duration(self):
        stamps = list(range(1, 1_000_000_002, 100_000_000))
        stamps += list(range(2_000_000_001, 3_000_000_002, 50_000_000))
        stamps += list(range(4_000_000_001, 5_000_000_002, 25_000_000))  # idle: must not count
        intervals = [dict(start_ns=0, end_ns=1_100_000_000), dict(start_ns=2_000_000_000, end_ns=3_100_000_000)]
        row = endurance.presentation_bin(stamps, intervals, 0, 6_000_000_000, [])
        self.assertEqual(row["presented_fps"], 15)
        self.assertEqual(row["sampled_seconds"], 2)
        self.assertFalse(row["rate_is_lower_bound"])

    def test_gap_cannot_be_marked_complete_or_interpolated(self):
        row = endurance.presentation_bin([1, 100_000_001, 900_000_001, 1_000_000_001],
                [dict(start_ns=0, end_ns=1_100_000_000)], 0, 2_000_000_000,
                [dict(start_ns=100_000_001, end_ns=900_000_001)])
        self.assertEqual(row["presented_fps"], 3)
        self.assertEqual(row["surface_gap_seconds"], .8)
        self.assertTrue(row["rate_is_lower_bound"])

    def test_empty_timing_is_unknown(self):
        row = endurance.presentation_bin([1], [dict(start_ns=0, end_ns=10)], 0, 10, [])
        self.assertIsNone(row["presented_fps"])

    def test_bin_end_is_exclusive(self):
        row = endurance.presentation_bin([1, 101, 201, 300], [dict(start_ns=0, end_ns=500)], 0, 300, [])
        self.assertEqual(row["presented_fps"], 10_000_000)


if __name__ == "__main__":
    unittest.main()
