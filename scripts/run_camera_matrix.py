"""Sequential component and joint benchmarks on the serial fixed by device_profile.py."""
import argparse
import subprocess
import sys
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--group',choices=['components','joint','hd','steady'],required=True)
parser.add_argument('--output-dir',type=Path,default=Path(r'E:\tripo\output\camera-benchmark\20261001'))
parser.add_argument('--face-input',choices=['camera','portrait'],default='camera')
args=parser.parse_args()
camera=['--input','camera']
scene=['--render','scene','--scale','.5','--profile-stages']
groups={
    'components':[
        ('idle',20,['--delegate','NONE']),
        ('capture-vga',30,camera+['--delegate','NONE']),
        ('convert-vga',30,camera+['--delegate','NONE','--convert']),
        ('face-cpu-vga',30,camera+['--delegate','CPU']),
        ('face-gpu-vga',30,camera+['--delegate','GPU']),
        ('interlace-4-half',30,['--delegate','NONE','--render','interlace','--scale','.5','--views','4','--profile-stages']),
        ('render-4-half',30,['--delegate','NONE','--views','4']+scene),
        ('render-8-half',30,['--delegate','NONE','--views','8']+scene),
        ('capture-render-4-half',30,camera+['--delegate','NONE','--convert','--views','4']+scene),
    ],
    'joint':[
        ('joint-cpu-vga-4-half',30,camera+['--delegate','CPU','--views','4']+scene),
        ('joint-gpu-vga-4-half',60,camera+['--delegate','GPU','--views','4']+scene),
        ('joint-gpu-vga-8-half',60,camera+['--delegate','GPU','--views','8']+scene),
        ('joint-gpu-vga-4-full',30,camera+['--delegate','GPU','--views','4','--render','scene','--scale','1','--profile-stages']),
    ],
    'hd':[
        ('face-gpu-hd',30,camera+['--delegate','GPU','--camera-width','1280','--camera-height','720','--capture-fps','15']),
        ('joint-gpu-hd-4-half',60,camera+['--delegate','GPU','--camera-width','1280','--camera-height','720','--capture-fps','15','--views','4']+scene),
    ],
    'steady':[
        ('joint-gpu-vga-4-half-10fps-300s',300,camera+['--delegate','GPU','--analysis-fps','10','--views','4']+scene),
    ]
}
for name,seconds,options in groups[args.group]:
    if args.face_input=='portrait': name+='-controlled-face'
    command=[sys.executable,'-X','utf8',str(Path(__file__).with_name('run_case.py')),name,
             '--seconds',str(seconds),'--output-dir',str(args.output_dir),'--face-input',args.face_input]+options
    subprocess.run(command,check=True)
