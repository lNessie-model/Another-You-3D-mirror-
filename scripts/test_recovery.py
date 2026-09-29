"""Recovery regressions: a lost ADB reply must not exclude a changed package."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import device_profile as profile


class RecoveryTests(unittest.TestCase):
    def test_dropped_disable_reply_is_journaled_and_restorable(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            package=dict(package='com.Jupiter.PhotoFrame101',original_enabled=0,applied=False)
            journal=dict(serial=profile.SERIAL,previous_home='com.softwinner.launcher/.Launcher',packages=[package])
            def dropped_reply(*args):
                persisted=json.loads((root/'cleanup-journal.json').read_text(encoding='utf-8'))
                self.assertTrue(persisted['packages'][0]['attempted'])
                self.assertFalse(persisted['packages'][0]['applied'])
                raise RuntimeError('ADB disconnected after device accepted the command')
            with patch.object(profile,'OUT',root), patch.object(profile,'adb',side_effect=dropped_reply):
                with self.assertRaises(RuntimeError): profile.mark_and_disable(journal,package)
            calls=[]
            def recovered_adb(*args):
                calls.append(args)
                return 'com.softwinner.launcher/.Launcher' if 'resolve-activity' in args[-1] else 'Success'
            with patch.object(profile,'OUT',root),patch.object(profile,'adb',side_effect=recovered_adb):
                profile.restore()
            self.assertIn(('shell','pm default-state --user 0 com.Jupiter.PhotoFrame101'),calls)
            self.assertIn(('shell','cmd package set-home-activity --user 0 com.softwinner.launcher'),calls)
            self.assertTrue((root/'cleanup-restored.txt').exists())

    def test_factory_home_cannot_be_guessed_from_a_chooser(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'baseline-inventory.txt').write_text('No verified original launcher',encoding='utf-8')
            with patch.object(profile,'OUT',root),patch.object(profile,'adb',return_value='com.softwinner.launcher'):
                with self.assertRaises(RuntimeError): profile.factory_home_from_inventory()


if __name__=='__main__': unittest.main()
