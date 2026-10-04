"""Geometric requirements for actual retopology, independent of a specific head."""
import unittest,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from head_surface_topology import FrontSurface,cut_convex_hole

class SurfaceTests(unittest.TestCase):
    def setUp(self):
        self.xy=np.array([[-1,-1],[1,-1],[1,1],[-1,1]],float)
        z=np.full((4,1),.5);linear=(3*self.xy[:,0]-2*self.xy[:,1]+5)[:,None]
        self.attrs=np.c_[self.xy,z,linear];self.ids=np.array([[0,1,2],[0,2,3]])
    def test_front_most_surface_and_interpolated_attributes(self):
        back=self.attrs.copy();back[:,2]=-.5;back[:,3]+=100
        sampler=FrontSurface(np.r_[self.attrs,back],np.r_[self.ids,self.ids+4])
        for x,y in [(.1,.2),(-.7,.4),(0,0)]:np.testing.assert_allclose(sampler.sample(x,y),[x,y,.5,3*x-2*y+5],atol=1e-12)
    def test_hole_area_winding_and_all_attributes(self):
        outline=np.array([[-.3,-.3],[.3,-.3],[.3,.3],[-.3,.3]])
        a,t,r=cut_convex_hole(self.attrs,self.ids,outline)
        xyz=a[t,:3];cross=np.cross(xyz[:,1]-xyz[:,0],xyz[:,2]-xyz[:,0])
        self.assertTrue((cross[:,2]>0).all());self.assertAlmostEqual(cross[:,2].sum()*.5,4-.36,places=10)
        np.testing.assert_allclose(a[:,3],3*a[:,0]-2*a[:,1]+5,atol=1e-12)
        self.assertEqual(r['intersected_source_triangles'],2)
        # No output triangle may intersect the removed interior, including one whose center is outside.
        inner=np.array([[-.25,-.25],[.25,-.25],[.25,.25],[-.25,.25]])
        _,_,again=cut_convex_hole(a,t,inner);self.assertEqual(again['intersected_source_triangles'],0)
    def test_back_faces_and_remote_triangles_are_preserved(self):
        a=self.attrs.copy();a[:,2]=-.5
        out,t,r=cut_convex_hole(a,self.ids,np.array([[-.3,-.3],[.3,-.3],[.3,.3],[-.3,.3]]))
        np.testing.assert_array_equal(out,a);np.testing.assert_array_equal(t,self.ids);self.assertEqual(r['removed_interior_polygons'],0)
        out,t,_=cut_convex_hole(self.attrs,self.ids,np.array([[3,3],[4,3],[4,4],[3,4]]));np.testing.assert_array_equal(t,self.ids)
    def test_triangle_crossing_front_threshold_is_cut_only_in_front(self):
        a=self.attrs.copy();a[:,2]=a[:,0]
        outline=np.array([[-.5,-.5],[.5,-.5],[.5,.5],[-.5,.5]])
        out,faces,report=cut_convex_hole(a,self.ids,outline,front_z=0)
        xyz=out[faces,:3];cross=np.cross(xyz[:,1]-xyz[:,0],xyz[:,2]-xyz[:,0])
        self.assertAlmostEqual(np.linalg.norm(cross,axis=1).sum()*.5,(4-.5)*np.sqrt(2),places=10)
        np.testing.assert_allclose(out[:,2],out[:,0],atol=1e-12)
        np.testing.assert_allclose(out[:,3],3*out[:,0]-2*out[:,1]+5,atol=1e-12)
        sampler=FrontSurface(out,faces)
        np.testing.assert_allclose(sampler.sample(-.25,0)[:3],[-.25,0,-.25],atol=1e-12)
        with self.assertRaises(ValueError):sampler.sample(.25,0)
        self.assertEqual(report['intersected_source_triangles'],2)

if __name__=='__main__':unittest.main()
