"""Observe MirrorActivity on the serial pinned by device_profile; imports never run ADB.

Actual presentation evidence uses SurfaceFlinger's second latency column and only
confirmed INTERACTIVE intervals. Callback FPS and session counters are not substitutes.
"""
import argparse
import json
import math
import re
import shlex
import time
from pathlib import Path

from device_profile import SERIAL, SAMPLE_COMMAND, adb, parse_sample, summarize

PACKAGE = "com.mirror.bench"
COMPONENT = PACKAGE + "/.MirrorActivity"
STATUS_FILE = "files/mirror-runtime-status.json"
BOUNDARY_NS = 34_000_000  # One 30 FPS frame, matching run_case's conservative boundary allowance.
JOURNAL_RECORD_BYTES = 1 << 20
JOURNAL_TOTAL_BYTES = 1024 << 20
JOURNAL_RECORDS = 22000
STATUS_STALE_SECONDS = 15  # App reports every 5s; allow the 2s poll cadence and ordinary report latency.


class PollJournal:
    """Exclusive, bounded raw collector journal; flush is not a power-loss guarantee."""
    def __init__(self, path):
        self.stream = path.open("xb")
        self.bytes_written = self.records = 0

    def append(self, row):
        encoded = json.dumps(row, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8") + b"\n"
        if (len(encoded) > JOURNAL_RECORD_BYTES or self.bytes_written + len(encoded) > JOURNAL_TOTAL_BYTES
                or self.records >= JOURNAL_RECORDS):
            raise ValueError("Collector journal size/record budget exceeded")
        if self.stream.write(encoded) != len(encoded):
            raise OSError("Short collector journal write")
        self.stream.flush()
        self.bytes_written += len(encoded)
        self.records += 1

    def close(self):
        self.stream.close()


def poll_progress(point, latest_status, device_clock, layer, history_present, expected_pause):
    """Constant-size observations from this poll and latest accepted status; no online FPS verdict."""
    def number(value):
        return value if type(value) in (int, float) and abs(value) <= 1e15 and math.isfinite(value) else None

    status = latest_status or {}
    renderer = status.get("renderer", {})
    source = status.get("source", {})
    renderer = renderer if isinstance(renderer, dict) else {}
    source = source if isinstance(source, dict) else {}
    updated = status.get("updated_elapsed_ns")
    delta = device_clock - updated if type(device_clock) is int and type(updated) is int else None
    age = delta / 1e9 if delta is not None and abs(delta) <= 1e18 else None
    return dict(status_age_seconds=number(age), status_clock_precedes_update=age is not None and age < 0,
                status_stale_threshold_seconds=STATUS_STALE_SECONDS, clock_precision_seconds=.01,
                status_sequence=number(status.get("status_sequence")), view_count=number(status.get("view_count")),
                renderer_frames=number(renderer.get("frames")), source_received_images=number(source.get("received_images")),
                source_received_fps=number(source.get("received_fps")), source_capture_fps=number(source.get("capture_fps")),
                temperature_c=number(point.get("temperature_c")), latest_sample_host_monotonic_s=number(point.get("host_monotonic_s")),
                surface_layer_present=bool(layer), surface_history_present=bool(history_present), expected_pause=bool(expected_pause))


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


def analyze_gl_context_recovery(statuses, actions, requested):
    """A functional gate only: real callback generations + resumed input progress, not GPU pixels/FPS."""
    result = dict(requested=bool(requested), verified=False, reason="not_requested",
                  waiting_for_new_frame_observed=False,
                  scope="Same Activity HOME/resume, new onSurfaceCreated generation and successful GL submission; "
                        "two fresh complete-face/input progress samples. Not physical presentation or pixel equivalence.")
    if not requested:
        return result
    def fail(reason):
        result["reason"] = reason
        return result
    homes = [a for a in actions if a.get("action") == "home"]
    resumes = [a for a in actions if a.get("action") == "resume"]
    if len(homes) != 1 or len(resumes) != 1:
        return fail("requires_one_completed_home_resume")
    clocks = [a.get(k) for a in (homes[0], resumes[0])
              for k in ("device_elapsed_before_ns", "device_elapsed_after_ns")]
    if not all(type(t) is int for t in clocks) or clocks != sorted(clocks):
        return fail("missing_or_invalid_action_clocks")
    rows = sorted([r for r in statuses if type(r.get("updated_elapsed_ns")) is int],
                  key=lambda r: r["updated_elapsed_ns"])
    before = [r for r in rows if r["updated_elapsed_ns"] <= clocks[0]
              and r.get("renderer", {}).get("runtime_gl_frame_ready") is True]
    after = [r for r in rows if r["updated_elapsed_ns"] >= clocks[2]]
    if not before or not after:
        return fail("missing_before_or_after_status")
    previous = before[-1]
    old_gl = previous.get("renderer", {})
    activity = previous.get("activity_instance_id")
    old_context = old_gl.get("context_generation")
    uuid = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    def identity(value, pattern):
        return isinstance(value, str) and re.fullmatch(pattern, value) is not None
    def release_policy(row):
        p = row.get("gl_context_policy", {})
        return (p.get("release_on_pause_requested") is True and p.get("preserve_on_pause_requested") is False
                and p.get("preserve_on_pause_actual") is False)
    if (not identity(activity, uuid)
            or type(old_context) is not int or old_context < 1
            or old_gl.get("frame_generation") != old_context):
        return fail("missing_initial_context_identity")
    if (not identity(previous.get("runtime_session_id"), uuid)
            or type(previous.get("runtime_epoch")) is not int or previous["runtime_epoch"] < 1
            or not identity(old_gl.get("avatar", {}).get("model_sha256"), r"[0-9a-f]{64}")
            or previous.get("input") not in ("camera", "camera_replay", "replay")
            or old_gl.get("multiview_fbo_actual") not in ("legacy", "persistent_groups")
            or old_gl.get("camera_vp_actual") not in ("per_frame", "cached")
            or any(type(previous.get(k)) is not int or previous[k] < 1 or previous[k] != old_gl.get(k)
                   for k in ("view_count", "view_width", "view_height"))):
        return fail("missing_initial_profile_identity")
    if not release_policy(previous):
        return fail("initial_release_policy_not_confirmed")
    result.update(activity_instance_id=activity, context_before=old_context)
    usable = []
    for r in after:
        gl = r.get("renderer", {})
        if r.get("activity_instance_id") != activity:
            return fail("activity_recreated_or_identity_missing")
        if not release_policy(r):
            return fail("release_policy_not_confirmed")
        if r.get("state") == "ERROR" or any(r.get(k) for k in ("error", "render_error", "control_error", "render_fault", "processing_fault")) or gl.get("error") or r.get("source", {}).get("error"):
            return fail("error_after_resume")
        if r.get("runtime_session_id") == previous.get("runtime_session_id"):
            continue  # A read immediately after am start may still contain the preceding epoch.
        if (not identity(r.get("runtime_session_id"), uuid) or type(r.get("runtime_epoch")) is not int
                or r["runtime_epoch"] <= previous["runtime_epoch"]):
            return fail("invalid_resumed_session_identity")
        if gl.get("runtime_gl_frame_ready") is False and r.get("state") == "WAITING":
            result["waiting_for_new_frame_observed"] = True
        if r.get("state") != "INTERACTIVE":
            continue
        age = r.get("result_age_ms")
        generation = gl.get("context_generation")
        if (gl.get("runtime_gl_frame_ready") is not True or type(generation) is not int
                or generation <= old_context or gl.get("frame_generation") != generation):
            return fail("new_context_frame_not_confirmed")
        if r.get("face_present") is not True or type(age) is not int or not 0 <= age <= 500:
            return fail("fresh_face_not_confirmed")
        if any(r.get(k) != previous.get(k) or gl.get(k) != old_gl.get(k)
               for k in ("view_count", "view_width", "view_height")):
            return fail("view_profile_changed")
        if (gl.get("avatar", {}).get("model_sha256") != old_gl.get("avatar", {}).get("model_sha256")
                or any(gl.get(k) != old_gl.get(k) for k in ("multiview_fbo_actual", "camera_vp_actual"))):
            return fail("render_identity_changed")
        if r.get("input") != previous.get("input") or r.get("metrics", {}).get("landmarks") != 478 or r.get("metrics", {}).get("blendshapes") != 52:
            return fail("complete_input_not_confirmed")
        usable.append(r)
    if len(usable) < 2:
        return fail("insufficient_resumed_progress_samples")
    first, last = usable[0], usable[-1]
    if (first.get("runtime_session_id") != last.get("runtime_session_id")
            or first["renderer"]["context_generation"] != last["renderer"]["context_generation"]):
        return fail("resumed_owner_changed_again")
    counters = [("renderer", "frames"), ("metrics", "completed_frames"), ("metrics", "face_frames"),
                ("npu", "mesh_calls"), ("npu", "post_calls")]
    if last.get("input") in ("camera", "camera_replay"):
        counters.append(("source", "received_images"))
    for section, name in counters:
        left, right = first.get(section, {}).get(name), last.get(section, {}).get(name)
        if type(left) is not int or type(right) is not int or not 0 < left < right:
            return fail("resumed_counter_not_growing:" + section + "." + name)
    result.update(verified=True, reason="verified", context_after=last["renderer"]["context_generation"],
                  runtime_session_before=previous.get("runtime_session_id"), runtime_session_after=last.get("runtime_session_id"),
                  progress_samples=len(usable), first_progress_elapsed_ns=first["updated_elapsed_ns"],
                  last_progress_elapsed_ns=last["updated_elapsed_ns"])
    return result


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
    parser.add_argument("--checkpoint-seconds", type=int, default=60,
                        help="Full JSON checkpoint period 0..3600; 0 disables intermediate full saves and enables exclusive incremental .polls.jsonl")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--blackout-after", type=int, default=0)
    parser.add_argument("--blackout-seconds", type=int, default=0)
    parser.add_argument("--pause-at", type=int)
    parser.add_argument("--pause-seconds", type=int, default=5)
    parser.add_argument("--avatar-synchronous", action="store_true", help="Debug-only same-APK control: deform on the GL thread")
    parser.add_argument("--avatar-batched", action="store_true", help="Debug-only opt-in merged avatar draw calls")
    parser.add_argument("--persistent-fbos", action="store_true", help="Debug-only opt-in persistent multiview FBO groups; defaults to legacy attachments")
    parser.add_argument("--release-gl-on-pause", action="store_true", help="Debug-only release EGL on real HOME/onPause; default preserves existing policy")
    parser.add_argument("--cached-camera-vp", action="store_true", help="Debug-only opt-in static avatar camera matrix cache; defaults to per-frame calculation")
    parser.add_argument("--gpu-profile", action="store_true", help="Debug-only asynchronous GPU timer sampling; defaults off; can perturb driver scheduling")
    parser.add_argument("--orm-rg8", action="store_true", help="Debug-only validated Geralt ORM RG8 upload; defaults off; other models retain RGBA8")
    parser.add_argument("--pbr-fast-math", action="store_true", help="Debug-only highp PBR arithmetic candidate; defaults off; retains full material and geometry")
    parser.add_argument("--npu-blendshapes", action="store_true", help="Debug-only normalized mixed CPU/NPU expression suffix; omission retains MediaPipe CPU 52")
    parser.add_argument("--private-head", action="store_true", help="Debug-only fixed app-private head; does not change stored-avatar selection")
    parser.add_argument("--view-preset", choices=("240x720", "320x576", "400x720", "400x640"), help="Debug-only view size override; never saves preferences")
    parser.add_argument("--view-count", type=int, choices=(16, 20), help="Debug-only count override; omission uses the saved application count, never saves preferences")
    parser.add_argument("--active-target-fps", type=int, choices=(31, 35), help="Debug-only frame pacing control; leaves idle rate and preferences unchanged")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,120}", args.name):
        parser.error("Invalid run name")
    if not 20 <= args.seconds <= 7200:
        parser.error("--seconds must be 20..7200")
    if not 0 <= args.checkpoint_seconds <= 3600:
        parser.error("--checkpoint-seconds must be 0..3600")
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
    markers = payload["observation_markers"] = []
    authorized_device, started, layer = False, None, ""
    seen_status, launch_elapsed = set(), None
    checkpoint_seconds = getattr(args, "checkpoint_seconds", 60)
    journal = None
    if checkpoint_seconds == 0:
        journal_path = destination.with_suffix(".polls.jsonl")
        try:
            if destination.exists():
                raise FileExistsError("Existing full report")
            journal = PollJournal(journal_path)
        except OSError as error:
            print("FAILED journal creation", str(error), flush=True)
            return 1  # No device authorization/actions; preserve all pre-existing evidence.
        payload["poll_journal"] = dict(path=str(journal_path), schema_version=1, failed=False,
                                       record_byte_limit=JOURNAL_RECORD_BYTES, total_byte_limit=JOURNAL_TOTAL_BYTES,
                                       record_limit=JOURNAL_RECORDS, durability="flush; no fsync/power-loss guarantee")
    deltas = dict(samples=samples, statuses=statuses, surface_windows=windows, host_actions=actions,
                  status_reads=reads, surface_probes=probes, collection_errors=failures, observation_markers=markers)
    cursors = {key: 0 for key in deltas}
    journal_failed = False
    latest_status, latest_point = None, {}

    def emit(kind, **extra):
        nonlocal journal_failed
        if journal is None or journal_failed:
            return
        row = dict(kind=kind, host_epoch_s=time.time(), host_monotonic_s=time.monotonic(),
                   collection_status=payload["collection_status"],
                   delta={key: values[cursors[key]:] for key, values in deltas.items()}, **extra)
        try:
            journal.append(row)
        except Exception:
            journal_failed = payload["poll_journal"]["failed"] = True
            raise
        for key, values in deltas.items():
            cursors[key] = len(values)

    def read_status(text, poll_start, reason):
        nonlocal latest_status
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
            if read["result"] in ("new", "duplicate") and statuses:
                latest_status = statuses[-1]
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
            emit("surface_discovery")

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
        emit("final_snapshot", raw_poll=raw, command="; ".join(commands))

    def save():
        payload.update(analyze_run(samples, statuses, windows, actions))
        payload["gl_context_recovery"] = analyze_gl_context_recovery(statuses, actions, args.release_gl_on_pause)
        payload["presentation"]["collection_complete"] = payload["collection_status"] == "completed" and not failures
        if not payload["presentation"]["collection_complete"]:
            payload["presentation"]["meets_30_fps"] = False
            if args.release_gl_on_pause:
                payload["gl_context_recovery"].update(verified=False, reason="collection_incomplete")
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
            emit("action")

    extras = (f" --es runtime_input {args.input} --ei test_blackout_after_s {args.blackout_after}"
              f" --ei test_blackout_duration_s {args.blackout_seconds}")
    if args.avatar_synchronous:
        extras += " --ez test_avatar_synchronous true"
    if args.avatar_batched:
        extras += " --ez test_avatar_batched true"
    if args.persistent_fbos:
        extras += " --ez test_persistent_fbos true"
    if args.cached_camera_vp:
        extras += " --ez test_cached_camera_vp true"
    if getattr(args,"gpu_profile",False):
        extras += " --ez test_gpu_profile true"
    if getattr(args,"orm_rg8",False):
        extras += " --ez test_orm_rg8 true"
    if getattr(args,"pbr_fast_math",False):
        extras += " --ez test_pbr_fast_math true"
    if getattr(args,"npu_blendshapes",False):
        extras += " --ez test_npu_blendshapes true"
    if getattr(args,"private_head",False):
        extras += " --ez test_private_head true"
    if args.release_gl_on_pause:
        extras += " --ez test_release_gl_on_pause true"
    if args.view_preset:
        extras += " --es test_view_preset " + args.view_preset
    if args.view_count is not None:
        extras += " --ei test_view_count " + str(args.view_count)
    if args.active_target_fps is not None:
        extras += " --ei test_active_target_fps " + str(args.active_target_fps)
    try:
        emit("header", metadata={key: payload[key] for key in ("schema_version", "serial", "arguments", "collector_policy", "poll_journal") if key in payload})
        actual_serial = adb("get-serialno")
        emit("serial_read", raw=actual_serial, command=["get-serialno"])
        if actual_serial != SERIAL:
            raise RuntimeError("Wrong device serial")
        authorized_device = True
        raw_paths = adb("shell", "pm path " + PACKAGE)
        emit("package_path_read", raw=raw_paths, command=["shell", "pm path " + PACKAGE])
        paths = raw_paths.splitlines()
        package_path = next((line.split(":", 1)[1] for line in paths if line.startswith("package:")), None)
        if not package_path:
            raise RuntimeError("Mirror APK is not installed")
        raw_checksum = adb("shell", "sha256sum " + shlex.quote(package_path))
        emit("apk_checksum_read", raw=raw_checksum, command=["shell", "sha256sum " + shlex.quote(package_path)])
        checksum = raw_checksum.split()[0]
        if not re.fullmatch(r"[a-fA-F0-9]{64}", checksum):
            raise RuntimeError("Cannot read installed APK checksum")
        payload["apk_sha256"], payload["apk_path"] = checksum.lower(), package_path
        # APK identity alone does not describe ART execution mode. In particular,
        # Android 11 can accept a requested speed compile but use quicken for a
        # debuggable package. Preserve the actual package state, not the request.
        payload["app_package_state_before_run"] = adb("shell", "dumpsys package " + PACKAGE, check=False)
        emit("identity", metadata={key: payload[key] for key in ("apk_sha256", "apk_path", "app_package_state_before_run")})
        action("initial_force_stop", "am force-stop " + PACKAGE)
        action("clear_known_status", f"run-as {PACKAGE} rm -f {STATUS_FILE}")
        # -W waits for the Activity's first drawn window. Capture real startup
        # history while it is still being produced instead of waiting for that.
        action("start", "am start -n " + COMPONENT + extras, clock=True)
        started = time.monotonic()
        payload["collection_start_host_monotonic_s"] = started
        payload["collection_status"] = "running"
        emit("start", metadata=dict(collection_start_host_monotonic_s=started))
        last_memory = last_status = last_layers = last_checkpoint = last_progress = -float("inf")
        last_observation = ((), False)
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
                latest_point = point
            except (AttributeError, IndexError, KeyError, ValueError) as error:
                failures.append(dict(host_monotonic_s=poll_start, scope="system_sample", error=str(error), raw=raw))
            stamps = []
            if queried_layer:
                refresh, stamps = parse_surface_latency(parts.get("SURFACE", ""))
                windows.append(dict(layer=queried_layer, host_monotonic_s=poll_start, device_elapsed_ns=device_elapsed,
                                    refresh_period_ns=refresh, timestamps_ns=stamps, raw=parts.get("SURFACE", "")))
            if status_due:
                read_status(parts.get("STATUS", ""), poll_start, "poll")
                last_status = poll_start
            progress = poll_progress(latest_point, latest_status, device_elapsed, queried_layer, bool(stamps), did_pause and not did_resume)
            warnings = []
            if elapsed >= 5:
                if not queried_layer or not stamps:
                    warnings.append("missing_surface_history")
                if latest_status is None:
                    if elapsed >= STATUS_STALE_SECONDS:
                        warnings.append("status_unavailable")
                elif progress["status_age_seconds"] is None:
                    warnings.append("status_clock_unavailable")
                elif progress["status_age_seconds"] > STATUS_STALE_SECONDS:
                    warnings.append("stale_status")
            observation = (tuple(warnings), progress["expected_pause"])
            if observation != last_observation:
                marker = dict(host_monotonic_s=poll_start, device_elapsed_ns=device_elapsed,
                              warnings=warnings, expected_pause=progress["expected_pause"],
                              unexpected=bool(warnings) and not progress["expected_pause"],
                              status_age_seconds=progress["status_age_seconds"],
                              surface_layer_present=bool(queried_layer), surface_history_present=bool(stamps))
                markers.append(marker)
                print("OBSERVATION " + json.dumps(marker, separators=(",", ":")), flush=True)
                last_observation = observation
            if poll_start - last_progress >= 60:
                print("PROGRESS " + json.dumps(dict(host_monotonic_s=poll_start, elapsed_seconds=elapsed,
                      samples=len(samples), accepted_statuses=len(statuses), status_reads=len(reads), warnings=warnings,
                      **progress), separators=(",", ":")), flush=True)
                last_progress = poll_start
            emit("poll", raw_poll=raw, command="; ".join(commands), host_poll_start_s=poll_start,
                 host_poll_end_s=time.monotonic(), observation=progress)
            # Avoid rewriting an increasingly large two-hour trace every two seconds.
            if checkpoint_seconds and poll_start-last_checkpoint >= checkpoint_seconds:
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
        if journal is not None:
            try:
                emit("end", metadata=dict(collection_end_host_monotonic_s=payload["collection_end_host_monotonic_s"],
                     actual_collection_seconds=payload["actual_collection_seconds"],
                     phase="after_final_stop_before_full_report_save"))
            except Exception as error:
                failures.append(dict(scope="journal_finalize", error=str(error)))
                payload["collection_status"] = "failed"
            try:
                journal.close()
            except Exception as error:
                failures.append(dict(scope="journal_close", error=str(error)))
                payload["collection_status"] = "failed"
                payload["poll_journal"]["failed"] = True
            payload["poll_journal"].update(records=journal.records, bytes_written=journal.bytes_written)
        save()
        print(json.dumps(dict(output=str(destination), status=payload["collection_status"],
                              presentation=payload["presentation"], system=payload["system"]), ensure_ascii=False), flush=True)
    return 0 if payload["collection_status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(run(arguments()))
