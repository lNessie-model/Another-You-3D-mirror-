"""Host-only collector orchestration/journal checks. Every ADB call is replaced."""
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
import run_runtime_check as runtime


class PollJournalTests(unittest.TestCase):
    def test_cli_default_60_zero_and_invalid_interval(self):
        base = ["collector", "--name", "offline", "--output-dir", "."]
        with patch.object(sys, "argv", base):
            self.assertEqual(runtime.arguments().checkpoint_seconds, 60)
        with patch.object(sys, "argv", base + ["--checkpoint-seconds", "0"]):
            self.assertEqual(runtime.arguments().checkpoint_seconds, 0)
        for value in ("-1", "3601", "nan", "0.5"):
            with self.subTest(value=value), patch.object(sys, "argv", base + ["--checkpoint-seconds", value]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    runtime.arguments()

    def collect(self, checkpoint=0, seconds=65, fault=None, stale=False, existing=False, no_layer=False):
        clock, calls = [100.0], []

        def fake_adb(*args, **kwargs):
            calls.append(args)
            cmd = args[-1]
            if args == ("get-serialno",):
                return runtime.SERIAL
            if cmd.startswith("pm path"):
                return "package:/data/app/base.apk"
            if cmd.startswith("sha256sum"):
                return "a" * 64 + "  /data/app/base.apk"
            if cmd == "dumpsys package " + runtime.PACKAGE:
                return "ART status=quicken"
            if cmd == "cat /proc/uptime":
                return f"{clock[0]+100:.2f} 1.00"
            if cmd == "dumpsys SurfaceFlinger --list":
                return "" if no_layer else "SurfaceView com.mirror.bench/MirrorActivity#0"
            if "__RUNTIME_" not in cmd:
                return "Status: ok"
            if fault == "final_raise" and "__RUNTIME_NPU__" not in cmd:
                raise RuntimeError("fake final read failure")
            if fault == "interrupt" and "__RUNTIME_NPU__" in cmd:
                raise KeyboardInterrupt()
            if fault == "adb" and "__RUNTIME_NPU__" in cmd:
                raise RuntimeError("fake poll read failure")
            stamp = 100 if stale else clock[0]
            status = dict(updated_elapsed_ns=round((stamp + 100) * 1e9), updated_monotonic_ns=round(stamp * 1e9),
                          state="INTERACTIVE", status_sequence=int(stamp), view_count=16,
                          state_events=[dict(monotonic_ns=100_000_000_000, state="INTERACTIVE")],
                          renderer=dict(frames=int(stamp * 30), view_count=16),
                          source=dict(received_fps=24.5, capture_fps=24.7, received_images=int(stamp * 24)))
            stamps = [round((clock[0] - 3 + i / 30) * 1e9) for i in range(91)]
            values = dict(CLOCK=f"{clock[0]+100:.2f} 1.00", STATUS=json.dumps(status),
                          SURFACE="" if no_layer else "16666666\n" + "\n".join(f"{s} {s} {s}" for s in stamps),
                          SYSTEM=f"cpu {int(clock[0]*20)} 0 0 {int(clock[0]*80)} 0 0 0 0\nMemAvailable: 512000 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n60000\n50@400\n1000000",
                          NPU="NPU load: 30%", MEM="TOTAL PSS: 102400")
            return "".join("\n__RUNTIME_" + label + "__\n" + values[label] for label in re.findall(r"__RUNTIME_(\w+)__", cmd)).strip()

        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            args = argparse.Namespace(name="offline", input="replay", seconds=seconds, output_dir=directory,
                                      checkpoint_seconds=checkpoint, blackout_after=0, blackout_seconds=0,
                                      pause_at=None, pause_seconds=3, avatar_synchronous=False, avatar_batched=False,
                                      view_preset=None, active_target_fps=None, persistent_fbos=False,
                                      cached_camera_vp=False, view_count=None, release_gl_on_pause=False)
            journal = directory / "offline.polls.jsonl"
            if existing:
                journal.write_bytes(b"sentinel\n")
            stdout = io.StringIO()
            original_append = getattr(runtime, "PollJournal", None)
            append = original_append.append if original_append else None

            def maybe_fail(obj, row):
                if row["kind"] == fault:
                    raise OSError("fake append failure")
                return append(obj, row)

            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.object(runtime, "adb", side_effect=fake_adb))
                stack.enter_context(patch.object(runtime.time, "monotonic", side_effect=lambda: clock[0]))
                stack.enter_context(patch.object(runtime.time, "time", side_effect=lambda: clock[0] + 1_700_000_000))
                stack.enter_context(patch.object(runtime.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)))
                spy = stack.enter_context(patch.object(runtime, "analyze_run", wraps=runtime.analyze_run))
                if fault in ("poll", "header", "end"):
                    stack.enter_context(patch.object(runtime.PollJournal, "append", maybe_fail))
                if fault == "save":
                    stack.enter_context(patch.object(runtime.Path, "write_text", side_effect=OSError("fake full save failure")))
                stack.enter_context(contextlib.redirect_stdout(stdout))
                try:
                    result = runtime.run(args)
                except OSError as error:
                    if fault != "save":
                        raise
                    result = error
            payload = json.loads((directory / "offline.json").read_text(encoding="utf-8")) if (directory / "offline.json").exists() else None
            raw = journal.read_bytes() if journal.exists() else None
            rows = [json.loads(x) for x in raw.split(b"\n") if x] if raw and not existing else []
        return result, payload, rows, raw, calls, stdout.getvalue(), spy.call_count

    def test_zero_checkpoints_retains_raw_and_recomputes_same_final_analysis(self):
        result, payload, rows, _, calls, output, analyses = self.collect()
        self.assertEqual((result, analyses), (0, 1))
        self.assertEqual(rows[0]["kind"], "header")
        self.assertEqual(rows[-1]["kind"], "end")
        self.assertEqual(len([r for r in rows if r["kind"] == "poll"]), len(payload["samples"]))
        lists = {k: [] for k in ("samples", "statuses", "surface_windows", "host_actions", "status_reads", "surface_probes", "collection_errors", "observation_markers")}
        for row in rows:
            self.assertIsInstance(row["host_monotonic_s"], (int, float))
            self.assertIsInstance(row["host_epoch_s"], (int, float))
            for key, delta in row.get("delta", {}).items():
                lists[key].extend(delta)
            if row["kind"] == "poll":
                self.assertIn("__RUNTIME_SYSTEM__", row["raw_poll"])
                self.assertLessEqual(len(row["delta"]["samples"]), 1)
                self.assertLessEqual(len(row["delta"]["statuses"]), 1)
                self.assertLessEqual(len(row["delta"]["surface_windows"]), 1)
        self.assertEqual(lists["statuses"], payload["statuses"])
        self.assertEqual(lists["status_reads"], payload["status_reads"])
        self.assertEqual(lists["surface_probes"], payload["surface_probes"])
        self.assertEqual(lists["host_actions"], payload["host_actions"])
        rebuilt = runtime.analyze_run(lists["samples"], lists["statuses"], lists["surface_windows"], lists["host_actions"])
        for key in ("presentation", "system", "interactive_system"):
            original = dict(payload[key])
            if key == "presentation":
                original.pop("collection_complete")
            self.assertEqual(rebuilt[key], original)
        self.assertEqual(lists["samples"], payload["samples"])
        self.assertEqual(lists["surface_windows"], payload["surface_windows"])
        self.assertEqual(len([c for c in calls if c[-1] == "am force-stop " + runtime.PACKAGE]), 2)
        progress = [x for x in output.splitlines() if x.startswith("PROGRESS ")]
        self.assertEqual(len(progress), 2)
        self.assertTrue(all(len(x.encode("utf-8")) < 2048 for x in progress))
        self.assertFalse(any("presented_fps" in x for x in progress))

    def test_default_keeps_old_60_second_save_and_no_journal(self):
        result, payload, rows, raw, _, _, analyses = self.collect(checkpoint=60)
        self.assertEqual((result, analyses), (0, 3))  # first poll, 60s, final
        self.assertIsNone(raw)
        self.assertEqual(payload["arguments"]["checkpoint_seconds"], 60)

    def test_append_or_poll_failure_still_stops_and_saves_failed_full_report(self):
        for fault in ("poll", "end", "adb", "header", "final_raise"):
            with self.subTest(fault=fault):
                result, payload, rows, _, calls, _, analyses = self.collect(fault=fault)
                self.assertEqual(result, 1)
                self.assertEqual(payload["collection_status"], "failed")
                self.assertFalse(payload["presentation"]["meets_30_fps"])
                self.assertGreaterEqual(analyses, 1)
                if fault != "header":
                    self.assertEqual(calls[-1][-1], "am force-stop " + runtime.PACKAGE)
                else:
                    self.assertEqual(calls, [])

    def test_existing_journal_rejected_without_adb_or_overwrite(self):
        result, payload, rows, raw, calls, _, analyses = self.collect(existing=True)
        self.assertEqual((result, raw, calls, analyses), (1, b"sentinel\n", [], 0))
        self.assertIsNone(payload)

    def test_interrupt_stops_and_saves_interrupted_without_performance_pass(self):
        result, payload, rows, _, calls, _, analyses = self.collect(fault="interrupt")
        self.assertEqual(result, 1)
        self.assertEqual(payload["collection_status"], "interrupted")
        self.assertFalse(payload["presentation"]["meets_30_fps"])
        self.assertEqual(analyses, 1)
        self.assertEqual(rows[-1]["collection_status"], "interrupted")
        self.assertEqual(calls[-1][-1], "am force-stop " + runtime.PACKAGE)

    def test_final_full_save_failure_has_stopped_and_keeps_closed_raw_journal(self):
        result, payload, rows, _, calls, _, analyses = self.collect(fault="save")
        self.assertIsInstance(result, OSError)
        self.assertIsNone(payload)
        self.assertEqual(analyses, 1)
        self.assertEqual(calls[-1][-1], "am force-stop " + runtime.PACKAGE)
        self.assertEqual(rows[-1]["metadata"]["phase"], "after_final_stop_before_full_report_save")
        self.assertTrue(any(row["kind"] == "poll" for row in rows))

    def test_progress_rejects_large_or_non_numeric_scalars_and_exposes_clock_order(self):
        value = runtime.poll_progress(dict(temperature_c="raw"), dict(updated_elapsed_ns=2_000_000_000,
                    status_sequence=10**4000, view_count=True, renderer=dict(frames=float("inf")),
                    source=dict(received_fps="raw", capture_fps=float("nan"))), 1_000_000_000, "", False, False)
        self.assertEqual(value["status_age_seconds"], -1)
        self.assertTrue(value["status_clock_precedes_update"])
        self.assertTrue(all(value[key] is None for key in ("temperature_c", "status_sequence", "view_count", "renderer_frames", "source_received_fps", "source_capture_fps")))
        self.assertLess(len(json.dumps(value)), 1024)
        self.assertIsNone(runtime.poll_progress({}, dict(updated_elapsed_ns=10**4000), 1, "", False, False)["status_age_seconds"])

    def test_limits_and_hard_stop_prefix_is_readable_before_close(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "bounded.jsonl"
            journal = runtime.PollJournal(p)
            try:
                journal.append(dict(kind="poll", raw_poll="first raw"))
                self.assertEqual(json.loads(p.read_bytes().splitlines()[0])["raw_poll"], "first raw")
                with patch.object(runtime, "JOURNAL_RECORD_BYTES", 20):
                    with self.assertRaises(ValueError):
                        journal.append(dict(kind="poll", raw_poll="x" * 100))
                with patch.object(runtime, "JOURNAL_TOTAL_BYTES", journal.bytes_written):
                    with self.assertRaises(ValueError):
                        journal.append(dict(kind="poll"))
                with patch.object(runtime, "JOURNAL_RECORDS", journal.records):
                    with self.assertRaises(ValueError):
                        journal.append(dict(kind="poll"))
                self.assertEqual(len(p.read_bytes().splitlines()), 1)
            finally:
                journal.close()

    def test_actual_append_detects_short_write_and_flush_failure(self):
        class BadStream(io.BytesIO):
            def __init__(self, short):
                super().__init__()
                self.short = short

            def write(self, data):
                written = super().write(data)
                return written - 1 if self.short else written

            def flush(self):
                if not self.short:
                    raise OSError("fake flush failure")

        with tempfile.TemporaryDirectory() as directory:
            for short in (True, False):
                journal = runtime.PollJournal(Path(directory) / str(short))
                journal.stream.close()
                journal.stream = BadStream(short)
                try:
                    with self.assertRaises(OSError):
                        journal.append(dict(kind="poll", raw_poll="raw"))
                    self.assertEqual((journal.records, journal.bytes_written), (0, 0))
                finally:
                    journal.close()

    def test_stale_or_unexpected_missing_surface_marked_without_relaunch(self):
        result, payload, rows, _, calls, output, _ = self.collect(stale=True, no_layer=True)
        self.assertEqual(result, 0)  # collection exit is not a performance pass
        self.assertFalse(payload["presentation"]["meets_30_fps"])
        warnings = payload["observation_markers"][-1]["warnings"]
        self.assertIn("stale_status", warnings)
        self.assertIn("missing_surface_history", warnings)
        self.assertIn("OBSERVATION ", output)
        progress = json.loads([x for x in output.splitlines() if x.startswith("PROGRESS ")][-1][9:])
        self.assertGreater(progress["status_age_seconds"], 50)
        self.assertEqual(progress["view_count"], 16)
        self.assertEqual(progress["source_received_fps"], 24.5)
        self.assertEqual(progress["temperature_c"], 60)
        self.assertFalse(progress["surface_layer_present"])
        self.assertEqual(len([c for c in calls if c[-1].startswith("am start -n ")]), 1)


if __name__ == "__main__":
    unittest.main()
