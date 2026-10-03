"""Actual merged artifact invariants; no ADB and no generation calls."""
from pathlib import Path
import json,sys,unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb

ROOT=Path(r'E:\tripo\assets\mirror-models-20261003')
class CombinedArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=ROOT/'extra-heads-v1/sukuna-rig-stage2-v4';cls.output=ROOT/'combined-v1/sukuna-gothic'
        cls.h=Glb(cls.source/'character.glb');cls.g=Glb(cls.output/'character.glb')
    def test_head_geometry_morph_and_texture_bytes_unchanged(self):
        size=self.h.doc['buffers'][0]['byteLength']
        self.assertEqual(self.h.raw[self.h.start:self.h.start+size],self.g.raw[self.g.start:self.g.start+size])
        self.assertEqual(self.h.doc['meshes'][0],self.g.doc['meshes'][0]);self.assertEqual(self.h.doc['images'],self.g.doc['images'])
        self.assertEqual(self.h.doc['textures'],self.g.doc['textures'])
    def test_background_is_static_sibling_and_does_not_follow_head(self):
        nodes=self.g.doc['nodes'];self.assertEqual(len(nodes),len(self.h.doc['nodes'])+1)
        self.assertIn(len(nodes)-1,nodes[0]['children']);self.assertEqual(nodes[1],self.h.doc['nodes'][1])
        bg=self.g.doc['meshes'][nodes[-1]['mesh']]['primitives'][0];self.assertNotIn('targets',bg)
        self.assertNotIn('baseColorTexture',self.g.doc['materials'][bg['material']]['pbrMetallicRoughness'])
        p=self.g.read(bg['attributes']['POSITION']);n=self.g.read(bg['attributes']['NORMAL']);colour=self.g.read(bg['attributes']['COLOR_0'])
        self.assertAlmostEqual(float(np.ptp(p[:,1])),.4,places=6);self.assertLess(float(p[:,2].max()),-.12)
        np.testing.assert_allclose(np.linalg.norm(n,axis=1),1,atol=1e-6)
        self.assertTrue(((colour>=0)&(colour<=1)).all());np.testing.assert_array_equal(colour[:,3],np.ones(len(colour)))
    def test_all_facial_bindings_preserved(self):
        h=json.loads((self.source/'avatar.json').read_text('utf-8'));g=json.loads((self.output/'avatar.json').read_text('utf-8'))
        for key in ['schemaVersion','inputSchema','coordinates','rig','bindings','derivedBindings','normalPolicy','requiredRigFeatures']:
            self.assertEqual(h[key],g[key])

if __name__=='__main__':unittest.main()
