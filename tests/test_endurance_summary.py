"""Focused tests for FPS attribution; no device imports or operations."""
import importlib.util
import contextlib
import io
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

    def test_ambiguity_touching_bin_boundary_does_not_overlap(self):
        intervals = [dict(start_ns=0, end_ns=300)]
        ambiguity = [dict(start_ns=-10, end_ns=0), dict(start_ns=300, end_ns=400)]
        row = endurance.presentation_bin([1, 101, 201], intervals, 0, 300, [], ambiguity)
        self.assertEqual(row['presented_fps'], 10_000_000)
        self.assertEqual(row['ambiguous_surface_interval_count'], 0)

    def test_markdown_unknown_metrics_are_not_zero(self):
        row = dict(start_minute=25, end_minute=30, presented_fps=None, rate_is_lower_bound=False,
                   cpu_percent_whole_machine=None, cpu0_mhz_mean=None, cpu0_mhz_min=None,
                   temperature_c_mean=None, temperature_c_max=None, app_pss_mib_mean=None,
                   app_pss_mib_peak=None, fresh_age_ms_max=None)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            endurance.markdown(dict(bins=[row]))
        self.assertIn('| 25–30 | — | — | — / — | — / — | — / — | — |', output.getvalue())
        self.assertNotIn('None', output.getvalue())

    def test_markdown_preserves_known_values_and_gap_marker(self):
        row = dict(start_minute=15, end_minute=20, presented_fps=30.531123, rate_is_lower_bound=True,
                   cpu_percent_whole_machine=79.181, cpu0_mhz_mean=1681.2, cpu0_mhz_min=1608,
                   temperature_c_mean=79.351, temperature_c_max=80, app_pss_mib_mean=493.224,
                   app_pss_mib_peak=496.695, fresh_age_ms_max=191)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            endurance.markdown(dict(bins=[row]))
        self.assertIn('| 15–20 | 30.531† | 79.18 | 1681 / 1608 | 79.35 / 80.000 | 493.22 / 496.69 | 191 |', output.getvalue())

    def test_summary_keeps_overlapping_surface_evidence_unknown(self):
        # Each layer is 10 FPS, interleaved at 50ms. Merging cannot establish 20 FPS.
        windows = [dict(layer='old', timestamps_ns=list(range(1, 1_000_000_002, 100_000_000))),
                   dict(layer='new', timestamps_ns=list(range(50_000_001, 1_050_000_002, 100_000_000)))]
        presentation = dict(intervals=[dict(start_ns=0, end_ns=1_100_000_000, presented_fps=None)],
            surface_history_gaps=[], ambiguous_surface_intervals=[dict(start_ns=50_000_001,
            end_ns=1_000_000_001, layers=['old', 'new'])], interactive_presented_fps=None,
            meets_30_fps=False, complete_active_evidence=False, rate_is_lower_bound=False,
            unconfirmed_tail_seconds=0, state_history_gaps=[])
        samples = [dict(device_monotonic_ns=t, cpu_ticks=[i*100, 0, 0, i*100, 0],
                   temperature_c=70) for i,t in enumerate([1, 500_000_001, 1_100_000_001])]
        status = dict(updated_monotonic_ns=1_100_000_000, state='INTERACTIVE', result_age_ms=120,
                      view_count=16, view_width=400, view_height=640, diagnostic_character=False,
                      recoveries=0, metrics={})
        summary = endurance.summarize(dict(samples=samples, statuses=[status], surface_windows=windows,
            presentation=presentation, collection_status='completed', arguments=dict(input='camera_replay'),
            collection_errors=[]))
        self.assertIsNone(summary['bins'][0]['presented_fps'])
        self.assertEqual(summary['bins'][0]['ambiguous_surface_interval_count'], 1)
        self.assertFalse(summary['meets_30_fps'])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            endurance.markdown(summary)
        self.assertIn('—‡', output.getvalue())


if __name__ == "__main__":
    unittest.main()
