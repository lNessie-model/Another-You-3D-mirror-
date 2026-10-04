"""User pitch/tan performance comparison; pitch units remain an explicit assumption."""
import subprocess,sys,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
out=Path('E:/tripo/output/hardware-optimization/20261001')
cases=[
    ('camera-convert-native-rga',['--input','camera','--delegate','NONE','--convert','--native-packing','--render','none']),
    ('panel10-replay-gpu4-baseline',['--analysis-fps','10','--views','4']),
    ('panel10-replay-gpu4-pipeline',['--analysis-fps','10','--views','4','--pipeline']),
    ('panel10-replay-cpu4-pipeline',['--analysis-fps','10','--views','4','--pipeline','--delegate','CPU']),
    ('panel10-replay-gpu8-pipeline',['--analysis-fps','10','--views','8','--pipeline']),
    ('panel10-camera-gpu4-pipeline-controlled',['--input','camera','--native-packing','--face-input','portrait','--analysis-fps','10','--views','4','--pipeline']),
]
for name,extra in cases:
    print('START',name,flush=True)
    command=[sys.executable,'-X','utf8',str(root/'scripts/run_case.py'),name,'--input','replay','--delegate','GPU',
             '--seconds','30','--scale','.5','--render','scene','--conversion-backend','rga',
             '--pitch','10','--tan','.2777777','--pitch-units','pixels','--output-dir',str(out),*extra]
    process=subprocess.run(command,cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace')
    (out/(name+'-host-log.txt')).write_text(process.stdout+process.stderr,encoding='utf-8')
    if process.returncode: raise RuntimeError(name+': '+process.stdout+process.stderr)
    data=json.loads((out/(name+'.json')).read_text(encoding='utf-8'))
    result=data['result']
    print(json.dumps(dict(name=name,face_fps=result.get('effective_fps'),face_fraction=result.get('face_fraction'),
                          conversion_ms=result.get('conversion',{}).get('mean_ms'),
                          render_fps=result.get('render',{}).get('fps'),presentation=data.get('presentation'),system=data['system'])),flush=True)
