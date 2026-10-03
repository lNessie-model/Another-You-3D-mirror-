import unittest
import numpy as np
from static_view_mesh import visible_subset


class StaticViewMeshTest(unittest.TestCase):
    def test_preserves_side_faces_and_bounds_while_removing_away_faces(self):
        p=np.array([[-1,-1,0],[1,-1,0],[0,1,0],[-1,-1,-1],[1,-1,-1],[0,1,-1]],dtype=float)
        ids=np.array([[0,1,2],[3,5,4],[0,3,2]])
        n=np.tile([0,0,1],(6,1));c=np.ones((6,4))
        q,_,_,result,report=visible_subset(p,n,c,ids,np.eye(4).T.reshape(-1))
        self.assertEqual(report['removed_triangles'],1)
        self.assertEqual(len(result)//3,2)
        np.testing.assert_array_equal(q.min(axis=0),p.min(axis=0))
        np.testing.assert_array_equal(q.max(axis=0),p.max(axis=0))

    def test_uniform_fit_changes_camera_space_and_invalid_fit_rejected(self):
        p=np.array([[-1,-1,0],[1,-1,0],[0,1,0]],dtype=float)
        n=np.tile([0,0,1],(3,1));c=np.ones((3,4));ids=np.array([[0,1,2]])
        f=np.eye(4);f[:3,:3]*=2;f[2,3]=4
        with self.assertRaisesRegex(ValueError,'No camera-facing'):
            visible_subset(p,n,c,ids,f.T.reshape(-1))
        f[1,1]=3
        with self.assertRaisesRegex(ValueError,'uniform'):
            visible_subset(p,n,c,ids,f.T.reshape(-1))

    def test_keeps_face_visible_at_only_one_extreme_eye_and_rejects_bad_indices(self):
        p=np.array([[0,-1,0],[0,1,0],[0,0,1]],dtype=float)
        n=np.tile([1,0,0],(3,1));c=np.ones((3,4));ids=np.array([[0,1,2]])
        _,_,_,result,report=visible_subset(p,n,c,ids,np.eye(4).T.reshape(-1))
        self.assertEqual(report['removed_triangles'],0)
        self.assertEqual(len(result),3)
        with self.assertRaisesRegex(ValueError,'indices'):
            visible_subset(p,n,c,np.array([[0,1,-1]]),np.eye(4).T.reshape(-1))
        with self.assertRaisesRegex(ValueError,'Nonfinite'):
            bad=np.eye(4);bad[2,2]=np.nan
            visible_subset(p,n,c,ids,bad.T.reshape(-1))


if __name__=='__main__':unittest.main()
