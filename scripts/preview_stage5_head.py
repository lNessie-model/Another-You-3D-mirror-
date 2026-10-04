"""Reversible asset-only v24 board preview; no APK, preference, or driver changes."""
from pathlib import Path
import argparse, datetime, hashlib, json, subprocess, time, uuid

ADB = r'C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe'
SERIAL = '6L32552009566714'
PACKAGE = 'com.mirror.bench'
APK_SHA = '2ff4dd4638f33ba947e14a85b923591c5502ee98ee86ca3acd7726f393a2c250'
BASE_SHA = 'f9231aca006210b83aef194bee9b6cfec4de6fc75322597d9baa051ff5be597c'
HEAD_SHA = '250941f86847ef22e599279fc6d00b62d80cf43ba9174d6ae01c1cb1e3b37d27'
HEAD = Path(r'E:\tripo\assets\mirror-models-20261003\lowpoly-heads-v2\geralt-rig-stage5-oral-matte-v3')
CONFIGS = {'runtime.xml': 'shared_prefs/mirror-runtime.xml',
           'scene.xml': 'shared_prefs/mirror-scene-view.xml',
           'selection.json': 'files/avatars/state.json'}


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--restore', action='store_true')
    args = parser.parse_args()
    out = args.out.resolve()
    if not args.restore: out.mkdir(parents=True, exist_ok=False)
    report = {'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'apk_sha256': APK_SHA, 'candidate_sha256': HEAD_SHA, 'baseline_sha256': BASE_SHA,
              'artistAccepted': False, 'scope': 'Asset-only private diagnostic, not stored-avatar selection or live USB'}
    receipt = out / ('restore-receipt.json' if args.restore else 'receipt.json')

    def save(): receipt.write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    def adb(*arguments):
        result = subprocess.run([ADB, '-s', SERIAL, *arguments], capture_output=True, timeout=60)
        if result.returncode: raise RuntimeError(str(arguments) + ': ' + result.stderr.decode('utf-8', 'replace'))
        return result.stdout
    def private(name): return adb('exec-out', 'run-as', PACKAGE, 'cat', name)
    def snapshot(prefix):
        for name, device_path in CONFIGS.items(): (out / (prefix + '-' + name)).write_bytes(private(device_path))
    def head_hash(): return sha(private('files/tripo-head-check/character.glb'))
    def copy_head(source, expected):
        assert sha((source / 'character.glb').read_bytes()) == expected
        manifest = json.loads((source / 'avatar.json').read_text('utf-8'))
        assert manifest['modelSha256'] == expected and manifest['model'] == 'character.glb'
        adb('shell', 'am', 'force-stop', PACKAGE)
        staging = '/data/local/tmp/head-stage5-' + str(uuid.uuid4())
        adb('shell', 'mkdir', '-p', staging)
        for name in ('character.glb', 'avatar.json'):
            adb('push', str(source / name), staging + '/' + name)
            adb('shell', 'run-as', PACKAGE, 'cp', staging + '/' + name, 'files/tripo-head-check/' + name)
            assert private('files/tripo-head-check/' + name) == (source / name).read_bytes()
        report['private_model_sha256'] = head_hash(); save()
    def launch(expected):
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MirrorActivity', '--es', 'runtime_input', 'replay',
            '--ez', 'test_private_head', 'true', '--ez', 'test_npu_blendshapes', 'true',
            '--ei', 'test_view_count', '16', '--es', 'test_view_preset', '400x640', '--ez', 'test_persistent_fbos', 'true')
        deadline = time.monotonic() + 90
        first = None
        while time.monotonic() < deadline:
            time.sleep(3)
            try: status = json.loads(private('files/mirror-runtime-status.json'))
            except (ValueError, RuntimeError): continue
            if (status.get('state') != 'INTERACTIVE' or not status.get('face_present') or status.get('error')
                    or status.get('input') != 'replay' or status.get('renderer', {}).get('avatar', {}).get('model_sha256') != expected):
                continue
            if first and status['runtime_session_id'] == first['runtime_session_id'] and status['status_sequence'] > first['status_sequence']:
                assert status['metrics']['landmarks'] == 478 and status['metrics']['blendshapes'] == 52
                (out / ('restored-status.json' if args.restore else 'candidate-status.json')).write_text(json.dumps(status, ensure_ascii=False, indent=2), 'utf-8')
                report['runtime_verified'] = True; save(); return status
            first = status
        raise RuntimeError('No two advancing complete-face replay samples within 90 seconds')

    assert adb('get-state').strip() == b'device'
    installed = adb('shell', 'pm', 'path', PACKAGE).decode().strip()
    assert installed.startswith('package:') and '\n' not in installed
    assert adb('shell', 'sha256sum', installed[8:]).decode().split()[0] == APK_SHA, 'Installed APK changed; refusing overwrite'
    backup = out / 'original-head'
    save()
    if args.restore:
        assert head_hash() in (BASE_SHA, HEAD_SHA), 'Another head is active; refusing overwrite'
        copy_head(backup, BASE_SHA); launch(BASE_SHA); report['restored'] = True; save(); return
    assert head_hash() == BASE_SHA, 'Unexpected existing private head; preserve it'
    backup.mkdir()
    for name in ('character.glb', 'avatar.json'): (backup / name).write_bytes(private('files/tripo-head-check/' + name))
    assert sha((backup / 'character.glb').read_bytes()) == BASE_SHA
    snapshot('before'); report['backup_complete'] = True; save()
    changed = False
    try:
        changed = True; copy_head(HEAD, HEAD_SHA)
        previous = private('files/tripo-head-preview-check.json')
        try: previous_id = json.loads(previous).get('run_id')
        except ValueError: previous_id = None
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.AvatarPreviewActivity', '--ez', 'test_private_head', 'true', '--ez', 'verify_private_head', 'true')
        print('Candidate uploaded; checking actual driver renders and multiview', flush=True)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            time.sleep(3)
            preview = json.loads(private('files/tripo-head-preview-check.json'))
            if preview.get('run_id') == previous_id or preview.get('running', True): continue
            assert preview.get('passed'), preview.get('error', 'Driver preview failed')
            multiview = json.loads(private('files/tripo-head-multiview-check.json'))
            if multiview.get('run_id') != preview.get('run_id') or multiview.get('running', True): continue
            assert multiview.get('passed'), 'Multiview or batch pixel comparison failed'
            assert preview['avatar']['model_sha256'] == HEAD_SHA
            for gate in ('serial_vs_ovr', 'individual_vs_batch'): assert multiview[gate]['avatar']['model_sha256'] == HEAD_SHA
            (out / 'driver-preview.json').write_text(json.dumps(preview, ensure_ascii=False, indent=2), 'utf-8')
            (out / 'driver-multiview.json').write_text(json.dumps(multiview, ensure_ascii=False, indent=2), 'utf-8')
            renders = out / 'driver-renders'; renders.mkdir()
            for frame in preview['renders']:
                assert frame['name'].replace('-', '').isalpha()
                png = private('files/' + frame['file']); assert sha(png) == frame['sha256'] and png.startswith(b'\x89PNG')
                (renders / (frame['name'] + '.png')).write_bytes(png)
            report['driver_gates_passed'] = True; save(); break
        else: raise RuntimeError('Driver gates did not finish in 240 seconds')
        launch(HEAD_SHA); snapshot('after')
        report['configuration_byte_exact'] = all((out / ('before-' + name)).read_bytes() == (out / ('after-' + name)).read_bytes() for name in CONFIGS)
        screen = adb('exec-out', 'screencap', '-p'); assert screen.startswith(b'\x89PNG')
        (out / 'candidate-screen.png').write_bytes(screen)
        report['candidate_left_for_review'] = True; save()
        print(json.dumps(report, ensure_ascii=False), flush=True)
    except Exception as error:
        report['error'] = str(error); save()
        if changed:
            try:
                copy_head(backup, BASE_SHA); launch(BASE_SHA); report['rollback_verified'] = True
            except Exception as rollback: report['rollback_error'] = str(rollback)
        save(); raise


if __name__ == '__main__': main()
