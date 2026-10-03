"""Isolated RKNN 1.3 lower-optimization experiment; never loads a device runtime."""
import argparse
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import platform
import sys
import time
import traceback

EXPECTED_SOURCE_SHA256 = "4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082"


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _toolkit():
    distribution = metadata.distribution("rknn-toolkit2")
    version = distribution.version
    if not version.startswith("1.3.0") or "11912b58" not in version:
        raise RuntimeError("Expected existing RKNN Toolkit2 1.3.0_11912b58, got " + version)
    api = Path(distribution.locate_file("rknn/api/rknn.py"))
    identity = {
        "distribution_version": version,
        "api_path": str(api),
        "api_sha256": sha256(api),
    }
    from rknn.api import RKNN
    return RKNN, identity


def run_attempt(source, output_root, level):
    if type(level) is not int or level not in (0, 1, 2):
        raise ValueError("optimization level must be explicitly 0, 1 or 2")
    source, output_root = Path(source).resolve(), Path(output_root).resolve()
    directory = output_root / ("level-" + str(level))
    directory.mkdir(parents=True, exist_ok=False)  # Never reuse/overwrite any previous attempt.
    config = {"target_platform": "rk3566", "float_dtype": "float16", "optimization_level": level}
    report = {
        "schema_version": 1, "status": "running", "started_utc": utc_now(),
        "pid": os.getpid(), "python_executable": sys.executable,
        "python_version": platform.python_version(), "platform": platform.platform(),
        "script_sha256": sha256(Path(__file__)), "source_path": str(source),
        "expected_source_sha256": EXPECTED_SOURCE_SHA256, "config": config,
        "build_config": {"do_quantization": False}, "steps": {},
        "scope": "Compilation only. Source topology unchanged; Android/runtime compatibility, accuracy and performance unverified.",
        "android_validated": False, "accuracy_validated": False, "performance_validated": False,
    }
    started = time.monotonic()
    compiler = None

    def save():
        temporary = directory / "report.json.tmp"
        temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        temporary.replace(directory / "report.json")

    save()
    try:
        data = source.read_bytes()
        report["source_sha256"] = hashlib.sha256(data).hexdigest()
        report["source_bytes"] = len(data)
        if report["source_sha256"] != EXPECTED_SOURCE_SHA256:
            raise ValueError("Unexpected source SHA256; refusing a different model")
        snapshot = directory / "face_blendshapes.tflite"
        snapshot.write_bytes(data)
        factory, report["toolkit"] = _toolkit()
        save()
        compiler = factory(verbose=True, verbose_file=str(directory / "compile.log"))
        artifact = directory / "face_blendshapes.rknn"
        operations = (
            ("config", lambda: compiler.config(**config)),
            ("load", lambda: compiler.load_tflite(model=str(snapshot))),
            ("build", lambda: compiler.build(**report["build_config"])),
            ("export", lambda: compiler.export_rknn(str(artifact))),
        )
        for step, operation in operations:
            report["active_step"] = step
            save()
            step_start = time.monotonic()
            code = operation()
            report["steps"][step] = {"return_code": code, "elapsed_s": time.monotonic() - step_start}
            save()
            if code != 0:
                raise RuntimeError(step + " returned " + str(code))
        if not artifact.is_file() or artifact.stat().st_size == 0:
            raise RuntimeError("Export returned success without a nonempty model")
        report["artifact"] = {"path": str(artifact), "bytes": artifact.stat().st_size,
                              "sha256": sha256(artifact)}
        report["status"] = "compiled"
    except BaseException as error:
        report["status"] = "error"
        report["error"] = type(error).__name__ + ": " + str(error)
        report["traceback"] = traceback.format_exc()
        traceback.print_exc()
    finally:
        if compiler is not None:
            try:
                compiler.release()
            except BaseException as error:
                report["cleanup_error"] = type(error).__name__ + ": " + str(error)
                report["cleanup_traceback"] = traceback.format_exc()
                report["status"] = "error"
                traceback.print_exc()
        report["finished_utc"] = utc_now()
        report["elapsed_s"] = time.monotonic() - started
        report.pop("active_step", None)
        save()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--optimization-level", required=True, type=int, choices=(0, 1, 2))
    args = parser.parse_args()
    report = run_attempt(args.source, args.output_root, args.optimization_level)
    print(json.dumps({key: report[key] for key in ("status", "config", "elapsed_s")}), flush=True)
    return 0 if report["status"] == "compiled" else 1


if __name__ == "__main__":
    sys.exit(main())
