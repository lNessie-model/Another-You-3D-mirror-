"""Deterministic presentation/state tests; never contacts Android or ADB."""
import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from run_runtime_check import analyze_presentation, parse_surface_latency, surface_layer_candidates
import run_runtime_check as runtime


def ns(seconds):
    return round(seconds * 1_000_000_000)


def status(updated, events, state="INTERACTIVE", **extra):
    return dict(updated_monotonic_ns=ns(updated), updated_elapsed_ns=ns(updated + 100),
                state=state, state_events=[dict(monotonic_ns=ns(t), state=s) for t, s in events], **extra)


def window(start, end, fps=30, layer="mirror"):
    return dict(layer=layer, timestamps_ns=[ns(start + i / fps)
                for i in range(round((end - start) * fps) + 1)])


class RuntimeMetricsTests(unittest.TestCase):
    def test_real_device_background_wrapper_is_not_a_surface_candidate(self):
        actual = "SurfaceView - com.mirror.bench/com.mirror.bench.MirrorActivity#0"
        background = "Background for -SurfaceView - com.mirror.bench/com.mirror.bench.MirrorActivity#0"
        layers = "\n".join((background, actual, "SurfaceView - unrelated.example/MainActivity#0"))
        self.assertEqual(surface_layer_candidates(layers), [actual])

    def test_two_real_activity_surfaces_remain_ambiguous(self):
        first = "SurfaceView - com.mirror.bench/com.mirror.bench.MirrorActivity#0"
        second = "SurfaceView - com.mirror.bench/com.mirror.bench.MirrorActivity#1"
        self.assertEqual(surface_layer_candidates(first + "\n" + second), [first, second])

    def test_empty_evidence_never_passes(self):
        result = analyze_presentation([], [])
        self.assertIsNone(result["interactive_presented_fps"])
        self.assertFalse(result["complete_active_evidence"])
        self.assertFalse(result["meets_30_fps"])

    def test_idle_frames_do_not_dilute_active_rate(self):
        events = [(1, "WAITING"), (3, "INTERACTIVE"), (7, "GRACE"), (8, "WAITING")]
        result = analyze_presentation([status(9, events, "WAITING")],
                [window(1, 3, 10), window(3, 7, 30), window(7, 9, 10)])
        self.assertEqual(len(result["intervals"]), 1)
        self.assertAlmostEqual(result["interactive_presented_fps"], 30, places=5)
        self.assertAlmostEqual(result["interactive_duration_s"], 4)
        self.assertTrue(result["complete_active_evidence"])
        self.assertTrue(result["meets_30_fps"])

    def test_separate_interactive_intervals_do_not_bridge_idle(self):
        events = [(1, "WAITING"), (2, "INTERACTIVE"), (4, "WAITING"), (8, "INTERACTIVE"), (10, "WAITING")]
        result = analyze_presentation([status(11, events, "WAITING")],
                                     [window(1, 5, 30), window(7, 11, 30)])
        self.assertEqual(len(result["intervals"]), 2)
        self.assertAlmostEqual(result["interactive_presented_fps"], 30, places=5)
        self.assertAlmostEqual(result["interactive_duration_s"], 4)
        self.assertTrue(result["complete_active_evidence"])

    def test_surface_history_gap_is_incomplete_lower_bound(self):
        result = analyze_presentation([status(10, [(1, "WAITING"), (2, "INTERACTIVE")])],
                                     [window(1, 4), window(7, 10)])
        self.assertFalse(result["complete_active_evidence"])
        self.assertTrue(result["rate_is_lower_bound"])
        self.assertFalse(result["meets_30_fps"])
        self.assertGreater(result["surface_history_gap_seconds"], 2.9)

    def test_overlapping_history_is_deduplicated(self):
        result = analyze_presentation([status(6, [(1, "WAITING"), (2, "INTERACTIVE")])],
                                     [window(1, 4), window(2, 5), window(3, 6)])
        self.assertAlmostEqual(result["interactive_presented_fps"], 30, places=5)
        self.assertTrue(result["complete_active_evidence"])

    def test_status_event_ring_gap_cannot_claim_complete(self):
        first = status(4, [(1, "WAITING"), (2, "INTERACTIVE")])
        second = status(10, [(7, "WAITING"), (8, "INTERACTIVE")])
        result = analyze_presentation([first, second], [window(1, 10)])
        self.assertFalse(result["complete_active_evidence"])
        self.assertGreater(len(result["state_history_gaps"]), 0)
        self.assertFalse(result["meets_30_fps"])
        self.assertAlmostEqual(result["interactive_duration_s"], 4)

    def test_surface_boundary_loss_is_not_full_coverage(self):
        result = analyze_presentation([status(8, [(1, "WAITING"), (2, "INTERACTIVE")])],
                                     [window(3, 7)])
        self.assertFalse(result["complete_active_evidence"])
        self.assertGreater(result["surface_history_gap_seconds"], 1.9)

    def test_error_status_invalidates_full_pass(self):
        row = status(6, [(1, "WAITING"), (2, "INTERACTIVE")], renderer={"error": "GL error 1285"})
        result = analyze_presentation([row], [window(1, 6)])
        self.assertTrue(result["status_errors"])
        self.assertFalse(result["meets_30_fps"])

    def test_pause_interval_is_excluded_without_bridging(self):
        result = analyze_presentation([status(10, [(1, "WAITING"), (2, "INTERACTIVE")])],
                                     [window(1, 4), window(8, 10)], [(ns(4), ns(8))])
        self.assertEqual(len(result["intervals"]), 2)
        self.assertAlmostEqual(result["interactive_duration_s"], 4)
        self.assertTrue(result["complete_active_evidence"])

    def test_session_counter_resets_are_not_used_for_presentation(self):
        events = [(1, "WAITING"), (2, "INTERACTIVE")]
        rows = [status(4, events, metrics={"completed_frames": 100, "elapsed_s": 3}),
                status(6, events, metrics={"completed_frames": 2, "elapsed_s": 1})]
        result = analyze_presentation(rows, [window(1, 6)])
        self.assertAlmostEqual(result["interactive_presented_fps"], 30, places=5)

    def test_parser_reads_actual_column_and_ignores_sentinels(self):
        refresh, stamps = parse_surface_latency("16666666\n11 22 33\n0 0 0\n12 9223372036854775807 30\nbad\n11 22 33\n")
        self.assertEqual(refresh, 16666666)
        self.assertEqual(stamps, [22])

    def test_overlapping_different_layers_cannot_double_count_as_success(self):
        result = analyze_presentation([status(6, [(1, "WAITING"), (2, "INTERACTIVE")])],
                [window(1, 6, 30, "old"), window(1.01, 6.01, 30, "new")])
        self.assertFalse(result["complete_active_evidence"])
        self.assertFalse(result["meets_30_fps"])
        self.assertIsNone(result["interactive_presented_fps"])

    def test_inconsistent_state_snapshot_cannot_fill_a_missing_transition(self):
        result = analyze_presentation([status(6, [(1, "WAITING"), (2, "INTERACTIVE")], "WAITING")],
                                      [window(1, 6)])
        self.assertFalse(result["complete_active_evidence"])
        self.assertTrue(result["state_history_gaps"])

    def test_offline_collector_pause_preserves_process_and_finally_stops(self):
        self.offline_collection(False)

    def test_offline_collector_failure_still_stops_and_saves_evidence(self):
        self.offline_collection(True)

    def test_offline_collector_flushes_surface_before_home_in_same_shell(self):
        payload, calls = self.offline_collection(False)
        homes = [call[-1] for call in calls if "android.intent.category.HOME" in call[-1]]
        self.assertEqual(len(homes), 1)
        command = homes[0]
        self.assertLess(command.index("dumpsys SurfaceFlinger --latency"), command.index("__RUNTIME_CLOCK_BEFORE__"))
        self.assertLess(command.index("__RUNTIME_CLOCK_BEFORE__"), command.index("am start -W -a"))
        self.assertLess(command.index("am start -W -a"), command.index("__RUNTIME_CLOCK_AFTER__"))
        closing = [row for row in payload["surface_windows"] if row.get("reason") == "pre_home"]
        self.assertEqual(len(closing), 1)
        self.assertEqual(max(closing[0]["timestamps_ns"]), ns(105))
        home = next(row for row in payload["host_actions"] if row["action"] == "home")
        self.assertEqual(home["device_elapsed_before_ns"], ns(205))
        self.assertEqual(home["device_elapsed_after_ns"], ns(205))
        self.assertIn("__RUNTIME_SURFACE__", home["raw"])
        self.assertEqual(payload["presentation"]["surface_history_gap_seconds"], 0)

    def test_offline_empty_pre_home_history_remains_a_collection_error(self):
        payload, _ = self.offline_collection(False, empty_pre_home=True)
        self.assertTrue(any(row["scope"] == "pre_home_surface" for row in payload["collection_errors"]))
        self.assertGreater(payload["presentation"]["surface_history_gap_seconds"], 0.9)
        self.assertFalse(payload["presentation"]["meets_30_fps"])

    def offline_collection(self, fail_sample, empty_pre_home=False):
        clock = [100.0]
        calls = []
        foreground = [True]

        def latency():
            return "16666666\n" + "\n".join(f"{stamp} {stamp} {stamp}"
                    for stamp in window(clock[0]-3, clock[0])["timestamps_ns"])

        def fake_adb(*args, **kwargs):
            calls.append(args)
            command = args[-1]
            if args == ("get-serialno",): return runtime.SERIAL
            if command.startswith("pm path"): return "package:/data/app/base.apk"
            if command.startswith("sha256sum"): return "a"*64 + "  /data/app/base.apk"
            if command == "cat /proc/uptime": return f"{clock[0]+100:.2f} 100.00"
            if "android.intent.category.HOME" in command:
                foreground[0] = False  # The real activity surface disappears on HOME.
                if "__RUNTIME_SURFACE__" not in command: return "Status: ok"
                return ("\n__RUNTIME_SURFACE__\n" + ("" if empty_pre_home else latency())
                        + f"\n__RUNTIME_CLOCK_BEFORE__\n{clock[0]+100:.2f} 100.00"
                        + "\n__RUNTIME_ACTION__\nStatus: ok"
                        + f"\n__RUNTIME_CLOCK_AFTER__\n{clock[0]+100:.2f} 100.00").strip()
            if "0x20020000" in command: foreground[0] = True
            if "__RUNTIME_NPU__" not in command: return "Status: ok"
            if fail_sample: raise RuntimeError("simulated ADB read failure")
            raw = (f"cpu {int(clock[0]*20)} 0 0 {int(clock[0]*80)} 0 0 0 0\n"
                   "MemAvailable: 512000 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n60000\n50@400\n1000000")
            updated = int(clock[0]//5)*5
            events = [(100, "WAITING")] + ([(101, "INTERACTIVE")] if updated >= 105 else [])
            values = {"NPU": "NPU load: 30%", "CLOCK": f"{clock[0]+100:.2f} 100.00",
                      "MEM": "TOTAL PSS: 102400", "LAYERS": "SurfaceView com.mirror.bench/MirrorActivity#0" if foreground[0] else "",
                      "STATUS": json.dumps(status(updated, events, "INTERACTIVE" if updated >= 105 else "WAITING")),
                      "SURFACE": latency() if foreground[0] else ""}
            for label, value in values.items():
                if f"__RUNTIME_{label}__" in command: raw += f"\n__RUNTIME_{label}__\n" + value
            return raw

        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(name="offline", input="replay", seconds=20, output_dir=Path(directory),
                                      blackout_after=0, blackout_seconds=0, pause_at=5, pause_seconds=3)
            with patch.object(runtime, "adb", side_effect=fake_adb), \
                 patch.object(runtime.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(runtime.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0]+seconds)), \
                 contextlib.redirect_stdout(io.StringIO()):
                result = runtime.run(args)
            payload = json.loads((Path(directory)/"offline.json").read_text(encoding="utf-8"))
        stops = [call for call in calls if call[-1] == "am force-stop com.mirror.bench"]
        self.assertEqual(len(stops), 2, "Only initial and final stop; pause never force-stops the process")
        self.assertEqual(payload["host_actions"][-1]["action"], "final_force_stop")
        self.assertEqual(result, 1 if fail_sample else 0)
        self.assertEqual(payload["collection_status"], "failed" if fail_sample else "completed")
        if fail_sample:
            self.assertFalse(payload["presentation"]["meets_30_fps"])
        else:
            self.assertTrue(any("0x20020000" in call[-1] for call in calls))
            self.assertGreater(len(payload["samples"]), 10)
            self.assertGreater(len(payload["statuses"]), 1)
            self.assertLess(len(payload["statuses"]), len(payload["status_reads"]))
            self.assertTrue(all("raw" in window for window in payload["surface_windows"]))
        return payload, calls


if __name__ == "__main__":
    unittest.main()
