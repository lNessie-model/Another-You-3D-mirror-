"""Run one exact-size case under journaled temporary rotation; stop and restore on exit."""
import argparse,json,re,subprocess,sys
from pathlib import Path
from display_rotation import temporary_rotation
parser=argparse.ArgumentParser(); parser.add_argument('name'); parser.add_argument('--output-dir',type=Path,required=True)
parser.add_argument('--orientation',choices=['portrait','landscape'],default='landscape')
args,extra=parser.parse_known_args()
if not re.fullmatch('[a-zA-Z0-9_-]+',args.name): parser.error('Invalid case name')
args.output_dir.mkdir(parents=True,exist_ok=True)
command=[sys.executable,'-X','utf8',str(Path(__file__).with_name('run_case.py')),args.name,
         '--output-dir',str(args.output_dir),'--orientation',args.orientation,*extra]
with temporary_rotation(1 if args.orientation=='landscape' else 0,args.output_dir/(args.name+'-rotation.json')):
    with (args.output_dir/(args.name+'-host.log')).open('w',encoding='utf-8') as log:
        run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
if run.returncode: raise SystemExit(run.returncode)
data=json.loads((args.output_dir/(args.name+'.json')).read_text(encoding='utf-8'))
result=data['result']; render=result.get('render',{})
print(json.dumps(dict(run=args.name,status=result['status'],error=result.get('error'),system=data['system'],
                     presentation=data['presentation'],face_fps=result.get('effective_fps'),
                     output=[render.get('output_width'),render.get('output_height')],
                     view=[render.get('view_width'),render.get('view_height')],
                     stage_scene_ms=render.get('scene_completion_mean_ms'),stage_interlace_ms=render.get('interlace_completion_mean_ms'),
                     combined_pixels=render.get('combined_verification')),ensure_ascii=False))
if result['status']!='success': raise SystemExit(1)
