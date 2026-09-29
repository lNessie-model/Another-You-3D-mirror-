import argparse
import json
import re
import subprocess
import time
from device_profile import adb, SERIAL, OUT

parser=argparse.ArgumentParser()
parser.add_argument('--name',default='reboot-verification')
parser.add_argument('--home',default='com.mirror.launcher')
args=parser.parse_args()

deadline = time.monotonic()+120
ready = False
while time.monotonic()<deadline:
    try:
        home=adb('shell','cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
        user=adb('shell','dumpsys user')
        if adb('shell','getprop sys.boot_completed') == '1' and 'State: RUNNING_UNLOCKED' in user and args.home+'/' in home:
            ready = True
            break
    except (RuntimeError, subprocess.TimeoutExpired):
        pass
    time.sleep(2)
if not ready: raise RuntimeError('Device has not finished booting')
home=adb('shell','cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
if args.home+'/' not in home: raise RuntimeError('Unexpected HOME: '+home)
report=dict(serial=adb('get-serialno'),boot_completed=adb('shell','getprop sys.boot_completed'),
            adb_enabled=adb('shell','settings get global adb_enabled'),home=home,
            fingerprint=adb('shell','getprop ro.build.fingerprint'),
            disabled=adb('shell','pm list packages -d'))
report['settings_start']=adb('shell','am start -W -a android.settings.SETTINGS')
time.sleep(2)
report['settings_resumed']=adb('shell','dumpsys activity activities | grep mResumedActivity')
if 'com.android.settings/' not in report['settings_resumed']: raise RuntimeError('Settings failed to resume')
report['home_start']=adb('shell','am start -W -a android.intent.action.MAIN -c android.intent.category.HOME')
time.sleep(4)
report['home_resumed']=adb('shell','dumpsys activity activities | grep mResumedActivity')
report['core_processes']=adb('shell','ps -A -o PID,NAME | grep -E "adbd|cameraserver|camera.provider|surfaceflinger|systemui|softwinner|Jupiter|mirror.launcher"')
report['camera_service']=adb('shell','dumpsys media.camera | head -25')
report['screen_size']=adb('shell','wm size')
report['disk']=adb('shell','df -k /data')
report['memory']=adb('shell','cat /proc/meminfo | head -5')
if args.home=='com.softwinner.launcher':
    report['factory_app_start']=adb('shell','am start -W -n com.Jupiter.PhotoFrame101/com.unity3d.player.UnityPlayerGameActivity')
    time.sleep(4)
    report['factory_app_resumed']=adb('shell','dumpsys activity activities | grep mResumedActivity')
    report['factory_app_pid']=adb('shell','pidof com.Jupiter.PhotoFrame101',check=False)
    report['factory_logs']=adb('logcat','-d','-t','200','-s','AndroidRuntime','Unity','ActivityTaskManager')
    package_info=adb('shell','dumpsys package com.Jupiter.PhotoFrame101')
    app_uid=re.search(r'userId=(\d+)',package_info).group(1)
    requested_wifi=bool(re.search(r'START .*android.settings.WIFI_SETTINGS .*from uid '+app_uid,report['factory_logs']))
    report['factory_network_setup_prompt']=('WifiSettings' in report['factory_app_resumed'] and requested_wifi and bool(report['factory_app_pid']))
    if 'com.Jupiter.PhotoFrame101/' not in report['factory_app_resumed'] and not report['factory_network_setup_prompt']:
        report['failure']='Factory 3D app did not remain in foreground'
        (OUT/(args.name+'.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
        raise RuntimeError(report['failure']+': '+report['factory_app_resumed'])
    report['home_return']=adb('shell','am start -W -a android.intent.action.MAIN -c android.intent.category.HOME')
home_visible=args.home+'/' in report['home_resumed']
if args.home=='com.softwinner.launcher':
    home_visible=home_visible or 'com.Jupiter.PhotoFrame101/' in report['home_resumed'] or (
        'WifiSettings' in report['home_resumed'] and report.get('factory_network_setup_prompt',False))
if not home_visible:
    raise RuntimeError('HOME did not launch: '+report['home_resumed'])
if report['serial']!=SERIAL or report['adb_enabled']!='1': raise RuntimeError('ADB check failed')
(OUT/(args.name+'.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report),flush=True)
