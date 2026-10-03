"""Observe MirrorActivity on the serial pinned by device_profile; imports never run ADB.

Actual presentation evidence uses SurfaceFlinger's second latency column and only
confirmed INTERACTIVE intervals. Callback FPS and session counters are not substitutes.
"""
import argparse
import json
import re
import shlex
import time
from pathlib import Path

from device_profile import SERIAL, SAMPLE_COMMAND, adb, parse_sample, summarize

PACKAGE = "com.mirror.bench"
COMPONENT = PACKAGE + "/.MirrorActivity"
STATUS_FILE = "files/mirror-runtime-status.json"
BOUNDARY_NS = 34_000_000  # One 30 FPS frame, matching run_case's conservative boundary allowance.


def surface_layer_candidates(text):
    """Keep actual activity SurfaceViews, excluding SurfaceFlinger's background wrappers.

    Return every real match so callers still reject multiple live activity surfaces.
    """
    return [name for name in text.splitlines()
            if name.startswith("SurfaceView") and PACKAGE in name and "MirrorActivity" in name]


def parse_surface_latency(text):
    lines = text.splitlines()
    refresh = int(lines[0]) if lines and lines[0].strip().isdigit() else None
    stamps = set()
    for line in lines[1:]:
        columns = line.split()
        if len(columns) == 3 and all(value.isdigit() for value in columns):
            actual = int(columns[1])
            if 0 < actual < 2**63 - 1:
                stamps.add(actual)
    return refresh, sorted(stamps)


def subtract_intervals(intervals, excluded):
    result = list(intervals)
    for left, right in sorted(excluded):
        if right <= left:
            continue
        remaining = []
        for start, end in result:
            if right <= start or left >= end:
                remaining.append((start, end))
            else:
                if start < left:
                    remaining.append((start, left))
                if right < end:
                    remaining.append((right, end))
        result = remaining
    return result


def merged_intervals(intervals):
    result = []
    for left, right in sorted(intervals):
        if result and left <= result[-1][1] + BOUNDARY_NS:
            result[-1] = (result[-1][0], max(right, result[-1][1]))
        else:
            result.append((left, right))
    return result


def analyze_presentation(statuses, windows, excluded_intervals=()):
    """Pure metrics. Missing histories are explicitly incomplete, never silently interpolated."""
    rows = sorted((row for row in statuses if isinstance(row.get("updated_monotonic_ns"), int)),
                  key=lambda row: row["updated_monotonic_ns"])
    events, gaps, errors = {}, [], []
    previous_events, previous_updated = None, None
    for row in rows:
        current = {(event.get("monotonic_ns"), event.get("state")) for event in row.get("state_events", [])
                   if isinstance(event.get("monotonic_ns"), int) and isinstance(event.get("state"), str)
                   and event["monotonic_ns"] <= row["updated_monotonic_ns"]}
        if not current:
            gaps.append(dict(kind="missing_state_events", start_ns=previous_updated or row["updated_monotonic_ns"],
                             end_ns=row["updated_monotonic_ns"]))
        elif previous_events is None:
            if min(current)[1] != "WAITING":
                gaps.append(dict(kind="initial_state_history_missing", start_ns=min(current)[0], end_ns=min(current)[0]))
        elif not current.intersection(previous_events):
            first_new = min(current)[0]
            gaps.append(dict(kind="state_event_ring_gap", start_ns=min(previous_updated, first_new), end_ns=first_new))
        if current and max(current)[1] != row.get("state"):
            gaps.append(dict(kind="state_snapshot_mismatch", start_ns=previous_updated or max(current)[0],
                             end_ns=row["updated_monotonic_ns"]))
        for stamp, state in current:
            if stamp in events and events[stamp] != state:
                gaps.append(dict(kind="conflicting_state_events", start_ns=stamp, end_ns=stamp))
            events[stamp] = state
            if state == "ERROR":
                errors.append(dict(monotonic_ns=stamp, source="state", error="ERROR"))
        for source, value in (("runtime", row.get("error")), ("renderer", row.get("renderer", {}).get("error")),
                              ("input", row.get("source", {}).get("error"))):
            if value:
                errors.append(dict(monotonic_ns=row["updated_monotonic_ns"], source=source, error=str(value)))
        if row.get("state") == "ERROR":
            errors.append(dict(monotonic_ns=row["updated_monotonic_ns"], source="status", error="ERROR"))
        previous_events, previous_updated = current, row["updated_monotonic_ns"]

    ordered = sorted(events.items())
    confirmed_end = rows[-1]["updated_monotonic_ns"] if rows else 0
    active = [(stamp, ordered[i + 1][0] if i + 1 < len(ordered) else confirmed_end)
              for i, (stamp, state) in enumerate(ordered) if state == "INTERACTIVE"]
    active = subtract_intervals(active, list(excluded_intervals) + [(gap["start_ns"], gap["end_ns"]) for gap in gaps])
    active = [(start, end) for start, end in active if end > start]
    all_stamps, coverage, layer_spans = set(), [], {}
    for window in windows:
        stamps = sorted(set(value for value in window.get("timestamps_ns", []) if isinstance(value, int) and 0 < value < 2**63 - 1))
        all_stamps.update(stamps)
        if len(stamps) >= 2:
            coverage.append((stamps[0], stamps[-1]))
            layer_spans.setdefault(window.get("layer", "unspecified"), []).append((stamps[0], stamps[-1]))
    coverage = merged_intervals(coverage)
    layers = [(name, merged_intervals(spans)) for name, spans in layer_spans.items()]
    ambiguous = []
    for index, (name, spans) in enumerate(layers):
        for other_name, other_spans in layers[index+1:]:
            for left, right in spans:
                for other_left, other_right in other_spans:
                    for start, end in active:
                        overlap_start, overlap_end = max(left, other_left, start), min(right, other_right, end)
                        if overlap_end-overlap_start > BOUNDARY_NS:
                            ambiguous.append(dict(layers=[name, other_name], start_ns=overlap_start, end_ns=overlap_end))
    intervals, uncovered, frame_intervals, measured_ns = [], [], 0, 0
    for start, end in active:
        stamps = sorted(stamp for stamp in all_stamps if start <= stamp < end)
        holes = [(left, right) for left, right in subtract_intervals([(start, end)], coverage)
                 if right - left > BOUNDARY_NS]
        uncovered.extend(holes)
        duration = stamps[-1] - stamps[0] if len(stamps) >= 2 else 0
        fps = (len(stamps) - 1) * 1e9 / duration if duration else None
        intervals.append(dict(start_ns=start, end_ns=end, duration_s=(end-start)/1e9, presented_frames=len(stamps),
                              presented_fps=fps, sampled_duration_s=duration/1e9, complete_history=not holes and len(stamps) >= 3))
        if duration:
            frame_intervals += len(stamps) - 1
            measured_ns += duration
    fps = frame_intervals * 1e9 / measured_ns if measured_ns and not ambiguous else None
    if ambiguous:
        for item in intervals:
            item["presented_fps"] = None
            item["complete_history"] = False
    complete = bool(intervals) and all(item["complete_history"] for item in intervals) and not gaps and not errors and not ambiguous
    return dict(interactive_presented_fps=fps, interactive_duration_s=sum(end-start for start, end in active)/1e9,
                sampled_duration_s=measured_ns/1e9, intervals=intervals, complete_active_evidence=complete,
                meets_30_fps=bool(complete and fps is not None and fps >= 30 - 1e-6
                                  and all(item["presented_fps"] >= 30 - 1e-6 for item in intervals)),
                fps_target=30, rate_is_lower_bound=bool(uncovered or gaps),
                surface_history_gaps=[dict(start_ns=start, end_ns=end, seconds=(end-start)/1e9) for start, end in uncovered],
                surface_history_gap_seconds=sum(end-start for start, end in uncovered)/1e9,
                state_history_gaps=gaps, status_errors=errors, ambiguous_surface_intervals=ambiguous,
                confirmed_until_monotonic_ns=confirmed_end,
                scope="SurfaceFlinger actual presentation timestamps only within confirmed INTERACTIVE state intervals; "
                      "HOME intervals and unknown state history excluded; idle/acquiring/GRACE excluded; "
                      "no claim about the unconfirmed tail after the last status; 34 ms boundary allowance; "
                      "30 FPS comparison allows only 0.000001 FPS numerical rounding")


def resource_summary(samples, intervals=None):
    groups = [samples] if intervals is None else [[sample for sample in samples
              if start <= sample.get("device_monotonic_ns", -1) < end] for start, end in intervals]
    selected = [sample for group in groups for sample in group]
    if not selected:
        return dict(samples=0, scope="no attributable resource samples")
    result = summarize(selected)
    # Do not create CPU deltas across a pause/idle gap by simply concatenating active samples.
    valid_groups = [group for group in groups if len(group) >= 2]
    pairs = sum(len(group)-1 for group in valid_groups)
    result["cpu_percent_whole_machine"] = (sum(summarize(group)["cpu_percent_whole_machine"] * (len(group)-1)
            for group in valid_groups) / pairs) if pairs else None
    for key, prefix, scale in (("app_pss_kib", "app_pss_mib", 1024), ("npu_busy_percent", "npu_busy_percent", 1)):
        values = [sample[key]/scale for sample in selected if key in sample]
        result[prefix + "_samples"] = len(values)
        if values:
            result[prefix + "_mean"], result[prefix + "_peak"] = sum(values)/len(values), max(values)
    gpu = [int(match[1]) for sample in selected if (match := re.match(r"(\d+)@", sample.get("gpu_load", "")))]
    result["gpu_busy_samples"] = len(gpu)
    if gpu:
        result["gpu_busy_percent_mean"], result["gpu_busy_percent_peak"] = sum(gpu)/len(gpu), max(gpu)
    result["scope"] = "whole collection including startup/idle/HOME" if intervals is None else "samples inside confirmed INTERACTIVE intervals; CPU deltas never bridge excluded intervals"
    return result


def uptime_ns(text):
    match = re.search(r"(?m)^\s*(\d+(?:\.\d+)?)\s+\d+(?:\.\d+)?\s*$", text)
    return round(float(match[1])*1e9) if match else None


def map_clock(elapsed_ns, statuses):
    candidates = [row for row in statuses if isinstance(row.get("updated_elapsed_ns"), int)
                  and isinstance(row.get("updated_monotonic_ns"), int)]
    if elapsed_ns is None or not candidates:
        return None
    nearest = min(candidates, key=lambda row: abs(row["updated_elapsed_ns"]-elapsed_ns))
    return elapsed_ns - (nearest["updated_elapsed_ns"]-nearest["updated_monotonic_ns"])


def pause_exclusions(actions, statuses):
    start, intervals = None, []
    end = max((row.get("updated_monotonic_ns", 0) for row in statuses), default=0)
    for action in actions:
        if action["action"] == "home":
            start = map_clock(action.get("device_elapsed_before_ns"), statuses)
        elif action["action"] == "resume" and start is not None:
            finish = map_clock(action.get("device_elapsed_after_ns"), statuses)
            if finish is not None:
                intervals.append((start, finish)); start = None
    if start is not None and end > start:
        intervals.append((start, end))
    return intervals


def analyze_run(samples, statuses, windows, actions):
    for sample in samples:
        stamp = map_clock(sample.get("device_elapsed_ns"), statuses)
        if stamp is not None:
            sample["device_monotonic_ns"] = stamp
    presentation = analyze_presentation(statuses, windows, pause_exclusions(actions, statuses))
    observed = [sample.get("device_monotonic_ns", 0) for sample in samples]
    for window in windows:
        mapped = map_clock(window.get("device_elapsed_ns"), statuses)
        if mapped is not None:
            observed.append(mapped)
        observed.extend(stamp for stamp in window.get("timestamps_ns", [])
                        if isinstance(stamp, int) and 0 < stamp < 2**63-1)
    observed_end = max(observed, default=0)
    presentation["unconfirmed_tail_seconds"] = max(0, observed_end-presentation["confirmed_until_monotonic_ns"])/1e9
    presentation["observed_until_monotonic_ns"] = observed_end
    presentation["pause_clock_complete"] = all(
            action.get("device_elapsed_before_ns") is not None and action.get("device_elapsed_after_ns") is not None
            for action in actions if action["action"] in ("home", "resume"))
    if not presentation["pause_clock_complete"]:
        presentation["complete_active_evidence"] = presentation["meets_30_fps"] = False
    interactive = [(item["start_ns"], item["end_ns"]) for item in presentation["intervals"]]
    return dict(presentation=presentation, system=resource_summary(samples),
                interactive_system=resource_summary(samples, interactive),
                inference_metrics_scope="Per-status current input session online means/counters are preserved verbatim. "
                                        "No subtraction or aggregation across session reset/recreation.",
                clock_scope="SurfaceFlinger and state events use device CLOCK_MONOTONIC. Resource samples and "
                            "HOME boundaries map /proc/uptime via the nearest status elapsed-minus-monotonic offset; "
                            "uptime has 10 ms precision plus ADB action interval uncertainty.")


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--input", choices=("camera", "replay", "camera_replay"), default="camera")
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--blackout-after", type=int, default=0)
    parser.add_argument("--blackout-seconds", type=int, default=0)
    parser.add_argument("--pause-at", type=int)
    parser.add_argument("--pause-seconds", type=int, default=5)
    parser.add_argument("--avatar-synchronous", action="store_true", help="Debug-only same-APK control: deform on the GL thread")
    parser.add_argument("--avatar-batched", action="store_true", help="Debug-only opt-in merged avatar draw calls")
    parser.add_argument("--persistent-fbos", action="store_true", help="Debug-only opt-in persistent multiview FBO groups; defaults to legacy attachments")
    parser.add_argument("--view-preset", choices=("240x720", "320x576", "400x720"), help="Debug-only view size override; never saves preferences")
    parser.add_argument("--active-target-fps", type=int, choices=(31, 35), help="Debug-only frame pacing control; leaves idle rate and preferences unchanged")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,120}", args.name):
        parser.error("Invalid run name")
    if not 20 <= args.seconds <= 7200:
        parser.error("--seconds must be 20..7200")
    if not 0 <= args.blackout_after <= 3600 or not 0 <= args.blackout_seconds <= 60:
        parser.error("Invalid blackout timing")
    if (args.blackout_after or args.blackout_seconds) and (args.input == "camera" or not args.blackout_seconds
            or args.blackout_after + args.blackout_seconds >= args.seconds):
        parser.error("Blackout requires replay/camera_replay and must end within the run")
    if not 1 <= args.pause_seconds <= 3600 or (args.pause_at is not None
            and not (1 <= args.pause_at and args.pause_at + args.pause_seconds < args.seconds)):
        parser.error("Pause must begin and end within the run")
    return args


def run(args):
    args.output_dir.mkdir(parents=True, exist_ok=True)
    destination = args.output_dir / (args.name + ".json")
    samples, statuses, windows, actions, reads, failures = [], [], [], [], [], []
    payload = dict(schema_version=1, serial=SERIAL, arguments={**vars(args), "output_dir": str(args.output_dir)},
                   samples=samples, statuses=statuses, surface_windows=windows, host_actions=actions,
                   status_reads=reads, collection_errors=failures, collection_status="starting")
    payload["collector_policy"] = "non-waiting launch; discover/query surface in same poll before resource dumps; final snapshot before independent force-stop"
    probes = payload["surface_probes"] = []
    authorized_device, started, layer = False, None, ""
    seen_status, launch_elapsed = set(), None

    def read_status(text, poll_start, reason):
        read = dict(host_monotonic_s=poll_start, reason=reason, raw=text)
        reads.append(read)
        try:
            current = json.loads(text)
            stamp = current.get("updated_elapsed_ns")
            monotonic = current.get("updated_monotonic_ns")
            if (type(stamp) is not int or type(monotonic) is not int
                    or launch_elapsed is None or stamp < launch_elapsed):
                read["result"] = "missing_or_prelaunch_timestamp"
            elif stamp in seen_status:
                read["result"] = "duplicate"
            else:
                current["host_observed_monotonic_s"] = poll_start
                statuses.append(current); seen_status.add(stamp); read["result"] = "new"
        except (json.JSONDecodeError, AttributeError) as error:
            read["result"], read["error"] = "unavailable", str(error)
        return read

    def discover_surface(reason):
        nonlocal layer
        probe = dict(reason=reason, host_monotonic_s=time.monotonic())
        probes.append(probe)
        try:
            probe["raw"] = adb("shell", "dumpsys SurfaceFlinger --list", check=False)
            candidates = surface_layer_candidates(probe["raw"])
            probe["candidates"] = candidates
            layer = candidates[0] if len(candidates) == 1 else ""
            if len(candidates) > 1:
                failures.append(dict(host_monotonic_s=probe["host_monotonic_s"],
                                     scope="ambiguous_surface_layer", layers=candidates, raw=probe["raw"]))
        except Exception as error:
            probe["error"] = str(error)
            raise
        finally:
            probe["host_end_monotonic_s"] = time.monotonic()

    def final_snapshot():
        # Discovery/history/status reads may fail; the caller always executes the
        # independent final force-stop in its own finally block afterwards.
        if not layer:
            discover_surface("pre_stop")
        poll_start = time.monotonic()
        commands = ["printf '\\n__RUNTIME_STATUS__\\n'", f"run-as {PACKAGE} cat {STATUS_FILE}"]
        if layer:
            commands.extend(["printf '\\n__RUNTIME_SURFACE__\\n'",
                             "dumpsys SurfaceFlinger --latency " + shlex.quote(layer)])
        commands.extend(["printf '\\n__RUNTIME_CLOCK__\\n'", "cat /proc/uptime"])
        raw = adb("shell", "; ".join(commands), check=False)
        sections = re.split(r"(?:^|\n)__RUNTIME_(\w+)__\n", raw)
        parts = dict(zip(sections[1::2], sections[2::2]))
        read = read_status(parts.get("STATUS", ""), poll_start, "pre_stop")
        refresh, stamps = parse_surface_latency(parts.get("SURFACE", ""))
        clock = uptime_ns(parts.get("CLOCK", ""))
        windows.append(dict(layer=layer, reason="pre_stop", host_monotonic_s=poll_start,
                            host_poll_end_s=time.monotonic(), device_elapsed_ns=clock,
                            refresh_period_ns=refresh, timestamps_ns=stamps,
                            raw=parts.get("SURFACE", ""), raw_snapshot=raw))
        if not stamps or clock is None or read["result"] not in ("new", "duplicate"):
            failures.append(dict(scope="final_snapshot", error="Incomplete final status/surface/clock snapshot", raw=raw))

    def save():
        payload.update(analyze_run(samples, statuses, windows, actions))
        payload["presentation"]["collection_complete"] = payload["collection_status"] == "completed" and not failures
        if not payload["presentation"]["collection_complete"]:
            payload["presentation"]["meets_30_fps"] = False
        temporary = destination.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        temporary.replace(destination)

    def action(name, command, clock=False, surface_before=None):
        row = dict(action=name, command=command, host_epoch_s=time.time(), host_monotonic_s=time.monotonic())
        actions.append(row)
        try:
            if surface_before is not None:
                # HOME destroys the SurfaceView. Close its history before the command,
                # with the pause clock and HOME in this same shell to avoid an ADB gap.
                commands = []
                if surface_before:
                    commands.extend(["printf '\\n__RUNTIME_SURFACE__\\n'",
                                     "dumpsys SurfaceFlinger --latency " + shlex.quote(surface_before)])
                commands.extend(["printf '\\n__RUNTIME_CLOCK_BEFORE__\\n'", "cat /proc/uptime",
                                 "printf '\\n__RUNTIME_ACTION__\\n'", command,
                                 "printf '\\n__RUNTIME_CLOCK_AFTER__\\n'", "cat /proc/uptime"])
                row["command"] = "; ".join(commands)
                row["raw"] = adb("shell", row["command"])
                # device_profile.adb strips leading whitespace, including the first newline.
                sections = re.split(r"(?:^|\n)__RUNTIME_(\w+)__\n", row["raw"])
                parts = dict(zip(sections[1::2], sections[2::2]))
                row["output"] = parts.get("ACTION", "")
                row["device_elapsed_before_ns"] = uptime_ns(parts.get("CLOCK_BEFORE", ""))
                row["device_elapsed_after_ns"] = uptime_ns(parts.get("CLOCK_AFTER", ""))
                refresh, stamps = parse_surface_latency(parts.get("SURFACE", ""))
                row["surface_layer"] = surface_before
                if surface_before:
                    windows.append(dict(layer=surface_before, reason="pre_home",
                                        host_monotonic_s=row["host_monotonic_s"],
                                        device_elapsed_ns=row["device_elapsed_before_ns"],
                                        refresh_period_ns=refresh, timestamps_ns=stamps,
                                        raw=parts.get("SURFACE", "")))
                if not stamps:
                    failures.append(dict(host_monotonic_s=row["host_monotonic_s"], scope="pre_home_surface",
                                         layer=surface_before, error="No actual presentation history captured before HOME",
                                         raw=parts.get("SURFACE", "")))
            else:
                if clock:
                    row["device_elapsed_before_ns"] = uptime_ns(adb("shell", "cat /proc/uptime"))
                row["output"] = adb("shell", command)
                if clock:
                    row["device_elapsed_after_ns"] = uptime_ns(adb("shell", "cat /proc/uptime"))
            if "Error:" in row["output"] or "Exception" in row["output"]:
                raise RuntimeError(row["output"])
        except Exception as error:
            row["error"] = str(error)
            raise
        finally:
            row["host_end_monotonic_s"] = time.monotonic()
            print(name.upper(), row.get("output", row.get("error", "")), flush=True)

    extras = (f" --es runtime_input {args.input} --ei test_blackout_after_s {args.blackout_after}"
              f" --ei test_blackout_duration_s {args.blackout_seconds}")
    if args.avatar_synchronous:
        extras += " --ez test_avatar_synchronous true"
    if args.avatar_batched:
        extras += " --ez test_avatar_batched true"
    if args.persistent_fbos:
        extras += " --ez test_persistent_fbos true"
    if args.view_preset:
        extras += " --es test_view_preset " + args.view_preset
    if args.active_target_fps is not None:
        extras += " --ei test_active_target_fps " + str(args.active_target_fps)
    try:
        if adb("get-serialno") != SERIAL:
            raise RuntimeError("Wrong device serial")
        authorized_device = True
        paths = adb("shell", "pm path " + PACKAGE).splitlines()
        package_path = next((line.split(":", 1)[1] for line in paths if line.startswith("package:")), None)
        if not package_path:
            raise RuntimeError("Mirror APK is not installed")
        checksum = adb("shell", "sha256sum " + shlex.quote(package_path)).split()[0]
        if not re.fullmatch(r"[a-fA-F0-9]{64}", checksum):
            raise RuntimeError("Cannot read installed APK checksum")
        payload["apk_sha256"], payload["apk_path"] = checksum.lower(), package_path
        # APK identity alone does not describe ART execution mode. In particular,
        # Android 11 can accept a requested speed compile but use quicken for a
        # debuggable package. Preserve the actual package state, not the request.
        payload["app_package_state_before_run"] = adb("shell", "dumpsys package " + PACKAGE, check=False)
        action("initial_force_stop", "am force-stop " + PACKAGE)
        action("clear_known_status", f"run-as {PACKAGE} rm -f {STATUS_FILE}")
        # -W waits for the Activity's first drawn window. Capture real startup
        # history while it is still being produced instead of waiting for that.
        action("start", "am start -n " + COMPONENT + extras, clock=True)
        started = time.monotonic()
        payload["collection_start_host_monotonic_s"] = started
        payload["collection_status"] = "running"
        last_memory = last_status = last_layers = last_checkpoint = -float("inf")
        did_pause, did_resume = False, False
        paused_host = None
        launch_elapsed = actions[-1].get("device_elapsed_before_ns", 0)
        while time.monotonic() - started < args.seconds:
            poll_start = time.monotonic()
            elapsed = poll_start - started
            if args.pause_at is not None and not did_pause and elapsed >= args.pause_at:
                action("home", "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME",
                       clock=True, surface_before=layer)
                did_pause = True
                paused_host = time.monotonic()
            if did_pause and not did_resume and time.monotonic() >= paused_host + args.pause_seconds:
                # Reorder the existing Activity to front; no force-stop during the pause test.
                action("resume", "am start -n " + COMPONENT + " -f 0x20020000" + extras, clock=True)
                did_resume = True
                layer = ""
            memory_due, status_due, layers_due = (poll_start-last_memory >= 5, poll_start-last_status >= 2,
                                                 poll_start-last_layers >= 3 or not layer)
            if layers_due:
                discover_surface("poll")
                last_layers = poll_start
            queried_layer = layer
            commands = []
            if queried_layer:
                commands.extend(["printf '\\n__RUNTIME_SURFACE__\\n'", "dumpsys SurfaceFlinger --latency " + shlex.quote(queried_layer)])
            commands.extend(["printf '\\n__RUNTIME_SYSTEM__\\n'", SAMPLE_COMMAND])
            for label, command in (("NPU", "cat /sys/kernel/debug/rknpu/load"), ("CLOCK", "cat /proc/uptime")):
                commands.extend([f"printf '\\n__RUNTIME_{label}__\\n'", command])
            if memory_due:
                commands.extend(["printf '\\n__RUNTIME_MEM__\\n'", "dumpsys meminfo " + PACKAGE])
            if status_due:
                commands.extend(["printf '\\n__RUNTIME_STATUS__\\n'", f"run-as {PACKAGE} cat {STATUS_FILE}"])
            raw = adb("shell", "; ".join(commands), check=False)
            sections = re.split(r"(?:^|\n)__RUNTIME_(\w+)__\n", raw)
            parts = dict(zip(sections[1::2], sections[2::2]))
            device_elapsed = uptime_ns(parts.get("CLOCK", ""))
            try:
                system = parts.get("SYSTEM", "")
                point = parse_sample(system)
                point.update(host_monotonic_s=poll_start, host_poll_end_s=time.monotonic(),
                             device_elapsed_ns=device_elapsed, raw_system=system, raw_npu=parts.get("NPU", ""))
                if match := re.search(r"(\d+)%", parts.get("NPU", "")):
                    point["npu_busy_percent"] = int(match[1])
                if memory_due:
                    point["raw_app_meminfo"] = parts.get("MEM", "")
                    if match := re.search(r"TOTAL PSS:\s+(\d+)", point["raw_app_meminfo"]):
                        point["app_pss_kib"] = int(match[1])
                samples.append(point)
            except (AttributeError, IndexError, KeyError, ValueError) as error:
                failures.append(dict(host_monotonic_s=poll_start, scope="system_sample", error=str(error), raw=raw))
            if queried_layer:
                refresh, stamps = parse_surface_latency(parts.get("SURFACE", ""))
                windows.append(dict(layer=queried_layer, host_monotonic_s=poll_start, device_elapsed_ns=device_elapsed,
                                    refresh_period_ns=refresh, timestamps_ns=stamps, raw=parts.get("SURFACE", "")))
            if status_due:
                read_status(parts.get("STATUS", ""), poll_start, "poll")
                last_status = poll_start
            # Avoid rewriting an increasingly large two-hour trace every two seconds.
            if poll_start-last_checkpoint >= 60:
                save()
                last_checkpoint = poll_start
            if memory_due:
                last_memory = poll_start
            time.sleep(max(0, 1 - (time.monotonic()-poll_start)))
        payload["collection_status"] = "completed"
        if args.pause_at is not None and not did_resume:
            raise RuntimeError("Requested pause/resume did not finish within the collection window")
    except KeyboardInterrupt:
        payload["collection_status"] = "interrupted"
    except Exception as error:
        payload["collection_status"] = "failed"
        failures.append(dict(scope="collector", error=str(error)))
        print("FAILED", str(error), flush=True)
    finally:
        if authorized_device:
            try:
                if started is not None:
                    final_snapshot()
            except Exception as error:
                failures.append(dict(scope="final_snapshot", error=str(error)))
                payload["collection_status"] = "failed"
            finally:
                try:
                    action("final_force_stop", "am force-stop " + PACKAGE)
                except Exception as error:
                    failures.append(dict(scope="final_force_stop", error=str(error)))
                    payload["collection_status"] = "failed"
        payload["collection_end_host_monotonic_s"] = time.monotonic()
        payload["actual_collection_seconds"] = time.monotonic()-started if started is not None else 0
        save()
        print(json.dumps(dict(output=str(destination), status=payload["collection_status"],
                              presentation=payload["presentation"], system=payload["system"]), ensure_ascii=False), flush=True)
    return 0 if payload["collection_status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(run(arguments()))
