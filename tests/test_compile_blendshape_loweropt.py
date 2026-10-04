"""Host checks for experiment isolation and truthful compiler outcomes; no RKNN/ADB."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import compile_blendshape_loweropt as experiment


class Compiler:
    def __init__(self, directory, fail=None, release_error=False, empty=False):
        self.directory, self.fail = directory, fail
        self.release_error, self.empty, self.released = release_error, empty, False
        self.configured = None

    def config(self, **config):
        self.configured = config
        return -1 if self.fail == "config" else 0

    def load_tflite(self, **kwargs):
        return -1 if self.fail == "load" else 0

    def build(self, **kwargs):
        return -1 if self.fail == "build" else 0

    def export_rknn(self, path):
        if self.fail == "export":
            return -1
        Path(path).write_bytes(b"" if self.empty else b"test-artifact")
        return 0

    def release(self):
        self.released = True
        if self.release_error:
            raise RuntimeError("release failed")


class ExperimentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.tflite"
        self.source.write_bytes(b"exact source fixture")
        self.digest = hashlib.sha256(self.source.read_bytes()).hexdigest()

    def run_case(self, level=0, **kwargs):
        compiler = Compiler(self.root, **kwargs)
        with patch.object(experiment, "EXPECTED_SOURCE_SHA256", self.digest), \
                patch.object(experiment, "_toolkit", return_value=(
                    lambda **ignored: compiler,
                    {"distribution_version": "1.3.0+11912b58", "test_boundary": True})), \
                contextlib.redirect_stderr(io.StringIO()):
            result = experiment.run_attempt(self.source, self.root / "out", level)
        saved = json.loads((self.root / "out" / ("level-" + str(level)) / "report.json").read_text())
        self.assertEqual(result, saved)
        return result, compiler

    def test_success_records_explicit_level_and_artifact(self):
        report, compiler = self.run_case(2)
        self.assertEqual(report["status"], "compiled")
        self.assertEqual(compiler.configured["optimization_level"], 2)
        self.assertEqual(report["source_sha256"], self.digest)
        self.assertEqual(report["artifact"]["sha256"], hashlib.sha256(b"test-artifact").hexdigest())
        self.assertFalse(report["android_validated"])
        self.assertFalse(report["accuracy_validated"])
        self.assertTrue(compiler.released)

    def test_nonzero_stages_never_claim_success(self):
        for index, stage in enumerate(("config", "load", "build", "export")):
            with self.subTest(stage=stage):
                self.root = Path(self.temp.name) / str(index)
                self.root.mkdir()
                self.source = self.root / "source.tflite"
                self.source.write_bytes(b"exact source fixture")
                report, compiler = self.run_case(fail=stage)
                self.assertEqual(report["status"], "error")
                self.assertEqual(report["steps"][stage]["return_code"], -1)
                self.assertIn(stage, report["error"])
                self.assertNotIn("artifact", report)
                self.assertTrue(compiler.released)

    def test_cleanup_failure_preserves_original_and_is_not_success(self):
        report, _ = self.run_case(fail="build", release_error=True)
        self.assertIn("build", report["error"])
        self.assertIn("release failed", report["cleanup_error"])
        self.assertEqual(report["status"], "error")

    def test_empty_export_is_not_compiled(self):
        report, _ = self.run_case(empty=True)
        self.assertEqual(report["status"], "error")

    def test_release_only_failure_is_not_compiled(self):
        report, _ = self.run_case(release_error=True)
        self.assertEqual(report["status"], "error")
        self.assertIn("release failed", report["cleanup_error"])
        self.assertEqual(report["steps"]["export"]["return_code"], 0)

    def test_toolkit_initialization_failure_is_saved(self):
        with patch.object(experiment, "EXPECTED_SOURCE_SHA256", self.digest), \
                patch.object(experiment, "_toolkit", side_effect=RuntimeError("wrong toolkit")), \
                contextlib.redirect_stderr(io.StringIO()):
            report = experiment.run_attempt(self.source, self.root / "out", 0)
        self.assertEqual(report["status"], "error")
        self.assertIn("wrong toolkit", report["error"])
        saved = json.loads((self.root / "out" / "level-0" / "report.json").read_text())
        self.assertEqual(report, saved)

    def test_existing_output_not_overwritten(self):
        self.run_case()
        path = self.root / "out" / "level-0" / "report.json"
        before = path.read_bytes()
        with self.assertRaises(FileExistsError):
            self.run_case()
        self.assertEqual(path.read_bytes(), before)

    def test_source_mismatch_does_not_load_compiler(self):
        with patch.object(experiment, "_toolkit") as backend, contextlib.redirect_stderr(io.StringIO()):
            report = experiment.run_attempt(self.source, self.root / "out", 0)
        self.assertEqual(report["status"], "error")
        self.assertIn("source SHA256", report["error"])
        backend.assert_not_called()

    def test_reject_invalid_level_before_writes(self):
        for level in (-1, 3, True):
            with self.assertRaises(ValueError):
                experiment.run_attempt(self.source, self.root / "out", level)
        self.assertFalse((self.root / "out").exists())


if __name__ == "__main__":
    unittest.main()
