"""Model-specific transform and landmark provenance contract tests."""
import sys, unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from asset_pipeline.profile import normalize_geometry,project_landmarks,validate_profile

class ProfileTests(unittest.TestCase):
    def spec(self):
        return {'schemaVersion':1,'slug':'ada-wong','sourceSha256':'a'*64,
                'observedFrontAxis':'+X','heightMeters':.24,'neckPivotFraction':.10,
                'frontEvidence':'own-render/right.png','landmarkApproval':'pending'}
    def test_requires_model_specific_evidence(self):
        p=self.spec();p.pop('frontEvidence')
        with self.assertRaises(ValueError):validate_profile(p)
    def test_proper_rotation_uses_own_height(self):
        points=np.array([[3.,-2.,1.],[7.,2.,-1.],[1.,0.,4.]])
        normals=np.tile([1.,0.,0.],(3,1))
        p,n,pivot,scale=normalize_geometry(points,normals,self.spec())
        self.assertAlmostEqual(np.ptp(p[:,1]),.24)
        np.testing.assert_allclose(n,np.tile([0.,0.,1.],(3,1)))
        self.assertAlmostEqual(scale,.06)
        np.testing.assert_allclose(p[0]+pivot,[-.06,-.12,.18])
    def test_no_degenerate_head_or_nan(self):
        for points in (np.zeros((3,3)),np.full((3,3),np.nan)):
            with self.assertRaises(ValueError):normalize_geometry(points,np.ones((3,3)),self.spec())
    def test_camera_and_face_identity_required(self):
        report={'faces':1,'landmarks':[[.5,.25,0]]*478}
        camera={'orthographic_scale':.4,'views':[{'name':'front','center':[.02,0,.03]}]}
        points=project_landmarks(report,camera)
        np.testing.assert_allclose(points[0],[.02,.13])
        report['faces']=0
        with self.assertRaises(ValueError):project_landmarks(report,camera)
        report['faces']=1;camera['views'][0]['name']='right'
        with self.assertRaises(ValueError):project_landmarks(report,camera)

if __name__=='__main__':unittest.main()
