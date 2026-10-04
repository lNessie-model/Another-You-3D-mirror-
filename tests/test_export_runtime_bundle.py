"""Exercise the exporter with a host fake ADB process; no connected device is used."""
import dataclasses
import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import export_runtime_bundle as bundle


FAKE_ADB = r'''
import hashlib, json, os, sys, time
from pathlib import Path
config = json.loads(Path(os.environ["BUNDLE_FAKE_CONFIG"]).read_text())
args = sys.argv[1:]
assert args[:2] == ["-s", "6L32552009566714"]
args = args[2:]
with open(os.environ["BUNDLE_FAKE_TRACE"], "a") as trace:
    trace.write(json.dumps(args) + "\n")
status = dict(schema_version=1, runtime_session_id="11111111-1111-1111-1111-111111111111",
              runtime_epoch=1, status_sequence=5, session_started_elapsed_ns=90_000_000_000,
              status_capture_elapsed_ns=99_000_000_000, updated_elapsed_ns=99_000_000_000,
              updated_monotonic_ns=99_000_000_000, state="INTERACTIVE", input="camera",
              face_present=True, stored_face_images=False, runtime_stage="CAPTURING",
              metrics=dict(completed_frames=25, landmarks=478, blendshapes=52),
              npu=dict(detector_calls=3, detector=dict(coordinates=[1,2,3])),
              face_coordinates=[[.1,.2,.3]], coefficients=[.5]*52,
              error="private canary", events=[dict(error="private canary")],
              renderer=dict(frames=52, fps=30, vertices=[.1,.2], error="private canary"))
status.update(config.get("status", {}))
apk = b"PK\x03\x04fake-installed-apk-binary"
files = {
 "files/mirror-runtime-status.json": json.dumps(status).encode(),
 "shared_prefs/mirror-runtime.xml": b'<map><int name="schema_version" value="3"/><int name="active_fps" value="17"/><string name="view_preset">320x576</string><float name="panel_pitch" value="10"/><string name="private_field">private canary</string></map>',
 "files/avatars/state.json": b'{"version":1,"current":null,"previous":null,"candidate":null}',
 "/data/app/com.mirror.bench-test/base.apk": apk,
}
for key, value in config.get("files", {}).items():
    files[key] = None if value is None else value.encode()
if args == ["get-serialno"]:
    print(config.get("serial", "6L32552009566714")); sys.exit()
command = args[-1] if args[0] == "shell" else ""
if config.get("sleep") and "cat /proc/uptime" in command:
    time.sleep(config["sleep"])
if config.get("flood") and "cat /proc/uptime" in command:
    sys.stdout.buffer.write(b"x" * config["flood"]); sys.exit()
if command == "cat /proc/uptime": print("100.00 30.00")
elif command.startswith("getprop "):
    prop=command.split()[1]
    print({"ro.product.model":"YS-L6", "ro.product.board":"rk3566", "ro.build.version.release":"11", "ro.build.version.sdk":"30", "ro.build.fingerprint":"vendor/product/build:11/test:user/release-keys"}[prop])
elif command == "pm path com.mirror.bench":
    print("package:" + config.get("apk_path", "/data/app/com.mirror.bench-test/base.apk"))
elif command == "dumpsys package com.mirror.bench":
    print("Package [com.mirror.bench]\n versionCode=1 minSdk=26 targetSdk=30\n versionName=1.0\n unrelated=private canary")
elif "sha256sum " in command:
    print(("0"*64 if config.get("wrong_hash") else hashlib.sha256(apk).hexdigest())+"  /data/app/com.mirror.bench-test/base.apk")
elif "stat -c" in command:
    path = next((path for path in files if path in command), None)
    if path is None: sys.exit(8)
    content=files[path]
    if content is None: print("MISSING")
    elif config.get("bad_stat") == path: print("not-stat")
    else: print(str(len(content))+" 1700000000")
elif args[0] == "exec-out":
    path=args[-1]
    if config.get("exec_error") == path:
        # adb exec-out can exit zero while run-as/cat prints an error to stdout.
        print("cat: private canary: No such file or directory")
    elif config.get("exec_exit") == path:
        sys.stdout.buffer.write(files[path]); sys.exit(1)
    else:
        content=files[path]
        if path == "files/mirror-runtime-status.json" and (config.get("session_change") or config.get("sequence_regression")):
            counter=Path(os.environ["BUNDLE_FAKE_TRACE"]+".counter")
            count=int(counter.read_text()) if counter.exists() else 0
            counter.write_text(str(count+1))
            if count and config.get("session_change"):
                status["runtime_session_id"]="22222222-2222-2222-2222-222222222222"
            if count and config.get("sequence_regression"):
                status.update(status_sequence=4, status_capture_elapsed_ns=99_500_000_000,
                              updated_elapsed_ns=99_500_000_000)
            content=json.dumps(status).encode()
        sys.stdout.buffer.write(content)
else: sys.exit(9)
'''


class ExportBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fake = self.root / "fake_adb.py"
        self.fake.write_text(FAKE_ADB, encoding="utf-8")
        self.config = self.root / "config.json"
        self.trace = self.root / "trace.jsonl"
        self.out = self.root / "current.zip"
        self.env = patch.dict(os.environ, {"BUNDLE_FAKE_CONFIG": str(self.config),
                                          "BUNDLE_FAKE_TRACE": str(self.trace)})
        self.env.start()
        self.addCleanup(self.env.stop)

    def export(self, config=None, **kwargs):
        self.config.write_text(json.dumps(config or {}), encoding="utf-8")
        return bundle.export_bundle(self.out, adb_command=[sys.executable, str(self.fake)], **kwargs)

    def read(self):
        with zipfile.ZipFile(self.out) as archive:
            return {name: archive.read(name) for name in archive.namelist()}

    def fails_cleanly(self, config, **kwargs):
        with self.assertRaises(bundle.ExportError):
            self.export(config, **kwargs)
        self.assertFalse(self.out.exists())
        self.assertFalse(any(p.suffix in (".part", ".apk") for p in self.root.iterdir()))

    def test_default_exports_only_sanitized_current_state_and_hash_manifest(self):
        self.export()
        entries = self.read()
        self.assertEqual(set(entries), {"manifest.json", "status-before.json", "status-after.json",
                                        "settings.json", "avatar-selection.json"})
        manifest = json.loads(entries["manifest.json"])
        self.assertEqual(manifest["apk"]["sha256"], hashlib.sha256(b"PK\x03\x04fake-installed-apk-binary").hexdigest())
        self.assertFalse(manifest["apk"]["included"])
        self.assertFalse(manifest["collection"]["atomic"])
        self.assertFalse(manifest["collection"]["session_changed"])
        joined = b"".join(entries.values())
        for private in (b"private canary", b'"face_coordinates"', b'"coefficients"', b'"vertices"'):
            self.assertNotIn(private, joined)
        self.assertEqual(json.loads(entries["status-after.json"])["metrics"]["landmarks"], 478)
        self.assertEqual(json.loads(entries["status-after.json"])["npu"], {"detector_calls": 3})
        for name, identity in manifest["entries"].items():
            self.assertEqual(identity["sha256"], hashlib.sha256(entries[name]).hexdigest())
        commands = self.trace.read_text().lower()
        for mutation in ("install", "force-stop", "push", "pull", "pm clear", "reboot"):
            self.assertNotIn(mutation, commands)
        self.assertNotIn('["exec-out", "cat", "/data/app/', commands)

    def test_include_apk_copies_installed_bytes_and_verifies_sha(self):
        self.export(include_apk=True)
        entries = self.read()
        manifest = json.loads(entries["manifest.json"])
        self.assertTrue(manifest["apk"]["included"])
        self.assertEqual(hashlib.sha256(entries["apk/installed.apk"]).hexdigest(), manifest["apk"]["sha256"])
        self.out.unlink()
        self.fails_cleanly({"wrong_hash": True}, include_apk=True)

    def test_missing_optional_persisted_files_are_explicit_without_invented_defaults(self):
        self.export({"files": {"shared_prefs/mirror-runtime.xml": None, "files/avatars/state.json": None}})
        entries = self.read()
        self.assertNotIn("settings.json", entries)
        self.assertNotIn("avatar-selection.json", entries)
        sources = json.loads(entries["manifest.json"])["sources"]
        self.assertFalse(sources["settings"]["exists"])
        self.assertFalse(sources["avatar_selection"]["exists"])

    def test_missing_or_corrupt_status_and_zero_exit_cat_error_are_rejected(self):
        for config in ({"files": {"files/mirror-runtime-status.json": None}},
                       {"files": {"files/mirror-runtime-status.json": "not JSON"}},
                       {"exec_error": "files/mirror-runtime-status.json"},
                       {"exec_exit": "files/mirror-runtime-status.json"},
                       {"status": {"schema_version": 2}},
                       {"status": {"schema_version": True}},
                       {"status": {"metrics": {"landmarks": [1, 2, 3]}}},
                       {"status": {"face_present": [.1,.2]}}):
            with self.subTest(config=config): self.fails_cleanly(config)

    def test_corrupt_settings_selection_and_unsafe_installed_path_are_rejected(self):
        for config in ({"files": {"shared_prefs/mirror-runtime.xml": "<map>"}},
                       {"files": {"shared_prefs/mirror-runtime.xml": '<!DOCTYPE map [<!ENTITY x "private canary">]><map><string name="camera_id">&x;</string></map>'}},
                       {"files": {"shared_prefs/mirror-runtime.xml": '<map><long name="active_fps" value="17"/></map>'}},
                       {"files": {"files/avatars/state.json": '{"version":1,"current":"../../private","previous":null,"candidate":null}'}},
                       {"apk_path": "/data/app/../../private/base.apk"},
                       {"bad_stat": "files/avatars/state.json"}):
            with self.subTest(config=config): self.fails_cleanly(config)

    def test_wrong_serial_and_replay_mode_follow_source_contract(self):
        self.fails_cleanly({"serial": "another-device"})
        self.export({"status": {"input": "camera_replay"}})
        self.assertEqual(json.loads(self.read()["status-after.json"])["input"], "camera_replay")

    def test_stale_or_future_status_cannot_be_labeled_current(self):
        for stamp in (70_000_000_000, 120_000_000_000):
            with self.subTest(stamp=stamp):
                self.fails_cleanly({"status": {"session_started_elapsed_ns": 0,
                                              "status_capture_elapsed_ns": stamp,
                                              "updated_elapsed_ns": stamp}})

    def test_session_change_is_recorded_as_non_atomic(self):
        self.export({"session_change": True})
        manifest = json.loads(self.read()["manifest.json"])
        self.assertTrue(manifest["collection"]["session_changed"])

    def test_status_capture_order_has_precedence_over_sequence_order(self):
        self.export({"sequence_regression": True})
        entries = self.read()
        self.assertLess(json.loads(entries["status-after.json"])["status_sequence"],
                        json.loads(entries["status-before.json"])["status_sequence"])
        self.assertFalse(json.loads(entries["manifest.json"])["collection"]["session_changed"])

    def test_limits_bound_remote_files_output_and_elapsed_time(self):
        small = dataclasses.replace(bundle.Limits(), apk_bytes=10)
        self.fails_cleanly({}, limits=small)
        self.fails_cleanly({"files": {"files/avatars/state.json": "x"*5000}})
        self.fails_cleanly({"flood": 100_000})
        self.fails_cleanly({"sleep": 2}, limits=dataclasses.replace(bundle.Limits(), command_seconds=.2))
        self.fails_cleanly({}, limits=dataclasses.replace(bundle.Limits(), total_seconds=.02))

    def test_existing_destination_is_untouched_before_any_adb(self):
        self.out.write_bytes(b"keep-existing")
        with self.assertRaises(bundle.ExportError): self.export()
        self.assertEqual(self.out.read_bytes(), b"keep-existing")
        self.assertFalse(self.trace.exists())

    def test_schema4_count_settings_real_orchestration_and_strict_boundaries(self):
        data='<map><int name="schema_version" value="4"/><int name="view_count" value="16"/><string name="view_preset">400x640</string><string name="raw_face">private canary</string></map>'
        self.export({"files":{"shared_prefs/mirror-runtime.xml":data},"status":{"view_count":20,"configured_view_count":16}})
        settings=json.loads(self.read()["settings.json"])
        self.assertEqual(settings,dict(schema_version=4,view_count=16,view_preset="400x640"))
        self.assertEqual(json.loads(self.read()["status-after.json"])["configured_view_count"],16)
        self.assertEqual(json.loads(self.read()["status-after.json"])["view_count"],20)
        self.out.unlink()
        self.export({"files":{"shared_prefs/mirror-runtime.xml":data.replace('name="view_count" value="16"','name="view_count" value="20"')}})
        self.assertEqual(json.loads(self.read()["settings.json"])["view_count"],20)
        for bad in ('<int name="view_count" value="17"/>','<string name="view_count">16</string>','<boolean name="view_count" value="true"/>','<long name="view_count" value="16"/>'):
            with self.subTest(bad=bad):
                self.out.unlink(missing_ok=True)
                self.fails_cleanly({"files":{"shared_prefs/mirror-runtime.xml":'<map><int name="schema_version" value="4"/>'+bad+'</map>'}})
        self.out.unlink(missing_ok=True)
        self.fails_cleanly({"files":{"shared_prefs/mirror-runtime.xml":'<map><int name="schema_version" value="4"/></map>'}})
        self.out.unlink(missing_ok=True)
        self.fails_cleanly({"files":{"shared_prefs/mirror-runtime.xml":'<map><int name="schema_version" value="5"/><int name="view_count" value="16"/></map>'}})

    def test_new_legal_400640_settings_are_exported_without_defaulting_old_values(self):
        data=b'<map><int name="schema_version" value="3"/><string name="view_preset">400x640</string></map>'
        self.export({"files":{"shared_prefs/mirror-runtime.xml":data.decode()}})
        self.assertEqual(json.loads(self.read()["settings.json"])["view_preset"],"400x640")

    def test_cli_refuses_existing_destination_with_nonzero_exit_and_actionable_error(self):
        self.out.write_bytes(b"keep-existing")
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = bundle.main(["--out", str(self.out), "--include-apk"])
        self.assertEqual(result, 1)
        self.assertIn("Destination already exists", errors.getvalue())
        self.assertEqual(self.out.read_bytes(), b"keep-existing")
        self.assertFalse(self.trace.exists())

    def test_interrupted_zip_write_cleans_only_own_new_output(self):
        with patch.object(zipfile.ZipFile, "writestr", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt): self.export()
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
