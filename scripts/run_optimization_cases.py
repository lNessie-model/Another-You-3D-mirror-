"""Sequential device runs; never overlap independent benchmarks on the same board."""
import subprocess,sys,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
out=Path('E:/tripo/output/hardware-optimization/20261001')
cases=[
    ('replay-rga-4-pipeline-10fps',['--conversion-backend','rga','--pipeline','--render','scene','--views','4','--analysis-fps','10']),
    ('replay-lut-4-pipeline-10fps',['--lookup','--verify-lookup','--pipeline','--render','scene','--views','4','--analysis-fps','10']),
    ('replay-gpu-8-pipeline-10fps',['--pipeline','--render','scene','--views','8','--analysis-fps','10']),
    ('replay-lut-8-pipeline-10fps',['--lookup','--verify-lookup','--pipeline','--render','scene','--views','8','--analysis-fps','10']),
    ('replay-npu-verified',['--render','none','--delegate','NPU']),
    ('replay-rs-convert-only',['--render','none','--delegate','NONE','--convert']),
]
for name,extra in cases:
    print('START',name,flush=True)
    command=[sys.executable,'-X','utf8',str(root/'scripts/run_case.py'),name,'--input','replay','--delegate','GPU',
             '--seconds','30','--scale','.5','--output-dir',str(out),*extra]
    process=subprocess.run(command,cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace')
    (out/(name+'-host-log.txt')).write_text(process.stdout+process.stderr,encoding='utf-8')
    if process.returncode: raise RuntimeError(name+': '+process.stdout+process.stderr)
    data=json.loads((out/(name+'.json')).read_text(encoding='utf-8'))
    result=data['result']
    print(json.dumps(dict(name=name,face_fps=result.get('effective_fps'),face_fraction=result.get('face_fraction'),
                          conversion_ms=result.get('conversion',{}).get('mean_ms'),
                          render_fps=result.get('render',{}).get('fps'),presentation=data.get('presentation'),
                          system=data['system'])),flush=True)
