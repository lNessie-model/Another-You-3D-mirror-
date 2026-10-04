"""Temporary native display rotation with a durable journal and exact preference restoration."""
from contextlib import contextmanager
import json,time
from device_profile import adb

@contextmanager
def temporary_rotation(rotation,journal):
    if rotation not in range(4): raise ValueError('Rotation must be 0..3')
    original={key:adb('shell','settings get system '+key) for key in ('accelerometer_rotation','user_rotation')}
    if original['accelerometer_rotation'] not in ('0','1') or original['user_rotation'] not in ('0','1','2','3'):
        raise RuntimeError('Cannot safely identify original rotation preferences')
    report=dict(original=original,requested_rotation=rotation,attempted=True,restored=False)
    journal.write_text(json.dumps(report,indent=2),encoding='utf-8')
    try:
        adb('shell','wm set-user-rotation lock '+str(rotation)); time.sleep(1)
        report['active_preferences']={key:adb('shell','settings get system '+key) for key in original}
        report['active_display']=adb('shell','dumpsys window displays')
        journal.write_text(json.dumps(report,indent=2),encoding='utf-8')
        yield report
    finally:
        adb('shell','am force-stop com.mirror.bench')
        adb('shell','wm set-user-rotation lock '+original['user_rotation'])
        adb('shell','settings put system accelerometer_rotation '+original['accelerometer_rotation'])
        report['final_preferences']={key:adb('shell','settings get system '+key) for key in original}
        report['restored']=report['final_preferences']==original
        journal.write_text(json.dumps(report,indent=2),encoding='utf-8')
        if not report['restored']: raise RuntimeError('Display rotation preferences were not restored')
