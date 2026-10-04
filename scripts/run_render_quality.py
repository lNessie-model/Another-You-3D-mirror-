"""Hold exact target pixels and full face/USB/UI load while comparing depth writeback."""
import argparse,json,subprocess,sys
from pathlib import Path
from display_rotation import temporary_rotation
parser=argparse.ArgumentParser(); parser.add_argument('--output',type=Path,required=True); args=parser.parse_args()
args.output.mkdir(parents=True,exist_ok=True)
common=['--delegate','RKNN','--input','camera','--face-input','replay','--analysis-fps','10','--npu-pipeline',
        '--render-fps','0','--ui-load','--render','scene','--views','20','--pipeline','--cull','--preblend','--multiview',
        '--explicit-lod','--pitch','10','--tan','.2777777','--pitch-units','subpixels','--conversion-backend','rga',
        '--native-packing','--output-dir',str(args.output)]
cases=[('quality-phase-landscape-baseline-60s','landscape',720,400,60,'none'),
       ('quality-phase-landscape-depth-60s','landscape',720,400,60,'depth'),
       ('quality-phase-landscape-shared-60s','landscape',720,400,60,'shared'),
       ('quality-phase-portrait-shared-60s','portrait',400,720,60,'shared')]
for name,orientation,width,height,seconds,mode in cases:
    print('START',name,flush=True)
    command=[sys.executable,'-X','utf8',str(Path(__file__).with_name('run_case.py')),name,'--seconds',str(seconds),
             '--orientation',orientation,'--view-width',str(width),'--view-height',str(height),*common]
    if mode!='none': command+=['--discard-depth','--verify-discard-depth','--verify-combined']
    if mode=='shared': command+=['--shared-phase','--verify-shared-phase']
    with temporary_rotation(1 if orientation=='landscape' else 0,args.output/(name+'-rotation.json')):
        with (args.output/(name+'-host.log')).open('w',encoding='utf-8') as log:
            run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    data=json.loads((args.output/(name+'.json')).read_text(encoding='utf-8'))
    if run.returncode or data['result']['status']!='success': raise RuntimeError(name+' failed: '+str(data['result'].get('error')))
    result=data['result']; render=result['render']; system=data['system']
    print(json.dumps(dict(run=name,output=[render['output_width'],render['output_height']],view=[render['view_width'],render['view_height']],
                         display_fps=data['presentation']['presented_fps'],face_fps=result['effective_fps'],
                         cpu=system['cpu_percent_whole_machine'],gpu=system['gpu_busy_percent_mean'],npu=system['npu_busy_percent_mean'],
                         window_depth_bits=render['window_depth_bits'],depth_pixels=render.get('depth_discard_verification'),
                         combined_pixels=render.get('combined_verification'),shared_phase=render.get('shared_phase_verification'))),flush=True)
