"""Post-test debug/launcher/vendor integrity checks; no firmware or driver writes."""
import argparse,json
from pathlib import Path
from device_profile import adb,SERIAL
parser=argparse.ArgumentParser(); parser.add_argument('--output',type=Path,required=True); args=parser.parse_args()
adb('shell','am force-stop com.mirror.bench')
package_path=adb('shell','pm path com.mirror.bench').splitlines()[0].split(':',1)[1]
report=dict(serial=SERIAL,adb_state=adb('get-state'),adb_enabled=adb('shell','settings get global adb_enabled'),
            fingerprint=adb('shell','getprop ro.build.fingerprint'),
            home=adb('shell','cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'),
            test_app_process=adb('shell','pidof com.mirror.bench',check=False),
            driver=adb('shell','cat /sys/kernel/debug/rknpu/driver_version'),
            npu_after_stop=adb('shell','cat /sys/kernel/debug/rknpu/load'),
            vendor_runtime_sha256=adb('shell','sha256sum /vendor/lib64/librknnrt.so').split()[0],
            installed_apk_sha256=adb('shell','sha256sum '+package_path).split()[0],
            stable_clip_sha256=adb('shell','run-as com.mirror.bench sha256sum files/recordings/face-reference-stable-20261001-01.nv21').split()[0],
            data_space=adb('shell','df -k /data'))
report['settings_start']=adb('shell','am start -W -a android.settings.SETTINGS')
report['home_start']=adb('shell','am start -W -a android.intent.action.MAIN -c android.intent.category.HOME')
report['passed']=(report['adb_state']=='device' and report['adb_enabled']=='1'
                  and report['fingerprint']=='rockchip/rk3566_r/rk3566_r:11/RD2A.211001.002/eng.mao.20260828.110410:userdebug/release-keys'
                  and 'com.mirror.launcher/' in report['home'] and not report['test_app_process']
                  and '0.7.2' in report['driver']
                  and report['vendor_runtime_sha256']=='01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de'
                  and report['stable_clip_sha256']=='9e41f46ff87e92ebef91608d8fa011d7c4714270a2dd8f77d68aaa848d46b095'
                  and 'Status: ok' in report['settings_start'] and 'Status: ok' in report['home_start'])
args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
if not report['passed']: raise SystemExit(1)
