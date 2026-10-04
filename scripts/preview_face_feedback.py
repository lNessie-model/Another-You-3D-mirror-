"""Install a pinned face-response candidate, verify real GL, and retain rollback files."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid
import zipfile

ADB=r'C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe'
SERIAL='6L32552009566714'
PACKAGE='com.mirror.bench'
CONFIGS={'runtime.xml':'shared_prefs/mirror-runtime.xml',
         'scene.xml':'shared_prefs/mirror-scene-view.xml','selection.json':'files/avatars/state.json'}


def sha(data):return hashlib.sha256(data).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('head','apk','out','current-apk'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--current-head-sha256',required=True)
    args=parser.parse_args();out=args.out.resolve();assert not out.exists(),'Preserve prior evidence'
    head=args.head.resolve();apk=args.apk.resolve();oldapk=args.current_apk.resolve()
    manifest=json.loads((head/'avatar.json').read_text('utf-8'))
    candidate_sha=sha((head/'character.glb').read_bytes())
    assert manifest['modelSha256']==candidate_sha
    with zipfile.ZipFile(oldapk) as before,zipfile.ZipFile(apk) as after:
        for name in before.namelist():
            if name.startswith('lib/') and name.endswith('.so'):
                assert before.read(name)==after.read(name),'Native library changed: '+name
    commands=[]
    def call(*argv,allow_failure=False):
        result=subprocess.run([ADB,'-s',SERIAL,*argv],capture_output=True,timeout=120)
        commands.append({'args':argv,'returncode':result.returncode,
                         'stdout':result.stdout.decode('utf-8','replace') if argv[0]!='exec-out' else '<binary-or-private-data archived separately>',
                         'stderr':result.stderr.decode('utf-8','replace')})
        if result.returncode and not allow_failure:
            raise RuntimeError('ADB command failed: '+repr(argv)+': '+result.stderr.decode('utf-8','replace'))
        return result.stdout
    def private(path):return call('exec-out','run-as',PACKAGE,'cat',path)
    def installed_sha():
        path=call('shell','pm','path',PACKAGE).decode().strip()
        assert path.startswith('package:') and '\n' not in path
        return call('shell','sha256sum',path[8:]).decode().split()[0]
    assert installed_sha()==sha(oldapk.read_bytes()),'Installed APK differs from rollback baseline'
    assert sha(private('files/tripo-head-check/character.glb'))==args.current_head_sha256
    out.mkdir(parents=True)
    receipt={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
             'candidate_apk_sha256':sha(apk.read_bytes()),'candidate_model_sha256':candidate_sha,
             'previous_apk_sha256':sha(oldapk.read_bytes()),'previous_model_sha256':args.current_head_sha256,
             'apk':str(apk),'head':str(head),'rollback_apk':str(oldapk),'native_libraries_unchanged':True,
             'artist_accepted':False,'personal_thresholds_measured':False,'camera_face_verified':False,
             'physical_presentation_fps_measured':False,'passed':False}
    def save():
        (out/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),'utf-8')
        (out/'commands.json').write_text(json.dumps(commands,ensure_ascii=False,indent=2),'utf-8')
    for label,path in CONFIGS.items():(out/('before-'+label)).write_bytes(private(path))
    for name in ('character.glb','avatar.json'):
        (out/('before-'+name)).write_bytes(private('files/tripo-head-check/'+name))
    (out/'before-status.json').write_bytes(private('files/mirror-runtime-status.json'))
    (out/'before-screen.png').write_bytes(call('exec-out','screencap','-p'))
    previous=json.loads(private('files/tripo-head-preview-check.json')).get('run_id')
    save()
    def deploy(model,meta):
        call('shell','am','force-stop',PACKAGE)
        staging='/data/local/tmp/face-feedback-'+str(uuid.uuid4())
        call('shell','mkdir','-p',staging)
        for name,path in (('character.glb',model),('avatar.json',meta)):
            call('push',str(path),staging+'/'+name)
            call('shell','run-as',PACKAGE,'cp',staging+'/'+name,'files/tripo-head-check/'+name)
            assert private('files/tripo-head-check/'+name)==path.read_bytes()
    def launch():
        call('shell','am','force-stop',PACKAGE)
        result=call('shell','am','start','-n',PACKAGE+'/.MirrorActivity','--es','runtime_input','camera',
                    '--ez','test_private_head','true','--ez','test_npu_blendshapes','true',
                    '--ei','test_view_count','16','--es','test_view_preset','400x640','--ez','test_persistent_fbos','true')
        assert b'Error:' not in result
    def restore_configs():
        # A failed candidate may have migrated saved preferences. Restore the
        # exact backed-up bytes while the package is stopped before old launch.
        staging='/data/local/tmp/face-feedback-config-'+str(uuid.uuid4())
        call('shell','mkdir','-p',staging)
        for label,path in CONFIGS.items():
            backup=out/('before-'+label)
            call('push',str(backup),staging+'/'+label)
            call('shell','run-as',PACKAGE,'cp',staging+'/'+label,path)
            assert private(path)==backup.read_bytes(),'Configuration restore failed: '+label
    changed=False
    try:
        changed=True
        print('Installing pinned APK; old APK, head and preferences backed up',flush=True)
        assert b'Success' in call('install','-r',str(apk))
        assert installed_sha()==receipt['candidate_apk_sha256']
        deploy(head/'character.glb',head/'avatar.json');receipt['deployed']=True;save()
        call('shell','am','start','-n',PACKAGE+'/.AvatarPreviewActivity',
             '--ez','test_private_head','true','--ez','verify_private_head','true')
        deadline=time.monotonic()+180
        print('Verifying real driver, deterministic poses and multiview output',flush=True)
        while time.monotonic()<deadline:
            time.sleep(2)
            try:
                p=json.loads(private('files/tripo-head-preview-check.json'))
                m=json.loads(private('files/tripo-head-multiview-check.json'))
            except (ValueError,RuntimeError):continue
            run_id=p.get('run_id')
            if not run_id or run_id==previous or p.get('running',True):continue
            assert p.get('passed'),p.get('error','Driver pose preview failed')
            # Repeated deployments of the same model can leave an older
            # successful multiview report. Both gates must belong to this run.
            if m.get('run_id')!=run_id or m.get('running',True):continue
            assert m.get('passed'),'Multiview geometry/material gate failed'
            assert p['avatar']['model_sha256']==candidate_sha
            for gate in ('serial_vs_ovr','individual_vs_batch'):
                assert m[gate]['avatar']['model_sha256']==candidate_sha
            (out/'candidate-driver.json').write_text(json.dumps(p,ensure_ascii=False,indent=2),'utf-8')
            (out/'candidate-multiview.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),'utf-8')
            renders=out/'candidate-renders';renders.mkdir()
            for frame in p['renders']:
                name=Path(frame['file']).name;assert name.endswith('.png')
                pixels=private('files/'+frame['file']);assert sha(pixels)==frame['sha256']
                (renders/name).write_bytes(pixels)
            receipt['driver_gates_passed']=True;save();break
        else:raise RuntimeError('Driver gates timed out')
        launch();deadline=time.monotonic()+45;first=None
        while time.monotonic()<deadline:
            time.sleep(2)
            s=json.loads(private('files/mirror-runtime-status.json'))
            if s.get('input')!='camera' or s.get('error') or s.get('render_fault') or s.get('processing_fault'):continue
            renderer=s.get('renderer') or {};avatar=renderer.get('avatar') or {}
            if avatar.get('model_sha256')!=candidate_sha or not renderer.get('runtime_gl_frame_ready'):continue
            assert s.get('view_count')==16 and s.get('view_width')==400 and s.get('view_height')==640
            assert s.get('debug_npu_blendshapes') is True
            assert 'v3' in renderer.get('face_playback',{}).get('response_policy','')
            if first and first['runtime_session_id']==s['runtime_session_id'] and s['status_sequence']>first['status_sequence']:
                (out/'live-status.json').write_text(json.dumps(s,ensure_ascii=False,indent=2),'utf-8')
                receipt.update(camera_preview_advancing=True,camera_face_verified=bool(s.get('face_present')),
                               live_session=s['runtime_session_id'],scene_view=renderer.get('scene_view'));break
            first=s
        else:raise RuntimeError('No advancing camera preview with the new response policy')
        for label,path in CONFIGS.items():(out/('after-'+label)).write_bytes(private(path))
        receipt['configuration_byte_exact']=all((out/('before-'+label)).read_bytes()==(out/('after-'+label)).read_bytes() for label in CONFIGS)
        assert receipt['configuration_byte_exact'],'Saved configuration unexpectedly changed'
        (out/'candidate-screen.png').write_bytes(call('exec-out','screencap','-p'))
        receipt.update(passed=True,candidate_left_for_review=True);save()
    except BaseException as error:
        receipt['error']=type(error).__name__+': '+str(error);save()
        if changed:
            try:
                assert b'Success' in call('install','-r',str(oldapk))
                deploy(out/'before-character.glb',out/'before-avatar.json')
                restore_configs();launch()
                receipt['rollback_verified']=installed_sha()==receipt['previous_apk_sha256'] and sha(private('files/tripo-head-check/character.glb'))==args.current_head_sha256
            except BaseException as rollback:receipt['rollback_error']=str(rollback)
        save();raise
    print(json.dumps(receipt,ensure_ascii=False),flush=True)


if __name__=='__main__':main()
