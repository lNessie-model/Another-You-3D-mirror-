"""Device checks for invalid configuration, camera permission denial, and safe HOME release.

Only runs against device_profile.SERIAL. Requires CAMERA initially granted without
user-fixed/user-set flags; restores that state in finally. Never clears app data.
"""
import argparse
import json
import re
import shlex
import time
from pathlib import Path

from device_profile import SERIAL, adb

PACKAGE = "com.mirror.bench"
CAMERA = "android.permission.CAMERA"
STATUS = "files/mirror-runtime-status.json"


def run(destination, test_permission=True):
    report = dict(serial=SERIAL, checks=[], status="running")
    authorized = restore_permission = False

    def shell(command):
        result = adb("shell", command)
        if "Exception" in result or "Error:" in result:
            raise RuntimeError(result)
        return result

    def check(name, passed):
        report["checks"].append(dict(name=name, passed=bool(passed)))
        if not passed:
            raise AssertionError(name)

    def launch(extras):
        shell("am force-stop " + PACKAGE)
        shell(f"run-as {PACKAGE} rm -f {STATUS}")
        shell(f"am start -W -n {PACKAGE}/.MirrorActivity " + extras)

    def await_status(predicate, seconds=15):
        deadline, latest = time.monotonic() + seconds, None
        while time.monotonic() < deadline:
            try:
                latest = json.loads(adb("shell", f"run-as {PACKAGE} cat {STATUS}", check=False))
                if predicate(latest):
                    return latest
            except json.JSONDecodeError:
                pass
            time.sleep(.25)
        raise TimeoutError("Expected runtime state missing: " + str({key: latest.get(key) for key in
                           ("state", "error", "runtime_stage")} if latest else None))

    def restore():
        # This factory Android supports grant/revoke, not set/clear-permission-flags.
        # Do not change any flags or answer the permission dialog during the check.
        shell(f"pm grant {PACKAGE} {CAMERA}")

    try:
        check("pinned serial", adb("get-serialno") == SERIAL)
        permissions = shell("dumpsys package " + PACKAGE)
        line = next((row.strip() for row in permissions.splitlines() if CAMERA + ": granted=" in row), "")
        report["permission_before"] = line
        check("initial grant is restorable without overwriting user choices",
              "granted=true" in line and "USER_FIXED" not in line and "USER_SET" not in line)
        authorized = True
        package_path = shell("pm path " + PACKAGE).splitlines()[0].split(":", 1)[1]
        report["apk_sha256"] = shell("sha256sum " + shlex.quote(package_path)).split()[0]

        launch("--es runtime_input invalid-test-input")
        report["invalid_configuration"] = await_status(lambda value: value.get("state") == "ERROR")
        check("invalid input is persisted without an input worker",
              "runtime_input" in report["invalid_configuration"].get("error", "")
              and report["invalid_configuration"].get("runtime_stage") == "STOPPED")

        report["permission_denial_exercised"] = False
        if test_permission:
            shell("am force-stop " + PACKAGE)
            restore_permission = True
            shell(f"pm revoke {PACKAGE} {CAMERA}")
            launch("--es runtime_input camera")
            report["permission_denied"] = await_status(lambda value: value.get("state") == "ERROR")
            check("denied camera is persisted without starting hardware",
                  bool(report["permission_denied"].get("error"))
                  and report["permission_denied"].get("runtime_stage") == "STOPPED")
            report["permission_denial_exercised"] = True
            shell("am force-stop " + PACKAGE)
            restore()
        launch("--es runtime_input camera_replay")
        report["recovered"] = await_status(lambda value: value.get("state") == "INTERACTIVE"
              and value.get("source", {}).get("capture_results", 0) > 0, seconds=30)
        check("granted camera captures USB and completes face output",
              report["recovered"].get("metrics", {}).get("landmarks") == 478
              and report["recovered"].get("metrics", {}).get("blendshapes") == 52
              and not report["recovered"].get("error"))

        report["pid_before_home"] = shell("pidof " + PACKAGE)
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
        time.sleep(2)
        report["pid_after_home"] = shell("pidof " + PACKAGE)
        report["npu_after_home"] = shell("cat /sys/kernel/debug/rknpu/load")
        report["camera_after_home"] = shell("dumpsys media.camera")
        check("HOME release observed without force-stopping the app",
              bool(report["pid_before_home"]) and report["pid_before_home"] == report["pid_after_home"])
        check("HOME stops NPU activity", bool(re.search(r"load:\s*0%", report["npu_after_home"])))
        check("HOME releases camera client", bool(re.search(r"Active Camera Clients:\s*\[\s*\]", report["camera_after_home"])))
        report["status"] = "success"
    except Exception as error:
        report["status"], report["error"] = "error", repr(error)
    finally:
        cleanup_errors = []
        if authorized:
            try:
                shell("am force-stop " + PACKAGE)
            except Exception as error:
                cleanup_errors.append("force_stop: " + repr(error))
        try:
            if restore_permission:
                restore()
            if authorized:
                permissions = shell("dumpsys package " + PACKAGE)
                line = next((row.strip() for row in permissions.splitlines() if CAMERA + ": granted=" in row), "")
                report["permission_after"] = line
                if line != report["permission_before"]:
                    raise RuntimeError("Camera permission restoration differs from initial state")
        except Exception as error:
            cleanup_errors.append("restore_permission: " + repr(error))
        if cleanup_errors:
            report["status"], report["cleanup_error"] = "error", "; ".join(cleanup_errors)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print(json.dumps({key: value for key, value in report.items() if key in
              ("status", "error", "cleanup_error", "checks", "permission_before", "permission_after")}, ensure_ascii=False))
    return report["status"] == "success"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--skip-permission-test", action="store_true",
                        help="Explicitly leave permission denial unverified, e.g. firmware auto-grants it")
    args = parser.parse_args()
    raise SystemExit(0 if run(args.output, not args.skip_permission_test) else 1)
