"""Fit a continuous carrier field to measured gray-brow and orbital constraints."""
import argparse, hashlib, json
from pathlib import Path
import numpy as np
from tripo_head_adapter import Glb
from head_surface_topology import FrontSurface

def projected_bary(surface,xy):
    q=np.asarray(xy)-surface.a;s=np.zeros(len(q));t=s.copy();good=surface.valid
    s[good]=(q[good,0]*surface.c[good,1]-q[good,1]*surface.c[good,0])/surface.det[good]
    t[good]=(surface.b[good,0]*q[good,1]-surface.b[good,1]*q[good,0])/surface.det[good]
    hit=good&(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001);ids=np.flatnonzero(hit);assert len(ids)
    weights=np.c_[1-s-t,s,t];z=(weights*surface.v[:,:,2]).sum(1);face=ids[np.argmax(z[ids])]
    return surface.triangles[face],weights[face],int(face)

def run(source,carrier,gray,out):
    source=Path(source);carrier=Path(carrier);out=Path(out);assert not out.exists();out.mkdir()
    g=Glb(source/'character.glb');c=Glb(carrier/'character.glb');pr=c.doc['meshes'][0]['primitives'][0]
    pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
    vertices=c.read(pr['attributes']['POSITION']).astype(float)+pivot;tri=c.read(pr['indices']).reshape(-1,3);surface=FrontSurface(vertices,tri)
    free=np.flatnonzero((abs(vertices[:,0]-.004)<.070)&(vertices[:,1]>.006)&(vertices[:,1]<.115)&(vertices[:,2]>.040))
    lookup=np.full(len(vertices),-1);lookup[free]=np.arange(len(free));assert len(free)<3000
    rows=[];values=[];details=[]
    probes=json.loads(Path(gray).read_text('utf-8'))['rows']
    desired=[.0015,.0035,.0060,.0075,.0075,.0075,.0075,.0045,.0015];assert len(probes)==9
    def constraint(point,value,label,kind):
        ids,bary,face=projected_bary(surface,point[:2]);row=np.zeros(len(free));cols=lookup[ids];assert ((cols>=0)|(bary<1e-9)).all()
        for col,weight in zip(cols,bary):
            if col>=0:row[col]+=weight
        rows.append(row);values.append(value);details.append({'kind':kind,'label':label,'world':list(point),'carrierTriangle':face,'vertices':ids.tolist(),'barycentric':bary.tolist(),'targetMeters':value})
    for probe,value in zip(probes,desired):constraint(probe['gltfWorld'],value,probe['label'],'gray')
    ring=g.doc['meshes'][0]['primitives'][1];p=g.read(ring['attributes']['POSITION']).astype(float)+pivot
    for side,sign in [('Left',1),('Right',-1)]:
        group=np.flatnonzero((p[:2080,1]>.01)&(sign*p[:2080,0]>.004));assert len(group)==512
        for vertex in group[:64]:constraint(p[vertex],0,side+' orbital cut '+str(vertex),'orbital')
    a=np.array(rows);b=np.array(values);fit,residual,rank,singular=np.linalg.lstsq(a,b,rcond=1e-10)
    fitted=a@fit;error=fitted-b
    for row,actual,err in zip(details,fitted,error):row.update(actualMeters=float(actual),errorMeters=float(err))
    report={'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'carrierModelSha256':hashlib.sha256(c.raw).hexdigest(),
            'constraints':details,'freeCarrierVertices':len(free),'constraintRank':int(rank),'constraintCount':len(rows),
            'maximumGrayResidualMeters':float(np.max(abs(error[:9]))),'maximumOrbitalResidualMeters':float(np.max(abs(error[9:]))),
            'exactConstraintsFeasibleAt50Micrometers':bool(np.max(abs(error))<=5e-5),'scope':'Measured gray-pixel constraints and 128 fixed orbital-edge points on the unchanged continuous pre-socket carrier; feasibility only, not an authored or accepted asset.'}
    (out/'constraint-feasibility.json').write_text(json.dumps(report,indent=2),'utf-8')
    np.savez(out/'constraint-system.npz',a=a,b=b,vertices=vertices,triangles=tri,free=free,minimumNormFit=fit)
    print(json.dumps({k:v for k,v in report.items() if k!='constraints'}));return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--carrier',required=True);p.add_argument('--gray',required=True);p.add_argument('--out',required=True);a=p.parse_args();run(a.source,a.carrier,a.gray,a.out)
