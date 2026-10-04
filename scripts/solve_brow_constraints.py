"""Metric projection onto linear no-fold inequalities with exact hard constraints."""
import argparse,json,time
from pathlib import Path
import numpy as np

def solve(data,dual_out=None):
    a=data['a'];b=data['b'];q=data['qmat'];rhs=data['rhs'];d=data['areaRows'];u,s,vh=np.linalg.svd(a,full_matrices=True)
    rank=int(np.count_nonzero(s>max(s[0]*1e-10,1e-10)));part=vh[:rank].T@((u[:,:rank].T@b)/s[:rank]);null=vh[rank:].T
    assert np.max(abs(a@part-b))<1e-8
    h=null.T@q@null;z0=np.linalg.solve(h,null.T@(rhs-q@part));w=d@null;c=-.8-d@part
    hinv_w=np.linalg.solve(h,w.T);gram=w@hinv_w;scale=np.sqrt(np.maximum(np.diag(gram),1e-30));gram/=scale[:,None]*scale[None,:]
    residual=(c-w@z0)/scale;lam=np.zeros(len(d));initial=residual.copy();start=time.perf_counter();history=[]
    for i in range(60000):
        violation=np.where(lam>1e-18,abs(residual),np.maximum(residual,0));pick=int(violation.argmax())
        delta=max(0,lam[pick]+residual[pick])-lam[pick]
        if abs(delta)<1e-18:break
        lam[pick]+=delta;residual-=delta*gram[:,pick]
        if i%2500==0:
            fit=part+null@(z0+hinv_w@(lam/scale));ratio=1+d@fit;minimum=float(ratio.min());history.append({'coordinateUpdates':i,'minimumProjectedAreaRatio':minimum,'elapsedSeconds':time.perf_counter()-start})
            print('QP',i,'minRatio',minimum,flush=True)
            if minimum>=.2-1e-7:break
    fit=part+null@(z0+hinv_w@(lam/scale));ratio=1+d@fit
    if dual_out is not None:np.savez(dual_out,w=w,c=c,lambdaOriginal=lam/scale,part=part,null=null,fit=fit)
    report={'feasible':bool(ratio.min()>=.2-1e-7),'minimumProjectedAreaRatio':float(ratio.min()),'hardEqualityResidualMeters':float(np.max(abs(a@fit-b))),
            'coordinateUpdates':i,'elapsedSeconds':time.perf_counter()-start,'history':history,'scope':'Convex metric projection with hard barycentric constraints; no parameter sweep or artistic acceptance.'}
    return fit,report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--system',required=True);p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out);assert not out.exists();out.mkdir();fit,report=solve(np.load(a.system),out/'dual-evidence.npz');np.save(out/'fit.npy',fit);(out/'qp-report.json').write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))
