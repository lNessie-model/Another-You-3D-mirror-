"""Cheap mutation regressions for the final asset's early preservation gates."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO.parent
sys.path.insert(0, str(REPO / 'scripts'))
sys.path.insert(0, str(REPO / 'tests'))
import check_dentition_appearance as gate
from head_glb_edit import GlbEdit

ASSETS = ROOT / 'assets/mirror-models-20261003/lowpoly-heads-v2'
ASSET = ASSETS / 'geralt-rig-stage13-dentition-appearance-v3'
SOURCE = ASSETS / 'geralt-rig-stage12-visible-brow-v3-safe'


@unittest.skipUnless(
    all((base / name).is_file() for base, name in (
        (ASSET, 'character.glb'), (ASSET, 'production-poses.json'),
        (ASSET, 'dentition-appearance-authoring.json'), (ASSET, 'avatar.json'),
        (SOURCE, 'character.glb'), (SOURCE, 'avatar.json'))),
    'Frozen production GLB fixtures are unavailable in this checkout',
)
class DentitionAppearanceGateMutationTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix='dental-gate-mutation-', dir=ROOT / 'output')
        self.addCleanup(self.scratch.cleanup)
        self.directory = Path(self.scratch.name).resolve()
        assert self.directory.is_relative_to((ROOT / 'output').resolve())
        self.poses = json.loads((ASSET / 'production-poses.json').read_text('utf-8'))

    def rejected_before_expensive_check(self, asset, poses, message):
        # Execute the real parser and check() path. A mutation must be rejected
        # before expensive ray/rigidity work rather than by this sentinel.
        with patch.object(gate, 'dental_structure', side_effect=RuntimeError('Reached expensive validation')):
            with self.assertRaisesRegex(AssertionError, message):
                gate.check(asset, SOURCE, poses)

    def test_face_material_binding_mutation_is_rejected(self):
        edit = GlbEdit(ASSET / 'character.glb')
        authoring = json.loads((ASSET / 'dentition-appearance-authoring.json').read_text('utf-8'))
        face = edit.doc['meshes'][0]['primitives'][0]
        self.assertNotEqual(face['material'], authoring['dentitionMaterialIndex'])
        face['material'] = authoring['dentitionMaterialIndex']
        model = self.directory / 'character.glb'
        edit.write(model)
        sha = hashlib.sha256(model.read_bytes()).hexdigest()
        authoring['modelSha256'] = sha
        (self.directory / 'dentition-appearance-authoring.json').write_text(json.dumps(authoring), 'utf-8')
        manifest = json.loads((ASSET / 'avatar.json').read_text('utf-8'))
        manifest['modelSha256'] = sha
        (self.directory / 'avatar.json').write_text(json.dumps(manifest), 'utf-8')
        self.poses['modelSha256'] = sha
        poses = self.directory / 'poses.json'
        poses.write_text(json.dumps(self.poses), 'utf-8')
        self.rejected_before_expensive_check(self.directory, poses, 'Primitive binding changed')

    def reject_missing_pose(self, name):
        self.assertEqual(len(self.poses['poses']), 19)
        next(pose for pose in self.poses['poses'] if pose['name'] == name)['name'] = 'omitted-' + name
        poses = self.directory / 'poses.json'
        poses.write_text(json.dumps(self.poses), 'utf-8')
        self.rejected_before_expensive_check(ASSET, poses, 'Required closed-lip poses missing')

    def test_missing_neutral_is_rejected_even_with_19_poses(self):
        self.reject_missing_pose('neutral')

    def test_missing_jaw_open_mouth_close_is_rejected_even_with_19_poses(self):
        self.reject_missing_pose('jaw-open-mouth-close')


if __name__ == '__main__':
    unittest.main(verbosity=2)
