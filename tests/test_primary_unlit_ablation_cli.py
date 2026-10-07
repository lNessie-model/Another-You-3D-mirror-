"""Host-only CLI and actual launch/resume checks; all Android calls are fakes."""
import contextlib
import io
import sys
import unittest
from unittest.mock import patch

import test_runtime_metrics as metrics

runtime = metrics.runtime


class PrimaryUnlitAblationCliTests(unittest.TestCase):
    def parse(self, *flags):
        argv = ["collector", "--name", "triangle", "--input", "replay",
                "--seconds", "60", "--output-dir", "unused-offline", *flags]
        with patch.object(sys, "argv", argv):
            return runtime.arguments()

    def test_default_keeps_existing_options(self):
        args = self.parse()
        self.assertFalse(args.primary_unlit_ablation)
        self.assertFalse(args.triangle_tangent)
        self.assertIsNone(args.view_count)
        self.assertIsNone(args.view_preset)
        self.assertIsNone(args.active_target_fps)
        self.assertFalse(args.avatar_synchronous)
        self.assertFalse(args.avatar_batched)
        self.assertFalse(args.cached_camera_vp)
        self.assertFalse(args.gpu_profile)
        # Existing experiments remain legal without the new flag.
        self.assertTrue(self.parse("--avatar-synchronous").avatar_synchronous)
        self.assertTrue(self.parse("--gpu-profile").gpu_profile)

    def test_explicit_joint_workload_does_not_enable_other_modes(self):
        args = self.parse("--primary-unlit-ablation", "--view-count", "16",
                          "--view-preset", "400x640", "--active-target-fps", "31",
                          "--persistent-fbos", "--npu-blendshapes", "--checkpoint-seconds", "0")
        self.assertTrue(args.primary_unlit_ablation)
        self.assertEqual(args.view_count, 16)
        self.assertEqual(args.view_preset, "400x640")
        self.assertEqual(args.active_target_fps, 31)
        self.assertEqual(args.input, "replay")
        self.assertEqual(args.seconds, 60)
        self.assertEqual(args.checkpoint_seconds, 0)
        self.assertTrue(args.persistent_fbos)
        self.assertTrue(args.npu_blendshapes)
        for name in ("avatar_synchronous", "avatar_batched", "cached_camera_vp", "gpu_profile",
                     "orm_rg8", "specialized_batch", "constant_white_primary", "reuse_group_uniforms",
                     "srgb_views", "empty_interlace", "pbr_fast_math", "private_head"):
            self.assertFalse(getattr(args, name), name)

    def test_requires_explicit_sixteen_before_any_adb(self):
        for flags in ((), ("--view-count", "20")):
            with self.subTest(flags=flags), patch.object(runtime, "adb") as adb, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                self.parse("--primary-unlit-ablation", *flags)
            self.assertEqual(failure.exception.code, 2)
            adb.assert_not_called()

    def test_conflicts_and_non_boolean_values_fail_before_any_adb(self):
        conflicts = [(flag,) for flag in (
            "--avatar-synchronous", "--avatar-batched", "--cached-camera-vp", "--gpu-profile",
            "--orm-rg8", "--specialized-batch", "--constant-white-primary", "--reuse-group-uniforms",
            "--srgb-views", "--empty-interlace", "--pbr-fast-math", "--private-head",
            "--static-background-cache", "--triangle-tangent", "--gpu-triangle-tangent")]
        conflicts += [("--avatar-batched", "--specialized-batch", "--constant-white-primary",
                       "--reuse-group-uniforms"), ("false",), ("true",)]
        for flags in conflicts:
            with self.subTest(flags=flags), patch.object(runtime, "adb") as adb, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                self.parse("--primary-unlit-ablation", "--view-count", "16", *flags)
            self.assertEqual(failure.exception.code, 2)
            adb.assert_not_called()

    def test_actual_launch_and_home_resume_only_add_the_single_extra(self):
        commands = {}
        for enabled in (False, True):
            original = runtime.run

            def run(args):
                args.primary_unlit_ablation = enabled
                return original(args)

            with patch.object(runtime, "run", side_effect=run):
                payload, calls = metrics.RuntimeMetricsTests().offline_collection(
                    False, view_count=16, view_preset="400x640", active_target_fps=31,
                    persistent_fbos=True, npu_blendshapes=True)
            launches = [call[-1] for call in calls if call[-1].startswith("am start ")
                        and runtime.COMPONENT in call[-1]]
            self.assertEqual(len(launches), 2)
            self.assertTrue(any("0x20020000" in command for command in launches))
            for command in launches:
                self.assertEqual(command.count(" --ez test_primary_unlit_ablation true"), int(enabled))
                self.assertIn("--ei test_view_count 16", command)
                self.assertIn("--es runtime_input replay", command)
                self.assertNotIn("test_avatar_synchronous", command)
                self.assertNotIn("test_avatar_batched", command)
            self.assertIs(payload["arguments"]["primary_unlit_ablation"], enabled)
            if enabled:
                self.assertTrue(payload["diagnostic_material_ablation"]["changes_lighting"])
                self.assertFalse(payload["diagnostic_material_ablation"]["quality_candidate"])
                self.assertFalse(payload["diagnostic_material_ablation"]["counts_toward_full_goal"])
            else:
                self.assertNotIn("diagnostic_material_ablation", payload)
            commands[enabled] = launches
        self.assertEqual(commands[False], [command.replace(" --ez test_primary_unlit_ablation true", "")
                                           for command in commands[True]])

    def test_legacy_namespace_still_omits_extra(self):
        payload, calls = metrics.RuntimeMetricsTests().offline_collection(False)
        self.assertNotIn("primary_unlit_ablation", payload["arguments"])
        self.assertFalse(any("test_primary_unlit_ablation" in call[-1] for call in calls))


if __name__ == "__main__":
    unittest.main()
