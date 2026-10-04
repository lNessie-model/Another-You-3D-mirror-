"""The actual device-check finally must restore permissions even when ADB stop fails."""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import run_preflight_check as checks


class Device:
    def __init__(self, fail_after_revoke=False):
        self.granted = True
        self.mode = ""
        self.fail_after_revoke = fail_after_revoke
        self.failed = False
        self.commands = []

    def adb(self, *args, **kwargs):
        if args == ("get-serialno",):
            return checks.SERIAL
        command = args[1]
        self.commands.append(command)
        if command == "dumpsys package " + checks.PACKAGE:
            return checks.CAMERA + ": granted=" + str(self.granted).lower() + ", flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]"
        if command.startswith("pm path "):
            return "package:/data/app/test/base.apk"
        if command.startswith("sha256sum "):
            return "1" * 64 + "  /data/app/test/base.apk"
        if command.startswith("pm revoke "):
            self.granted = False
        if command.startswith("pm grant "):
            self.granted = True
        if command.startswith("am start -W -n "):
            self.mode = command.split("runtime_input ")[1]
            if self.mode == "camera" and self.fail_after_revoke:
                self.failed = True
                raise RuntimeError("Injected launch failure after revoke")
        if command.startswith("am force-stop ") and self.failed:
            raise RuntimeError("Injected cleanup stop failure")
        if command.endswith("cat " + checks.STATUS):
            if self.mode == "invalid-test-input":
                value = dict(state="ERROR", error="Invalid runtime_input", runtime_stage="STOPPED")
            elif self.mode == "camera":
                value = dict(state="ERROR", error="Camera permission missing", runtime_stage="STOPPED")
            else:
                value = dict(state="INTERACTIVE", source=dict(capture_results=1), metrics=dict(landmarks=478, blendshapes=52))
            return json.dumps(value)
        if command.startswith("pidof "):
            return "42"
        if command.endswith("rknpu/load"):
            return "NPU load:  0%"
        if command == "dumpsys media.camera":
            return "Active Camera Clients:\n[]"
        return ""


class PreflightCheckTest(unittest.TestCase):
    def execute(self, device, test_permission=True):
        with tempfile.TemporaryDirectory() as directory, patch.object(checks, "adb", device.adb), \
                patch.object(checks.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            result_path = Path(directory) / "report.json"
            result = checks.run(result_path, test_permission)
            return result, json.loads(result_path.read_text(encoding="utf-8"))

    def test_permission_restores_despite_launch_and_force_stop_failure(self):
        device = Device(True)
        result, report = self.execute(device)
        self.assertFalse(result)
        self.assertTrue(device.granted)
        self.assertIn("launch failure", report["error"])
        self.assertIn("cleanup stop failure", report["cleanup_error"])
        self.assertEqual(report["permission_before"], report["permission_after"])

    def test_success_uses_only_supported_grant_revoke_and_restores_exactly(self):
        device = Device()
        result, report = self.execute(device)
        self.assertTrue(result)
        self.assertTrue(device.granted)
        self.assertEqual(report["permission_before"], report["permission_after"])
        self.assertFalse(any("permission-flags" in command for command in device.commands))
        self.assertTrue(all(row["passed"] for row in report["checks"]))

    def test_skipped_denial_is_explicit_and_does_not_mutate_permissions(self):
        device = Device()
        result, report = self.execute(device, False)
        self.assertTrue(result)
        self.assertFalse(report["permission_denial_exercised"])
        self.assertEqual(report["permission_before"], report["permission_after"])
        self.assertFalse(any(command.startswith(("pm grant ", "pm revoke ")) for command in device.commands))


if __name__ == "__main__":
    unittest.main()
