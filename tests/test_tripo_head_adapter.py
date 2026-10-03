"""Geometry/colour contract for the offline Tripo head adapter."""
import unittest
import sys
from pathlib import Path
import tempfile
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import tripo_head_adapter as adapter

class HeadAdapterTests(unittest.TestCase):
    def test_observed_positive_x_front_is_rotated_to_runtime_positive_z(self):
        source=np.eye(3)
        result=adapter.rotate_x_front(source)
        np.testing.assert_allclose(result[0],[0,0,1])
        np.testing.assert_allclose(result[1],[0,1,0])
        np.testing.assert_allclose(result[2],[-1,0,0])
        self.assertAlmostEqual(np.linalg.det(result),1)

    def test_base_texture_values_are_linearised_once(self):
        got=adapter.srgb_to_linear(np.array([0.,.04045,.5,1.]))
        np.testing.assert_allclose(got,[0,.04045/12.92,.21404114,1],atol=1e-7)

    def test_texture_filtering_interpolates_linear_texels(self):
        class Fixture(adapter.Glb):
            def __init__(self):
                self.doc={'materials':[{'pbrMetallicRoughness':{'baseColorTexture':{'index':0}}}], 'textures':[{'source':0}]}
            def read(self,index):return np.array([[.5,.5]])
            def image(self,index):return np.array([[[0,0,0],[1,1,1]],[[0,0,0],[1,1,1]]],dtype=float)
        actual=Fixture().bake_base_colour({'attributes':{'TEXCOORD_0':0},'material':0})
        np.testing.assert_allclose(actual,[[.5,.5,.5,1]])

    def test_exported_glb_preserves_positions_colours_indices_and_pivot(self):
        positions=np.array([[0,0,0],[.1,0,0],[0,.1,0]],dtype=np.float32)
        normals=np.array([[0,0,1]]*3,dtype=np.float32)
        colours=np.array([[.2,.4,.6,1]]*3,dtype=np.float32)
        pivot=np.array([0,-.03,0])
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'head.glb'
            adapter.write_glb(path,positions,normals,colours,np.array([[0,1,2]]),head_pivot=pivot)
            actual=adapter.Glb(path);primitive=actual.doc['meshes'][0]['primitives'][0]
            np.testing.assert_array_equal(actual.read(primitive['attributes']['POSITION']),positions)
            np.testing.assert_array_equal(actual.read(primitive['attributes']['COLOR_0']),colours)
            np.testing.assert_array_equal(actual.read(primitive['indices']).ravel(),[0,1,2])
            np.testing.assert_allclose(actual.doc['nodes'][1]['translation'],pivot)
            self.assertFalse(any(k in actual.doc for k in ('skins','textures','images','animations')))
            self.assertEqual(actual.doc['materials'][0]['pbrMetallicRoughness']['metallicFactor'],0)

if __name__=='__main__':unittest.main()
