"""Sequential comparisons; retain native output and full face outputs in every case."""
import subprocess
import sys
from pathlib import Path

root=Path(__file__).resolve().parents[1]
out=Path('E:/tripo/output/multiview-optimization/20261001')
cases=[('v16-native-shared-mv',16,.5),('v20-width300-gpu',20,.25),('v24-width240-gpu',24,.2)]
for name,views,width_scale in cases:
    command=[sys.executable,str(root/'scripts/run_case.py'),name,'--delegate','GPU','--input','replay',
             '--render','scene','--views',str(views),'--scale','.5','--width-scale',str(width_scale),
             '--analysis-fps','10','--seconds','35','--pipeline','--cull','--preblend','--multiview',
             '--explicit-lod','--conversion-backend','rga','--pitch','10','--tan','.2777777',
             '--output-dir',str(out)]
    print('CASE',name,flush=True)
    subprocess.run(command,cwd=root,check=True)
