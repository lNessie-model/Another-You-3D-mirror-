"""Read-only, bounded current-state export for the one device in device_profile.

No raw status/XML/dumpsys/log output is persisted. CLI never starts/stops the app,
changes HOME, installs a package, or reads camera images, recordings or avatars.
"""
import argparse
import hashlib
import json
import math
import queue
import re
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
import xml.etree.ElementTree as ET

from device_profile import ADB, SERIAL

PACKAGE = "com.mirror.bench"
STATUS = "files/mirror-runtime-status.json"
SETTINGS = "shared_prefs/mirror-runtime.xml"
SELECTION = "files/avatars/state.json"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
HASH = r"[0-9a-f]{64}"


class ExportError(RuntimeError):
    """A sanitized error; remote stdout/stderr is intentionally never echoed."""


@dataclass(frozen=True)
class Limits:
    command_seconds: float = 15
    total_seconds: float = 120
    total_bytes: int = 512 * 1024 * 1024
    apk_bytes: int = 256 * 1024 * 1024


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def require(condition, message):
    if not condition:
        raise ExportError(message)


class Reader:
    def __init__(self, command, limits):
        self.command = command
        self.limits = limits
        self.started = time.monotonic()
        self.bytes = 0

    def remaining(self):
        left = self.limits.total_seconds - (time.monotonic() - self.started)
        require(left > 0, "Collection time limit exceeded")
        return left

    def run(self, label, args, limit=16 * 1024, sink=None):
        """Drain both pipes with bounded chunks; kill on time, byte or stderr failure."""
        timeout = min(self.limits.command_seconds, self.remaining())
        try:
            process = subprocess.Popen([*self.command, "-s", SERIAL, *args],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except OSError:
            raise ExportError(label + ": cannot execute pinned ADB") from None
        events = queue.Queue(maxsize=4)
        stopped = threading.Event()

        def drain(pipe, stream):
            try:
                while not stopped.is_set():
                    chunk = pipe.read(65536)
                    while not stopped.is_set():
                        try:
                            events.put((stream, chunk), timeout=.05)
                            break
                        except queue.Full:
                            pass
                    if not chunk:
                        return
            finally:
                pipe.close()

        workers = [threading.Thread(target=drain, args=(process.stdout, "out"), daemon=True),
                   threading.Thread(target=drain, args=(process.stderr, "err"), daemon=True)]
        for worker in workers:
            worker.start()
        result = bytearray()
        received = 0
        done = set()
        deadline = time.monotonic() + timeout
        try:
            while len(done) != 2:
                left = deadline - time.monotonic()
                require(left > 0, label + ": timed out")
                try:
                    stream, chunk = events.get(timeout=min(.1, left))
                except queue.Empty:
                    continue
                if not chunk:
                    done.add(stream)
                    continue
                self.bytes += len(chunk)
                require(self.bytes <= self.limits.total_bytes, "Collection byte limit exceeded")
                require(stream != "err", label + ": ADB reported an error")
                received += len(chunk)
                require(received <= limit, label + ": output size limit exceeded")
                if sink is None:
                    result.extend(chunk)
                else:
                    sink.write(chunk)
            require(process.wait(timeout=max(.001, deadline - time.monotonic())) == 0,
                    label + ": ADB exited unsuccessfully")
            return bytes(result) if sink is None else received
        except subprocess.TimeoutExpired:
            raise ExportError(label + ": timed out") from None
        finally:
            stopped.set()
            if process.poll() is None:
                process.kill()
            process.wait()
            for worker in workers:
                worker.join(timeout=.2)

    def shell(self, label, command, limit=16 * 1024):
        data = self.run(label, ["shell", command], limit)
        try:
            return data.decode("utf-8").strip()
        except UnicodeError:
            raise ExportError(label + ": invalid UTF-8") from None

    def stat(self, path, private=False):
        # Reject symlinks at each component; only callers' allowlisted paths reach here.
        components = [str(parent) for parent in Path(path).parents if str(parent) not in (".", "\\", "/")]
        components = [value.replace("\\", "/") for value in components] + [path]
        links = " || ".join("[ -L " + shlex.quote(value) + " ]" for value in components)
        script = (f"if {links}; then echo INVALID; "
                  f"elif [ ! -e {shlex.quote(path)} ]; then echo MISSING; "
                  f"elif [ ! -f {shlex.quote(path)} ]; then echo INVALID; "
                  f"else stat -c '%s %Y' {shlex.quote(path)}; fi")
        command = "sh -c " + shlex.quote(script)
        if private:
            command = "run-as " + PACKAGE + " " + command
        value = self.shell("File metadata", command, 128)
        if value == "MISSING":
            return dict(exists=False)
        match = re.fullmatch(r"([0-9]{1,12}) ([0-9]{1,12})", value)
        require(match is not None, "File metadata is invalid or path is not a regular file")
        return dict(exists=True, size_bytes=int(match[1]), mtime_epoch_seconds=int(match[2]))

    def private_file(self, path, maximum, required=False):
        require(path in (STATUS, SETTINGS, SELECTION), "Private path is outside the export allowlist")
        started = utc_now()
        before = self.stat(path, private=True)
        meta = dict(path=path, **before, read_started_utc=started)
        if not before["exists"]:
            require(not required, "Runtime status is missing; open MirrorActivity before exporting")
            meta["read_finished_utc"] = utc_now()
            return None, meta
        require(0 < before["size_bytes"] <= maximum, "Private file size is invalid or exceeds its limit")
        data = self.run("Private file read", ["exec-out", "run-as", PACKAGE, "cat", path], maximum)
        require(len(data) == before["size_bytes"], "Private file read length differs from verified size")
        after = self.stat(path, private=True)
        require(after["exists"], "Private file disappeared during collection")
        meta.update(read_finished_utc=utc_now(), metadata_after=after,
                    metadata_changed_during_read=before != after)
        return data, meta

    def clock(self):
        value = self.shell("Device elapsed clock", "cat /proc/uptime", 128)
        require(re.fullmatch(r"[0-9]{1,12}\.[0-9]{1,9} [0-9]{1,12}\.[0-9]{1,9}", value) is not None,
                "Device elapsed clock is invalid")
        return int(Decimal(value.split()[0]) * 1_000_000_000)

    def apk_identity(self):
        text = self.shell("Installed package path", "pm path " + PACKAGE, 4096)
        lines = text.splitlines()
        require(len(lines) == 1 and lines[0].startswith("package:"),
                "Expected one installed base APK; split APKs are unsupported")
        path = lines[0][8:]
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_+=~./-]+/base\.apk", path) is not None
                and all(part not in (".", "..", "") for part in path.split("/")[1:]),
                "Installed APK path is outside the allowlist")
        meta = self.stat(path)
        require(meta["exists"] and 0 < meta["size_bytes"] <= self.limits.apk_bytes,
                "Installed APK is missing, empty or exceeds its size limit")
        info = self.shell("Installed package identity", "dumpsys package " + PACKAGE, 64 * 1024)
        code = re.findall(r"\bversionCode=([0-9]{1,12})\b", info)
        name = re.findall(r"\bversionName=([A-Za-z0-9._+-]{1,80})(?=\s|$)", info)
        require(len(code) == len(name) == 1 and "Package [" + PACKAGE + "]" in info,
                "Installed package version cannot be verified")
        digest = self.shell("Installed APK SHA-256", "sha256sum " + shlex.quote(path), 512)
        require(re.fullmatch(HASH + r"\s+" + re.escape(path), digest) is not None,
                "Installed APK SHA-256 cannot be verified")
        return dict(path=path, size_bytes=meta["size_bytes"], sha256=digest[:64],
                    version_code=int(code[0]), version_name=name[0])


def load_json(data):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON keys are unsupported")
            result[key] = value
        return result
    def nonfinite(_):
        raise ExportError("Nonfinite JSON number")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=unique_pairs,
                           parse_constant=nonfinite)
        require(type(value) is dict, "Expected a JSON object")
        return value
    except (UnicodeError, ValueError, RecursionError):
        raise ExportError("Remote JSON is invalid") from None


def number(value, integer=False, minimum=-1, maximum=2**63-1):
    require(type(value) in ((int,) if integer else (int, float))
            and minimum <= value <= maximum and math.isfinite(value), "Allowlisted numeric field is invalid")
    return value


def boolean(value):
    require(type(value) is bool, "Allowlisted Boolean field is invalid")
    return value


def enum(value, choices):
    require(type(value) is str and value in choices, "Allowlisted enum field is invalid")
    return value


def pattern(value, expression):
    require(type(value) is str and re.fullmatch(expression, value) is not None,
            "Allowlisted identity field is invalid")
    return value


def numeric_summary(value, names):
    require(type(value) is dict, "Allowlisted summary is invalid")
    result = {name: number(value[name]) for name in names.split() if name in value}
    if "error" in value:
        require(type(value["error"]) is str, "Summary error flag is invalid")
        result["error_present"] = bool(value["error"])
    return result


def status_summary(data, clock_before, clock_after, max_age):
    value = load_json(data)
    require(type(value.get("schema_version")) is int and value["schema_version"] == 1,
            "Unsupported runtime status schema")
    result = dict(schema_version=1, runtime_session_id=pattern(value.get("runtime_session_id"), UUID))
    for name in ("runtime_epoch", "status_sequence", "session_started_elapsed_ns", "status_capture_elapsed_ns",
                 "updated_elapsed_ns", "updated_monotonic_ns"):
        result[name] = number(value.get(name), integer=True, minimum=0)
    require(result["session_started_elapsed_ns"] <= result["status_capture_elapsed_ns"] <= result["updated_elapsed_ns"],
            "Runtime status timestamps are inconsistent")
    stamp = result["updated_elapsed_ns"]
    require(clock_before <= clock_after and stamp <= clock_after + 20_000_000,
            "Runtime status clock is invalid or in the future")
    age_upper = max(0, (clock_after + 10_000_000 - stamp) / 1e9)
    require(age_upper <= max_age, "Runtime status is stale; open MirrorActivity and export again")
    result["state"] = enum(value.get("state"), ("WAITING", "ACQUIRING", "INTERACTIVE", "GRACE", "ERROR"))
    if "runtime_stage" in value:
        result["runtime_stage"] = enum(value["runtime_stage"], ("WAITING_FOR_OWNER", "OPENING_CAMERA", "INITIALIZING",
            "CAPTURING", "CONVERTING", "INFERENCING", "REPORTING", "PACING", "RELEASING", "RETRY_WAIT", "STOPPED",
            "PRECHECK_FAILED", "WAITING_FOR_GL"))
    if "input" in value:
        result["input"] = enum(value["input"], ("camera", "replay", "camera_replay"))
    for name in ("face_present", "stored_face_images", "render_fault", "processing_fault", "camera_calibration_open"):
        if name in value:
            result[name] = boolean(value[name])
    for name in ("result_age_ms", "recoveries", "analysis_active_target_fps", "analysis_idle_target_fps",
                 "view_width", "view_height", "view_count", "configured_view_count", "control_revision", "control_rejected_frames"):
        if name in value:
            result[name] = number(value[name])
    for section, names in {
        "metrics": "elapsed_s converted_frames submitted_frames completed_frames face_frames analysis_fps face_fps face_fraction conversion_mean_ms reference_conversion_mean_ms normalization_mean_ms normalized_frames submission_mean_ms inference_completion_mean_ms received_to_completed_mean_ms landmarks blendshapes retained_timing_samples",
        "renderer": "frames fps frame_work_mean_ms output_width output_height view_width view_height view_count render_target_fps persistent_fbo_count retained_frame_samples",
        "source": "capture_results received_images capture_fps received_fps pending_frames_replaced capture_failures clip_frames nominal_fps consumed_frames skipped_frames mapped_mib",
        "npu": "max_pending_cpu_jobs detector_calls mesh_calls post_calls crop_total_ms detector_mean_ms mesh_mean_ms post_mean_ms completed_face_latency_mean_ms",
    }.items():
        if section in value:
            result[section] = numeric_summary(value[section], names)
    # Free text can contain paths or user data; retain only whether an error was reported.
    for name in ("error", "render_error", "control_error"):
        if name in value:
            require(type(value[name]) is str, "Runtime error flag is invalid")
            result[name + "_present"] = bool(value[name])
    freshness = dict(device_elapsed_before_ns=clock_before, device_elapsed_after_ns=clock_after,
                     age_lower_seconds=max(0, (clock_before - 10_000_000 - stamp) / 1e9),
                     age_upper_seconds=age_upper, max_age_seconds=max_age, clock_precision_seconds=.01)
    return result, freshness


def settings_summary(data):
    try:
        text = data.decode("utf-8")
        require("\x00" not in text and "<!" not in text, "XML declarations with entities or DTDs are unsupported")
        root = ET.fromstring(text)
    except (ET.ParseError, UnicodeError):
        raise ExportError("Saved settings XML is invalid") from None
    require(root.tag == "map", "Saved settings must be an Android preferences map")
    integer = lambda v: number(v, integer=True, minimum=0)
    validators = {
        "schema_version": lambda v: number(v, integer=True, minimum=0, maximum=4),
        "view_count": lambda v: v if type(v) is int and v in (16, 20) else enum(v, ()),
        "active_fps": lambda v: v if type(v) is int and v in (10, 17, 20) else enum(v, ()),
        "view_preset": lambda v: enum(v, ("240x720", "320x576", "400x720", "400x640")),
        "panel_pitch": lambda v: number(v, minimum=1, maximum=4096),
        "panel_tan": lambda v: number(v, minimum=-4, maximum=4),
        "panel_phase": lambda v: number(v, minimum=-1024, maximum=1024),
        "panel_units": lambda v: enum(v, ("PIXELS", "SUBPIXELS")),
        "panel_order": lambda v: enum(v, ("RGB", "BGR")),
        "panel_origin": lambda v: enum(v, ("BOTTOM", "TOP")),
        "panel_reverse": boolean, "camera_reflect_input": boolean, "camera_mirror_interaction": boolean,
        "camera_width": integer, "camera_height": integer, "camera_revision": integer,
        "camera_rotation_degrees": lambda v: v if type(v) is int and v in (0, 90, 180, 270) else enum(v, ()),
        "camera_id": lambda v: pattern(v, r"[A-Za-z0-9_.:-]{0,64}"),
        "camera_fingerprint": lambda v: pattern(v, r"(?:" + HASH + r")?"),
    }
    result = {}
    expected_tags = {name: "string" for name in validators}
    for name in ("schema_version", "active_fps", "view_count", "camera_width", "camera_height", "camera_rotation_degrees"):
        expected_tags[name] = "int"
    expected_tags["camera_revision"] = "long"
    for name in ("panel_pitch", "panel_tan", "panel_phase"):
        expected_tags[name] = "float"
    for name in ("panel_reverse", "camera_reflect_input", "camera_mirror_interaction"):
        expected_tags[name] = "boolean"
    for entry in root:
        name = entry.get("name")
        if name not in validators:
            continue
        require(name not in result and not list(entry), "Saved settings have duplicate or nested fields")
        require(entry.tag == expected_tags[name], "Saved setting XML type is incompatible")
        raw = (entry.text or "") if entry.tag == "string" else entry.get("value")
        try:
            if entry.tag in ("int", "long"):
                raw = int(raw)
            elif entry.tag == "float":
                raw = float(raw)
            elif entry.tag == "boolean":
                require(raw in ("true", "false"), "Saved Boolean setting is invalid")
                raw = raw == "true"
            else:
                require(entry.tag == "string", "Saved setting type is unsupported")
        except (ValueError, TypeError):
            raise ExportError("Saved setting value is invalid") from None
        result[name] = validators[name](raw)
    require(result.get("schema_version") != 4 or "view_count" in result,
            "Schema 4 saved settings require an explicit view count")
    return result


def selection_summary(data):
    value = load_json(data)
    require(type(value.get("version")) is int and value["version"] == 1, "Unsupported avatar selection schema")
    result = dict(version=1)
    for role in ("current", "previous", "candidate"):
        require(role in value, "Avatar selection is incomplete")
        result[role] = None if value[role] is None else pattern(value[role], UUID)
    ids = [identity for identity in result.values() if type(identity) is str]
    require(len(ids) == len(set(ids)), "Avatar selection has overlapping roles")
    return result


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def export_bundle(out, include_apk=False, max_status_age_s=15, *, adb_command=None, limits=None):
    require(1 <= max_status_age_s <= 60, "Maximum status age must be within 1 to 60 seconds")
    out = Path(out)
    require(out.suffix.lower() == ".zip", "Destination must have a .zip extension")
    try:
        destination = out.open("xb")
    except FileExistsError:
        raise ExportError("Destination already exists; choose a new ZIP path") from None
    except OSError:
        raise ExportError("Cannot create destination ZIP; its parent directory must exist") from None
    try:
        reader = Reader(adb_command or [ADB], limits or Limits())
        started = utc_now()
        require(reader.run("Device serial", ["get-serialno"], 128).strip() == SERIAL.encode("ascii"),
                "Pinned device serial does not match")
        device = dict(serial=SERIAL)
        for prop in ("ro.product.model", "ro.product.board", "ro.build.version.release",
                     "ro.build.version.sdk", "ro.build.fingerprint"):
            value = reader.shell("Device identity", "getprop " + prop, 256)
            device[prop] = pattern(value, r"[A-Za-z0-9_ .:/+=,@~()-]{1,200}")
        require(device["ro.build.version.sdk"] == "30" and device["ro.build.version.release"] == "11",
                "This exporter is restricted to the Android 11 device")
        apk = reader.apk_identity()
        entries, sources, statuses = {}, {}, []

        def read_status(name):
            before = reader.clock()
            raw, meta = reader.private_file(STATUS, 256 * 1024, required=True)
            after = reader.clock()
            summary, freshness = status_summary(raw, before, after, max_status_age_s)
            meta["freshness"] = freshness
            sources[name] = meta
            entries[name.replace("_", "-") + ".json"] = json_bytes(summary)
            statuses.append(summary)

        read_status("status_before")
        for name, path, maximum, sanitize, entry in (
            ("settings", SETTINGS, 16 * 1024, settings_summary, "settings.json"),
            ("avatar_selection", SELECTION, 4096, selection_summary, "avatar-selection.json"),
        ):
            raw, meta = reader.private_file(path, maximum)
            meta["freshness_policy"] = "Persisted state observed during collection; no expiry or claim of atomic consistency"
            sources[name] = meta
            if raw is not None:
                entries[entry] = json_bytes(sanitize(raw))

        with tempfile.TemporaryDirectory(prefix="mirror-export-") as temporary:
            local_apk = Path(temporary) / "installed.apk"
            if include_apk:
                with local_apk.open("xb") as stream:
                    count = reader.run("Installed APK read", ["exec-out", "cat", apk["path"]],
                                       reader.limits.apk_bytes, sink=stream)
                require(count == apk["size_bytes"], "Installed APK read length differs from verified size")
                with local_apk.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                require(digest == apk["sha256"], "Installed APK SHA-256 differs from device identity")
            read_status("status_after")
            require(reader.apk_identity() == apk, "Installed package changed during collection; export again")
            left, right = statuses
            session_changed = (left["runtime_session_id"], left["runtime_epoch"]) != (right["runtime_session_id"], right["runtime_epoch"])
            if not session_changed:
                # RuntimeStatusOrder orders capture time first, sequence only on a tie.
                require(right["status_capture_elapsed_ns"] >= left["status_capture_elapsed_ns"],
                        "Runtime capture time regressed within a session")
            identities = {name: dict(size_bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                          for name, data in entries.items()}
            if include_apk:
                identities["apk/installed.apk"] = dict(size_bytes=apk["size_bytes"], sha256=apk["sha256"])
            manifest = dict(schema_version=1, kind="mirror-current-state", package=PACKAGE, device=device,
                            apk=dict(**apk, included=include_apk), sources=sources, entries=identities,
                            privacy=dict(policy="Explicit field allowlist; raw files, error text, events, images, recordings, face coordinates and coefficients omitted"),
                            collection=dict(started_utc=started, finished_utc=utc_now(), atomic=False,
                                            session_changed=session_changed, elapsed_seconds=time.monotonic()-reader.started,
                                            observed_bytes=reader.bytes, max_seconds=reader.limits.total_seconds,
                                            max_bytes=reader.limits.total_bytes,
                                            scope="Two fresh status summaries and persisted-state reads; counters have their own session/window scope; not an endurance report or firmware image"))
            entries["manifest.json"] = json_bytes(manifest)
            require(sum(len(value) for value in entries.values()) + (apk["size_bytes"] if include_apk else 0)
                    <= reader.limits.total_bytes, "ZIP entry byte limit exceeded")
            reader.remaining()
            with destination, zipfile.ZipFile(destination, mode="w", compression=zipfile.ZIP_STORED,
                                             allowZip64=False) as archive:
                for name, data in entries.items():
                    reader.remaining()
                    archive.writestr(name, data)
                if include_apk:
                    with local_apk.open("rb") as source, archive.open("apk/installed.apk", "w") as target:
                        while chunk := source.read(65536):
                            reader.remaining()
                            target.write(chunk)
                reader.remaining()
        return manifest
    except BaseException:
        destination.close()
        out.unlink(missing_ok=True)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path, help="New local ZIP path; parent must exist")
    parser.add_argument("--include-apk", action="store_true", help="Include verified installed base APK for local reinstall")
    parser.add_argument("--max-status-age-seconds", type=float, default=15, help="Freshness bound, 1..60 seconds (default: 15)")
    args = parser.parse_args(argv)
    try:
        manifest = export_bundle(args.out, args.include_apk, args.max_status_age_seconds)
    except (ExportError, OSError, UnicodeError, InvalidOperation) as error:
        # Keep expected failure details actionable; never expose remote payloads or tracebacks.
        print("Export failed: " + (str(error) if isinstance(error, ExportError) else "local I/O or encoding error"), file=sys.stderr)
        return 1
    print("Created " + str(args.out.resolve()) + "; session_changed=" + str(manifest["collection"]["session_changed"]).lower())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
