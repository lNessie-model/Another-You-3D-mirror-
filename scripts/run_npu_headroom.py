"""Increase useful face throughput while holding USB, 20 views and native UI constant."""
import argparse,json,subprocess,sys
from pathlib import Path

parser=argparse.ArgumentParser(); parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args(); args.output.mkdir(parents=True,exist_ok=True)
script=Path(__file__).with_name('run_case.py')
common=['--delegate','RKNN','--input','camera','--face-input','replay','--render-fps','31','--ui-load',
        '--render','scene','--views','20','--scale','.375','--width-scale','.2','--pipeline','--cull',
        '--preblend','--multiview','--explicit-lod','--pitch','10','--tan','.2777777','--pitch-units','subpixels',
        '--conversion-backend','rga','--native-packing','--output-dir',str(args.output)]
cases=[('npu-ui-pipeline-capacity-60s',0,60,True),
       ('npu-ui-reference10-60s',10,60,False),('npu-ui-serial20-60s',20,60,False),
       ('npu-ui-pipeline15-180s',15,180,True),('npu-ui-pipeline20-300s',20,300,True),
       ('npu-ui-pipeline17-300s',17,300,True)]
def minute_rates(data):
    result=data['result']; start=result['measurement_start_monotonic_ns']; end=result['measurement_end_monotonic_ns']
    stamps=sorted({t for point in data['samples'] for t in point.get('presented_timestamps_ns',[]) if start<=t<=end})
    rates=[]
    for i in range(int(result['elapsed_s']//60)):
        values=[t for t in stamps if start+i*60e9<=t<start+(i+1)*60e9]
        rates.append((len(values)-1)*1e9/(values[-1]-values[0]) if len(values)>2 else 0)
    return rates
for name,rate,seconds,pipelined in cases:
    print('START',name,flush=True)
    command=[sys.executable,'-X','utf8',str(script),name,'--seconds',str(seconds),'--analysis-fps',str(rate),*common]
    if pipelined: command.append('--npu-pipeline')
    with (args.output/(name+'-host.log')).open('w',encoding='utf-8') as log:
        run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    if run.returncode: raise RuntimeError('Benchmark command failed: '+name)
    data=json.loads((args.output/(name+'.json')).read_text(encoding='utf-8'))
    result=data['result']; system=data['system']
    if result['status']!='success': raise RuntimeError('Device benchmark failed: '+name)
    print(json.dumps(dict(run=name,display_fps=data['presentation']['presented_fps'],face_fps=result['effective_fps'],
                         cpu=system['cpu_percent_whole_machine'],gpu=system['gpu_busy_percent_mean'],
                         npu=system['npu_busy_percent_mean'],min_memory=system['mem_available_mib_min'],
                         max_temperature=system['temperature_c_max'])),flush=True)
    if rate==17 and min(minute_rates(data),default=0)<30:
        cases.append(('npu-ui-pipeline15-300s',15,300,True))
