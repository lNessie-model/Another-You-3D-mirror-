"""Deterministic presentation/state tests; never contacts Android or ADB."""
import argparse
import contextlib
import io
import json
import re
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

    def test_synchronous_avatar_override_survives_resume(self):
        payload, calls = self.offline_collection(False, synchronous=True)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all("--ez test_avatar_synchronous true" in command for command in launches))
        self.assertTrue(payload["arguments"]["avatar_synchronous"])

    def test_draw_and_size_overrides_survive_resume_without_saving_preferences(self):
        payload, calls = self.offline_collection(False, batched=True, view_preset="400x720")
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all("--ez test_avatar_batched true" in command for command in launches))
        self.assertTrue(all("--es test_view_preset 400x720" in command for command in launches))
        self.assertFalse(any("shared_prefs" in call[-1] for call in calls))
        self.assertEqual(payload["arguments"]["view_preset"], "400x720")

    def test_400640_cli_and_same_size_start_resume_contract(self):
        with patch.object(sys,"argv",["run_runtime_check.py","--name","offline","--output-dir",".","--view-preset","400x640"]):
            self.assertEqual(runtime.arguments().view_preset,"400x640")
        payload,calls=self.offline_collection(False,view_preset="400x640",view_count=16)
        launches=[call[-1] for call in calls if call[-1].startswith("am start -n "+runtime.COMPONENT)]
        self.assertEqual(len(launches),2)
        self.assertTrue(all(command.count("--es test_view_preset 400x640")==1 and "--ei test_view_count 16" in command for command in launches))
        self.assertEqual(payload["arguments"]["view_preset"],"400x640")
        self.assertFalse(any("shared_prefs" in call[-1] for call in calls))

    def test_collector_preserves_actual_art_state_instead_of_assuming_speed(self):
        payload, calls = self.offline_collection(False)
        self.assertIn("status=quicken", payload["app_package_state_before_run"])
        self.assertFalse(any("cmd package compile" in call[-1] for call in calls))

    def test_target_override_survives_home_and_does_not_change_preferences(self):
        payload, calls = self.offline_collection(False, active_target_fps=35)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all("--ei test_active_target_fps 35" in command for command in launches))
        self.assertFalse(any("shared_prefs" in call[-1] for call in calls))
        self.assertEqual(payload["arguments"]["active_target_fps"], 35)

    def test_default_does_not_pass_target_override(self):
        _, calls = self.offline_collection(False)
        self.assertFalse(any("test_active_target_fps" in call[-1] for call in calls))

    def test_persistent_fbos_cli_is_explicit_opt_in(self):
        required = ["run_runtime_check.py", "--name", "offline", "--output-dir", "."]
        with patch.object(sys, "argv", required):
            self.assertIs(runtime.arguments().persistent_fbos, False)
        with patch.object(sys, "argv", required + ["--persistent-fbos"]):
            self.assertIs(runtime.arguments().persistent_fbos, True)

    def test_persistent_fbos_survive_both_actual_launches_and_are_recorded(self):
        payload, calls = self.offline_collection(False, persistent_fbos=True)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertNotIn("0x20020000", launches[0])
        self.assertIn("0x20020000", launches[1])
        for command in launches:
            self.assertEqual(command.count("--ez test_persistent_fbos true"), 1)
        actions = [row for row in payload["host_actions"] if row["action"] in ("start", "resume")]
        self.assertEqual([row["action"] for row in actions], ["start", "resume"])
        self.assertEqual([row["command"] for row in actions], launches)
        self.assertIs(payload["arguments"]["persistent_fbos"], True)
        self.assertFalse(any("shared_prefs" in call[-1] for call in calls))

    def test_default_keeps_legacy_for_both_actual_launches(self):
        payload, calls = self.offline_collection(False)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all("test_persistent_fbos" not in command for command in launches))
        self.assertIs(payload["arguments"]["persistent_fbos"], False)

    def test_camera_vp_cache_cli_and_both_launches(self):
        required = ["run_runtime_check.py", "--name", "offline", "--output-dir", "."]
        with patch.object(sys, "argv", required):
            self.assertIs(runtime.arguments().cached_camera_vp, False)
        with patch.object(sys, "argv", required + ["--cached-camera-vp"]):
            self.assertIs(runtime.arguments().cached_camera_vp, True)
        payload, calls = self.offline_collection(False, cached_camera_vp=True)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertIn("0x20020000", launches[1])
        self.assertTrue(all(command.count("--ez test_cached_camera_vp true") == 1 for command in launches))
        actions = [row for row in payload["host_actions"] if row["action"] in ("start", "resume")]
        self.assertEqual([row["action"] for row in actions], ["start", "resume"])
        self.assertEqual([row["command"] for row in actions], launches)
        self.assertIs(payload["arguments"]["cached_camera_vp"], True)
        self.assertFalse(any("shared_prefs" in call[-1] for call in calls))

    def test_camera_vp_default_does_not_enable_either_launch(self):
        payload, calls = self.offline_collection(False)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all("test_cached_camera_vp" not in command for command in launches))
        self.assertIs(payload["arguments"]["cached_camera_vp"], False)

    def test_view_count_cli_defaults_and_strict_choices(self):
        required = ["run_runtime_check.py", "--name", "offline", "--output-dir", "."]
        with patch.object(sys, "argv", required):
            self.assertIsNone(runtime.arguments().view_count)
        for count in (16, 20):
            with patch.object(sys, "argv", required + ["--view-count", str(count)]):
                self.assertEqual(runtime.arguments().view_count, count)
        for value in ("0", "15", "17", "32", "16.0", "true"):
            with patch.object(sys, "argv", required + ["--view-count", value]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    runtime.arguments()

    def test_view_count_survives_both_launches_without_saving_preferences(self):
        for count in (16, 20):
            payload, calls = self.offline_collection(False, view_count=count)
            launches = [call[-1] for call in calls if "am start -n " in call[-1]]
            self.assertEqual(len(launches), 2)
            self.assertIn("0x20020000", launches[1])
            self.assertTrue(all(command.count(f"--ei test_view_count {count}") == 1 for command in launches))
            self.assertEqual([row["command"] for row in payload["host_actions"] if row["action"] in ("start", "resume")], launches)
            self.assertEqual(payload["arguments"]["view_count"], count)
            self.assertFalse(any("shared_prefs" in call[-1] for call in calls))

    def test_default_view_count_does_not_override_twenty(self):
        payload, calls = self.offline_collection(False)
        launches = [call[-1] for call in calls if "am start -n " in call[-1]]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all("test_view_count" not in command for command in launches))
        self.assertIsNone(payload["arguments"]["view_count"])

    def test_surface_is_queried_before_slow_memory_and_without_draw_wait(self):
        payload, calls = self.offline_collection(False, slow_start=True, memory_delay=3,
                                                  presentation_delay=.4, no_pause=True)
        first = payload["surface_windows"][0]
        self.assertLess(first["host_monotonic_s"], 101)
        self.assertGreaterEqual(payload["samples"][0]["host_poll_end_s"], 103)
        command = next(call[-1] for call in calls if "__RUNTIME_MEM__" in call[-1])
        self.assertLess(command.index("--latency"), command.index("dumpsys meminfo"))
        probe = next(i for i, call in enumerate(calls) if call[-1]=="dumpsys SurfaceFlinger --list")
        self.assertIn("--latency", calls[probe+1][-1], "The discovered layer is queried in this poll")
        self.assertEqual(payload["presentation"]["surface_history_gap_seconds"], 0)

    def test_real_startup_no_present_interval_is_not_interpolated_away(self):
        payload, _ = self.offline_collection(False, presentation_delay=2, no_pause=True)
        self.assertAlmostEqual(payload["presentation"]["surface_history_gap_seconds"], 1)
        self.assertFalse(payload["presentation"]["meets_30_fps"])

    def test_final_snapshot_captures_new_status_and_last_surface_before_stop(self):
        payload, calls = self.offline_collection(False)
        final = payload["surface_windows"][-1]
        self.assertEqual(final["reason"], "pre_stop")
        self.assertEqual(max(final["timestamps_ns"]), ns(120))
        self.assertEqual(payload["statuses"][-1]["updated_monotonic_ns"], ns(120))
        self.assertEqual(payload["presentation"]["unconfirmed_tail_seconds"], 0)
        snapshot = next(i for i, call in enumerate(calls) if "__RUNTIME_STATUS__" in call[-1]
                        and "__RUNTIME_NPU__" not in call[-1])
        self.assertEqual(calls[snapshot+1][-1], "am force-stop com.mirror.bench")
        self.assertIn("__RUNTIME_STATUS__", final["raw_snapshot"])

    def test_stale_final_status_preserves_unconfirmed_tail(self):
        payload, _ = self.offline_collection(False, final_status_offset=5)
        self.assertAlmostEqual(payload["presentation"]["unconfirmed_tail_seconds"], 5)
        self.assertEqual(payload["presentation"]["confirmed_until_monotonic_ns"], ns(115))
        self.assertEqual(payload["presentation"]["observed_until_monotonic_ns"], ns(120))

    def test_final_read_exception_still_force_stops_independently(self):
        payload, calls = self.offline_collection(False, final_error="raise")
        self.assertEqual(calls[-1][-1], "am force-stop com.mirror.bench")
        self.assertTrue(any(row["scope"]=="final_snapshot" for row in payload["collection_errors"]))
        self.assertFalse(payload["presentation"]["meets_30_fps"])

    def test_malformed_final_output_is_preserved_and_never_passes(self):
        payload, calls = self.offline_collection(False, final_error="malformed")
        self.assertEqual(calls[-1][-1], "am force-stop com.mirror.bench")
        self.assertEqual(payload["surface_windows"][-1]["raw_snapshot"], "wrong row\nPermission denied")
        self.assertTrue(any(row["scope"]=="final_snapshot" for row in payload["collection_errors"]))
        self.assertFalse(payload["presentation"]["collection_complete"])
        self.assertFalse(payload["presentation"]["meets_30_fps"])

    def test_release_gl_cli_default_and_explicit_flag(self):
        required=["run_runtime_check.py","--name","offline","--output-dir","."]
        with patch.object(sys,"argv",required):
            self.assertIs(runtime.arguments().release_gl_on_pause,False)
        with patch.object(sys,"argv",required+["--release-gl-on-pause"]):
            self.assertIs(runtime.arguments().release_gl_on_pause,True)

    def test_release_gl_option_reaches_start_and_resume_only_when_requested(self):
        for enabled in (False,True):
            payload,calls=self.offline_collection(False,release_gl_on_pause=enabled)
            launches=[c[-1] for c in calls if c[-1].startswith("am start -n "+runtime.COMPONENT)]
            self.assertEqual(len(launches),2)
            self.assertTrue(all(c.count("--ez test_release_gl_on_pause true")==int(enabled) for c in launches))
            self.assertIs(payload["arguments"]["release_gl_on_pause"],enabled)
            # Legacy fixture has no generation identity; it must never certify context recovery.
            self.assertFalse(payload["gl_context_recovery"]["verified"])

    def test_expression_cli_requires_explicit_opt_in(self):
        required=["run_runtime_check.py","--name","offline","--output-dir","."]
        with patch.object(sys,"argv",required):
            self.assertIs(runtime.arguments().npu_blendshapes,False)
        with patch.object(sys,"argv",required+["--npu-blendshapes"]):
            self.assertIs(runtime.arguments().npu_blendshapes,True)

    def test_expression_flag_reaches_start_and_resume_only_when_requested(self):
        for enabled in (False,True):
            payload,calls=self.offline_collection(False,npu_blendshapes=enabled)
            launches=[c[-1] for c in calls if c[-1].startswith("am start -n "+runtime.COMPONENT)]
            self.assertEqual(len(launches),2)
            self.assertTrue(all(c.count("--ez test_npu_blendshapes true")==int(enabled) for c in launches))
            self.assertIs(payload["arguments"]["npu_blendshapes"],enabled)

    def test_gpu_profile_cli_is_explicit(self):
        required=["run_runtime_check.py","--name","offline","--output-dir","."]
        with patch.object(sys,"argv",required):
            self.assertIs(runtime.arguments().gpu_profile,False)
        with patch.object(sys,"argv",required+["--gpu-profile"]):
            self.assertIs(runtime.arguments().gpu_profile,True)

    def test_gpu_profile_reaches_start_and_resume_only_when_requested(self):
        for enabled in (False,True):
            payload,calls=self.offline_collection(False,gpu_profile=enabled)
            launches=[c[-1] for c in calls if c[-1].startswith("am start -n "+runtime.COMPONENT)]
            self.assertEqual(len(launches),2)
            self.assertTrue(all(c.count("--ez test_gpu_profile true")==int(enabled) for c in launches))
            self.assertIs(payload["arguments"]["gpu_profile"],enabled)

    def offline_collection(self, fail_sample, empty_pre_home=False, synchronous=False,
                           slow_start=False, memory_delay=0, presentation_delay=0, no_pause=False,
                           final_error=None, final_status_offset=0, batched=False, view_preset=None,
                           active_target_fps=None, persistent_fbos=False, cached_camera_vp=False, view_count=None,
                           release_gl_on_pause=False,npu_blendshapes=False,gpu_profile=False):
        clock = [100.0]
        calls = []
        foreground = [True]
        delayed_memory = [False]

        def latency():
            first = max(100+presentation_delay, clock[0]-3)
            return "16666666\n" + "\n".join(f"{stamp} {stamp} {stamp}"
                    for stamp in (window(first, clock[0])["timestamps_ns"] if first<=clock[0] else []))

        def fake_adb(*args, **kwargs):
            calls.append(args)
            command = args[-1]
            if args == ("get-serialno",): return runtime.SERIAL
            if command.startswith("pm path"): return "package:/data/app/base.apk"
            if command.startswith("sha256sum"): return "a"*64 + "  /data/app/base.apk"
            if command == "dumpsys package com.mirror.bench": return "Dexopt state:\n arm64: [status=quicken] [reason=unknown]"
            if command == "cat /proc/uptime": return f"{clock[0]+100:.2f} 100.00"
            if command == "dumpsys SurfaceFlinger --list":
                return "SurfaceView com.mirror.bench/MirrorActivity#0" if foreground[0] else ""
            if "android.intent.category.HOME" in command:
                foreground[0] = False  # The real activity surface disappears on HOME.
                if "__RUNTIME_SURFACE__" not in command: return "Status: ok"
                return ("\n__RUNTIME_SURFACE__\n" + ("" if empty_pre_home else latency())
                        + f"\n__RUNTIME_CLOCK_BEFORE__\n{clock[0]+100:.2f} 100.00"
                        + "\n__RUNTIME_ACTION__\nStatus: ok"
                        + f"\n__RUNTIME_CLOCK_AFTER__\n{clock[0]+100:.2f} 100.00").strip()
            if "0x20020000" in command: foreground[0] = True
            if command.startswith("am start ") and runtime.COMPONENT in command:
                # A real elapsed draw wait in the fake protocol: -W holds the host
                # until 3.7 seconds; the non-waiting command acknowledges in .2.
                if slow_start:clock[0]+=3.7 if " -W " in command else .2
                return "Status: ok"
            if "__RUNTIME_" not in command:return "Status: ok"
            final = "__RUNTIME_NPU__" not in command
            if final and final_error=="raise":raise RuntimeError("simulated final snapshot failure")
            if final and final_error=="malformed":return "wrong row\nPermission denied"
            if fail_sample and not final:raise RuntimeError("simulated ADB read failure")
            raw = ""
            # Execute marker sections in their real shell order. Slow meminfo
            # advances device/host time and consumes the finite presentation ring.
            for label in re.findall(r"__RUNTIME_(\w+)__", command):
                if label=="MEM" and not delayed_memory[0]:
                    clock[0]+=memory_delay;delayed_memory[0]=True
                updated = int(clock[0]//5)*5-(final_status_offset if final else 0)
                events = [(100, "WAITING")] + ([(101, "INTERACTIVE")] if updated >= 105 else [])
                values = {"SYSTEM": (f"cpu {int(clock[0]*20)} 0 0 {int(clock[0]*80)} 0 0 0 0\n"
                            "MemAvailable: 512000 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n60000\n50@400\n1000000"),
                          "NPU":"NPU load: 30%", "CLOCK":f"{clock[0]+100:.2f} 100.00",
                          "MEM":"TOTAL PSS: 102400",
                          "STATUS":json.dumps(status(updated,events,"INTERACTIVE" if updated>=105 else "WAITING")),
                          "SURFACE":latency() if foreground[0] else ""}
                raw+=f"\n__RUNTIME_{label}__\n"+values[label]
            return raw.strip()

        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(name="offline", input="replay", seconds=20, output_dir=Path(directory),
                                      blackout_after=0, blackout_seconds=0, pause_at=None if no_pause else 5, pause_seconds=3,
                                      avatar_synchronous=synchronous, avatar_batched=batched, view_preset=view_preset,
                                      active_target_fps=active_target_fps, persistent_fbos=persistent_fbos, cached_camera_vp=cached_camera_vp,
                                      view_count=view_count,release_gl_on_pause=release_gl_on_pause,npu_blendshapes=npu_blendshapes,gpu_profile=gpu_profile)
            with patch.object(runtime, "adb", side_effect=fake_adb), \
                 patch.object(runtime.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(runtime.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0]+seconds)), \
                 contextlib.redirect_stdout(io.StringIO()):
                result = runtime.run(args)
            payload = json.loads((Path(directory)/"offline.json").read_text(encoding="utf-8"))
        stops = [call for call in calls if call[-1] == "am force-stop com.mirror.bench"]
        self.assertEqual(len(stops), 2, "Only initial and final stop; pause never force-stops the process")
        self.assertEqual(payload["host_actions"][-1]["action"], "final_force_stop")
        failed = fail_sample or final_error=="raise"
        self.assertEqual(result, 1 if failed else 0)
        self.assertEqual(payload["collection_status"], "failed" if failed else "completed")
        if failed:
            self.assertFalse(payload["presentation"]["meets_30_fps"])
        else:
            if not no_pause:self.assertTrue(any("0x20020000" in call[-1] for call in calls))
            self.assertGreater(len(payload["samples"]), 10)
            self.assertGreater(len(payload["statuses"]), 1)
            self.assertLess(len(payload["statuses"]), len(payload["status_reads"]))
            self.assertTrue(all("raw" in window for window in payload["surface_windows"]))
        return payload, calls


if __name__ == "__main__":
    unittest.main()
