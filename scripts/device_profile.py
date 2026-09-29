"""Auditable, reversible package cleanup and measurements on the named YS-L6."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

ADB = r'C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe'
SERIAL = '6L32552009566714'
OUT = Path(r'E:\tripo\output\device-benchmark\20260929')
PACKAGES = [
    'com.Jupiter.PhotoFrame101', 'com.Jupiter.AutoFrame',
    'com.yishengkj.testtools', 'com.ys.checknet', 'android.rockchip.update.service',
    'com.android.printspooler', 'com.android.bips', 'com.android.printservice.recommendation',
    'com.android.soundrecorder', 'com.android.music', 'com.android.musicfx',
    'com.android.gallery3d', 'com.android.calculator2', 'com.android.egg',
    'com.android.dreams.basic', 'com.android.wallpaper.livepicker',
    'com.android.wallpaperpicker', 'com.android.wallpapercropper',
    'com.android.wallpaperbackup', 'com.android.backupconfirm',
    'com.android.sharedstoragebackup', 'com.android.localtransport',
    'com.android.bookmarkprovider', 'acr.browser.barebones', 'com.android.camera2',
]

def adb(*args, check=True):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=35)
    if check and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()

def sample():
    text = adb('shell', 'cat /proc/stat; cat /proc/meminfo; '
               'cat /sys/class/thermal/thermal_zone0/temp; '
               'cat /sys/class/devfreq/fde60000.gpu/load; '
               'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq')
    cpu = [int(x) for x in re.search(r'^cpu\s+(.+)$', text, re.M).group(1).split()]
    mem = {key: int(value) for key, value in re.findall(r'^(\w+):\s+(\d+) kB', text, re.M)}
    ending = text.splitlines()[-3:]
    return dict(time=time.time(), cpu_ticks=cpu[:8], mem_available_kib=mem['MemAvailable'],
                swap_used_kib=mem['SwapTotal']-mem['SwapFree'],
                temperature_c=int(ending[0])/1000, gpu_load=ending[1], cpu0_khz=int(ending[2]))

def summarize(series):
    usage = []
    for left, right in zip(series, series[1:]):
        delta = [b-a for a, b in zip(left['cpu_ticks'], right['cpu_ticks'])]
        if sum(delta) > 0:
            usage.append(100*(sum(delta)-delta[3]-delta[4])/sum(delta))
    return dict(samples=len(series), cpu_percent_whole_machine=sum(usage)/max(1,len(usage)),
                mem_available_mib_mean=sum(x['mem_available_kib'] for x in series)/len(series)/1024,
                mem_available_mib_min=min(x['mem_available_kib'] for x in series)/1024,
                temperature_c_min=min(x['temperature_c'] for x in series),
                temperature_c_max=max(x['temperature_c'] for x in series))

def baseline(name, seconds):
    series=[]
    finish=time.monotonic()+seconds
    while time.monotonic()<finish:
        series.append(sample())
        time.sleep(1)
    result=dict(summary=summarize(series), samples=series)
    (OUT/f'{name}.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result['summary']), flush=True)

def disable():
    journal=OUT/'cleanup-journal.json'
    if journal.exists():
        raise RuntimeError('Cleanup journal exists; review it instead of applying twice.')
    installed=set(re.findall(r'package:([^\s]+)',adb('shell','pm list packages')))
    states=[]
    for package in PACKAGES:
        if package not in installed: continue
        info=adb('shell','dumpsys package '+package)
        row=re.search(r'User 0:.*',info)
        if not row: raise RuntimeError('Cannot verify user state for '+package)
        enabled=int(re.search(r'enabled=(\d+)',row.group()).group(1))
        states.append(dict(package=package, original_enabled=enabled, original_user_state=row.group(), applied=False))
    payload=dict(serial=SERIAL, created=time.time(), packages=states)
    journal.write_text(json.dumps(payload,indent=2),encoding='utf-8')
    for item in states:
        if item['original_enabled'] in (2,3,4): continue
        output=adb('shell','pm disable-user --user 0 '+item['package'])
        if 'disabled-user' not in output: raise RuntimeError(output)
        item['applied']=True
        journal.write_text(json.dumps(payload,indent=2),encoding='utf-8')
        print(output,flush=True)
    (OUT/'disabled-packages.txt').write_text(adb('shell','pm list packages -d')+'\n',encoding='utf-8')

def restore():
    payload=json.loads((OUT/'cleanup-journal.json').read_text(encoding='utf-8'))
    if payload['serial']!=SERIAL: raise RuntimeError('Serial mismatch')
    commands={0:'default-state',1:'enable',2:'disable',3:'disable-user',4:'disable-until-used'}
    for item in payload['packages']:
        if not item['applied']: continue
        print(adb('shell',f"pm {commands[item['original_enabled']]} --user 0 {item['package']}"),flush=True)
    (OUT/'cleanup-restored.txt').write_text(str(time.time()),encoding='utf-8')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('mode', choices=['baseline','disable','restore'])
    parser.add_argument('--name',default='baseline')
    parser.add_argument('--seconds',type=int,default=15)
    args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    if adb('get-serialno')!=SERIAL: raise RuntimeError('Wrong device')
    if args.mode=='baseline': baseline(args.name,args.seconds)
    elif args.mode=='disable': disable()
    else: restore()
