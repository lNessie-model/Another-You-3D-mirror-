"""Sequential fair-rate comparisons; each case retains raw SurfaceFlinger and resource samples."""
import subprocess,sys,json,argparse
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args(); args.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parent
common=['--input','camera','--face-input','replay','--analysis-fps','10','--render','scene','--views','20',
        '--scale','.375','--width-scale','.2','--pipeline','--cull','--preblend','--multiview','--explicit-lod',
        '--pitch','10','--tan','.2777777','--pitch-units','subpixels','--conversion-backend','rga','--native-packing',
        '--output-dir',str(args.output)]
cases=[('gpu-v20-paced-3min','GPU',180,31,False),('npu-v20-paced-3min','RKNN',180,31,False),
       ('npu-v20-ui-5min','RKNN',300,31,True),('npu-v20-capacity-1min','RKNN',60,0,False)]
for name,delegate,duration,fps,ui in cases:
    print('START',name,flush=True)
    command=[sys.executable,'-X','utf8',str(root/'run_case.py'),name,'--delegate',delegate,'--seconds',str(duration),
             '--render-fps',str(fps),*common]
    if ui: command.append('--ui-load')
    with (args.output/(name+'-host.log')).open('w',encoding='utf-8') as log:
        completed=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    if completed.returncode: raise RuntimeError('Benchmark failed; inspect '+name+'-host.log')
    row=json.loads((args.output/(name+'.json')).read_text(encoding='utf-8'))
    result=row['result']; system=row['system']; presentation=row['presentation']
    print(json.dumps(dict(name=name,status=result['status'],render_fps=presentation['presented_fps'],face_fps=result['effective_fps'],
                          face_fraction=result['face_fraction'],gpu=system['gpu_busy_percent_mean'],cpu=system['cpu_percent_whole_machine'],
                          npu=system['npu_busy_percent_mean'],mem_available_min=system['mem_available_mib_min'])),flush=True)
