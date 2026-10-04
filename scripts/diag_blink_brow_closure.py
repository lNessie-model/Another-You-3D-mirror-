"""Extra closed-eye combinations omitted by the canonical 19-pose gate."""
import argparse,hashlib,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tests'))
from check_head_ocular import visible_eye,Glb
p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--poses',required=True);p.add_argument('--out',required=True);a=p.parse_args()
g=Glb(Path(a.model)/'character.glb');data=json.loads(Path(a.poses).read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
rows=[]
for pose in data['poses']:
    for view,direction in [('front',[0,0,1]),('right',[.45,0,1]),('left',[-.45,0,1])]:
        rows.append({'pose':pose['name'],'view':view,'eyes':visible_eye(g,pose,direction)})
result={'modelSha256':data['modelSha256'],'passed':all(e['eyeProbes']==0 for row in rows for e in row['eyes']),
        'measurements':rows,'scope':'Actual Java FacePlayback and AvatarRig weights, extra full-blink/inner/outer brow combinations, 2145 orthographic samples per eye/view; no live or all-angle acceptance'}
out=Path(a.out);assert not out.exists();out.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result));raise SystemExit(0 if result['passed'] else 1)
