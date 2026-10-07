"""No Android access: actual collector launch/resume and argparse behavior."""
import contextlib, io, sys, unittest
from unittest.mock import patch
import test_runtime_metrics as metrics
runtime = metrics.runtime

class SrgbCliTests(unittest.TestCase):
    def test_launch_and_resume(self):
        for enabled in (False, True):
            original = runtime.run
            def run(args):
                args.srgb_views = enabled
                return original(args)
            with patch.object(runtime, 'run', side_effect=run):
                payload, calls = metrics.RuntimeMetricsTests().offline_collection(False, view_count=16)
            launches = [c[-1] for c in calls if c[-1].startswith('am start ') and runtime.COMPONENT in c[-1]]
            self.assertEqual(len(launches), 2)
            for launch in launches:
                self.assertEqual('--ez test_srgb_views true' in launch, enabled)
            self.assertEqual(payload['arguments']['srgb_views'], enabled)

    def parse(self, *flags):
        with patch.object(sys, 'argv', ['collector','--name','srgb','--input','replay','--seconds','20','--output-dir','unused-offline',*flags]):
            return runtime.arguments()

    def test_explicit_and_default(self):
        self.assertFalse(self.parse().srgb_views)
        self.assertTrue(self.parse('--srgb-views','--view-count','16').srgb_views)

    def test_dependencies(self):
        for flag in ('--avatar-batched','--orm-rg8','--pbr-fast-math','--empty-interlace','--specialized-batch',
                     '--constant-white-primary','--reuse-group-uniforms','--private-head','--static-background-cache'):
            with self.subTest(flag=flag), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.parse('--srgb-views','--view-count','16',flag)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.parse('--srgb-views','--view-count','20')

if __name__=='__main__': unittest.main()
