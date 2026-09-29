import subprocess
import time
from pathlib import Path
from device_profile import adb, SERIAL, OUT

deadline = time.monotonic()+120
ready = False
while time.monotonic()<deadline:
    try:
        if adb('shell','getprop sys.boot_completed') == '1':
            ready = True
            break
    except (RuntimeError, subprocess.TimeoutExpired):
        pass
    time.sleep(2)
if not ready: raise RuntimeError('Device has not finished booting')
report = adb('shell', 'pm list packages -d; '
             'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; '
             'ps -A -o PID,NAME | grep -E "Jupiter|testtools|checknet|printspooler|systemui|softwinner"; '
             'cat /proc/meminfo | head -4; settings get global adb_enabled')
(OUT/'reboot-verification.txt').write_text(report+'\n',encoding='utf-8')
print(report)
