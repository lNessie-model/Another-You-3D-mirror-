"""Run workloads sequentially on one board; never overlap benchmark processes."""
import argparse
import subprocess
import sys
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--group',choices=['render','face','joint'],required=True)
parser.add_argument('--seconds',type=int,default=30)
args=parser.parse_args()
script=Path(__file__).with_name('run_case.py')
cases=[]
if args.group=='render':
    for mode in ['interlace','scene']:
        for views in [2,4,8,16]:
            cases.append((f'{mode}-{views}-full', ['--delegate','NONE','--render',mode,'--views',str(views)]))
    for views in [8,16]:
        cases.append((f'scene-{views}-half',['--delegate','NONE','--render','scene','--views',str(views),'--scale','.5']))
elif args.group=='face':
    for delegate in ['CPU','GPU']:
        cases.append((f'face-{delegate.lower()}',['--delegate',delegate]))
    for delegate in ['CPU','GPU']:
        cases.append((f'face-{delegate.lower()}-image',['--delegate',delegate,'--running-mode','IMAGE']))
else:
    for delegate in ['CPU','GPU']:
        cases.append((f'joint-{delegate.lower()}-4-full',['--delegate',delegate,'--render','scene','--views','4','--analysis-fps','10']))
        cases.append((f'joint-{delegate.lower()}-8-half',['--delegate',delegate,'--render','scene','--views','8','--scale','.5','--analysis-fps','10']))
    for delegate in ['CPU','GPU']:
        cases.append((f'joint-{delegate.lower()}-4-half',['--delegate',delegate,'--render','scene','--views','4','--scale','.5','--analysis-fps','10']))
for name, options in cases:
    subprocess.run([sys.executable,'-X','utf8',str(script),name,'--seconds',str(args.seconds),*options],check=True)
