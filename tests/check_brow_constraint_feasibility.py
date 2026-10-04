"""Audit the measured barycentric constraints and the hard-edge infeasibility."""
import argparse,json,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb

p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);p.add_argument('--carrier',required=True);p.add_argument('--out',required=True);a=p.parse_args()
root=Path(a.evidence);data=json.loads((root/'constraint-feasibility.json').read_text('utf-8'));z=np.load(root/'constraint-system.npz')
c=Glb(Path(a.carrier)/'character.glb');pr=c.doc['meshes'][0]['primitives'][0];pivot=np.array(next(n['translation'] for n in c.doc['nodes'] if n['name']=='Head'))
vertices=c.read(pr['attributes']['POSITION']).astype(float)+pivot;tri=c.read(pr['indices']).reshape(-1,3)
assert np.array_equal(vertices,z['vertices']) and np.array_equal(tri,z['triangles'])
field_rows=z['a'];targets=z['b'];free=z['free'];lookup=np.full(len(vertices),-1);lookup[free]=np.arange(len(free))
point_errors=[]
for i,row in enumerate(data['constraints']):
    ids=np.array(row['vertices']);weights=np.array(row['barycentric']);assert np.array_equal(ids,tri[row['carrierTriangle']])
    reconstructed=np.zeros(len(free))
    for vertex,weight in zip(ids,weights):reconstructed[lookup[vertex]]+=weight
    assert np.array_equal(reconstructed,field_rows[i]) and row['targetMeters']==targets[i]
    err=float(np.linalg.norm(weights@vertices[ids]-np.array(row['world'])))
    assert err<2e-7,(row['label'],err);point_errors.append(err)
orbit=field_rows[9:];gray=field_rows[:9];coefficients=np.linalg.lstsq(orbit.T,gray.T,rcond=1e-10)[0]
nullgray=gray-(orbit.T@coefficients).T;norm=np.linalg.norm(nullgray,axis=1)
assert norm[3]<1e-10 and norm[6]<1e-10
mandatory=np.array([0,3,4,5,6,8]);aa=np.r_[gray[mandatory],orbit];bb=np.r_[targets[mandatory],targets[9:]]
fit=np.linalg.lstsq(aa,bb,rcond=1e-10)[0];errors=aa@fit-bb
null_fit=np.linalg.lstsq(nullgray[mandatory],targets[mandatory],rcond=1e-9)[0]
null_fit-=orbit.T@np.linalg.lstsq(orbit.T,null_fit,rcond=1e-10)[0]
hard_actual=gray[mandatory]@null_fit;hard_orbit=orbit@null_fit
assert abs(hard_actual[1])<1e-10 and abs(hard_actual[4])<1e-10 and np.max(abs(hard_orbit))<1e-10
report={'auditPassed':True,'candidateAuthored':False,'constraintCounts':{'gray':9,'orbit':128},'maximumMeasuredCarrierPointMismatchMeters':max(point_errors),
        'mandatoryGrayIndices':mandatory.tolist(),'mandatoryGrayTargetMeters':targets[mandatory].tolist(),
        'leastSquaresMandatoryGrayMaximumResidualMeters':float(np.max(abs(errors[:6]))),'leastSquaresOrbitMaximumResidualMeters':float(np.max(abs(errors[6:]))),
        'exactOrbitGrayActualMeters':hard_actual.tolist(),'exactOrbitMaximumResidualMeters':float(np.max(abs(hard_orbit))),
        'forcedZeroGrayIndices':np.flatnonzero(norm<1e-10).tolist(),'grayOrbitalRowspaceResidualNorms':norm.tolist(),
        'scope':'The key measured gray-head rows are linear combinations of fixed orbital-edge rows on the unchanged carrier. Thus every possible continuous field on this carrier gives zero at those gray heads; no optimizer or smoothing choice can satisfy a 7–8 mm lift there.'}
out=Path(a.out);assert not out.exists();out.write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))
