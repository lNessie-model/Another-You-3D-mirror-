"""Pinned PBR preview with file backups and APK/asset rollback on failed driver gates."""
from pathlib import Path
import argparse,datetime,hashlib,json,shutil,subprocess,time,uuid,zipfile

ADB=r'C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe'
SERIAL='6L32552009566714';PACKAGE='com.mirror.bench'
OUT=Path(r'E:\tripo\output\mirror-assets\20261004\stage6-pbr-device-check')
HEAD=Path(r'E:\tripo\assets\mirror-models-20261003\lowpoly-heads-v2\geralt-rig-stage6-pbr-v1')
OLDAPK=Path(r'E:\tripo\output\mirror-program\20261004\AvatarRuntime-v26-eye-response.apk')
APK=OLDAPK.with_name('AvatarRuntime-v27-pbr-materials.apk')
OLD_SHA='250941f86847ef22e599279fc6d00b62d80cf43ba9174d6ae01c1cb1e3b37d27'
NEW_SHA='b52b4e6a459a8c840f7cde55c4140e398e88e576c6a611a99fcffbd153cd8f8a'
CONFIGS={'runtime.xml':'shared_prefs/mirror-runtime.xml','scene.xml':'shared_prefs/mirror-scene-view.xml','selection.json':'files/avatars/state.json'}


def sha(data):return hashlib.sha256(data).hexdigest()
def adb(*args):
    p=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,timeout=120)
    if p.returncode:raise RuntimeError(str(args)+': '+p.stderr.decode('utf-8','replace'))
    return p.stdout
def private(path):
    b=adb('exec-out','run-as',PACKAGE,'cat',path)
    if b.startswith(b'cat:'):raise RuntimeError(b.decode('utf-8','replace'))
    return b
def apksha():
    path=adb('shell','pm','path',PACKAGE).decode().strip();assert path.startswith('package:') and '\n' not in path
    return adb('shell','sha256sum',path[8:]).decode().split()[0]
def deploy(model,manifest):
    adb('shell','am','force-stop',PACKAGE);stage='/data/local/tmp/pbr-head-'+str(uuid.uuid4());adb('shell','mkdir','-p',stage)
    for name,path in [('character.glb',model),('avatar.json',manifest)]:
        adb('push',str(path),stage+'/'+name);adb('shell','run-as',PACKAGE,'cp',stage+'/'+name,'files/tripo-head-check/'+name)
        assert private('files/tripo-head-check/'+name)==path.read_bytes()
def launch(input='camera'):
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-n',PACKAGE+'/.MirrorActivity','--es','runtime_input',input,'--ez','test_private_head','true','--ez','test_npu_blendshapes','true','--ei','test_view_count','16','--es','test_view_preset','400x640','--ez','test_persistent_fbos','true')


def main():
    global OUT,HEAD,APK,OLDAPK,OLD_SHA
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,default=OUT);p.add_argument('--head',type=Path,default=HEAD);p.add_argument('--apk',type=Path,default=APK);p.add_argument('--baseline',type=Path)
    p.add_argument('--current-apk',type=Path,default=OLDAPK);p.add_argument('--current-head-sha256',default=OLD_SHA);p.add_argument('--baseline-prefix',choices=('baseline','candidate'),default='baseline')
    args=p.parse_args();OUT,HEAD,APK=args.out.resolve(),args.head.resolve(),args.apk.resolve()
    OLDAPK,OLD_SHA=args.current_apk.resolve(),args.current_head_sha256
    if not OUT.exists():
        assert args.baseline and args.baseline.resolve()!=OUT
        assert apksha()==sha(OLDAPK.read_bytes()) and sha(private('files/tripo-head-check/character.glb'))==OLD_SHA
        OUT.mkdir()
        for name,path in CONFIGS.items():(OUT/('before-'+name)).write_bytes(private(path))
        for name in ('character.glb','avatar.json'):(OUT/('before-'+name)).write_bytes(private('files/tripo-head-check/'+name))
        for name in ('driver.json','multiview.json'):shutil.copyfile(args.baseline/(args.baseline_prefix+'-'+name),OUT/('baseline-'+name))
        shutil.copytree(args.baseline/(args.baseline_prefix+'-renders'),OUT/'baseline-renders')
        (OUT/'before-screen.png').write_bytes(adb('exec-out','screencap','-p'))
        (OUT/'baseline-source.json').write_text(json.dumps({'evidence_directory':str(args.baseline.resolve()),'prefix':args.baseline_prefix,'current_apk_sha256':sha(OLDAPK.read_bytes()),'current_model_sha256':OLD_SHA,'scope':'Previously captured fixed driver poses with the pinned baseline APK/model'},indent=2),'utf-8')
    assert OUT.exists() and not (OUT/'receipt.json').exists() and not APK.exists()
    assert apksha()==sha(OLDAPK.read_bytes())
    assert sha(private('files/tripo-head-check/character.glb'))==OLD_SHA
    assert sha((OUT/'before-character.glb').read_bytes())==OLD_SHA
    assert json.loads((OUT/'baseline-driver.json').read_text('utf-8'))['passed']
    assert json.loads((OUT/'baseline-multiview.json').read_text('utf-8'))['passed']
    assert sha((HEAD/'character.glb').read_bytes())==NEW_SHA
    shutil.copyfile(Path(__file__).resolve().parents[1]/'app/build/outputs/apk/debug/app-debug.apk',APK)
    with zipfile.ZipFile(OLDAPK) as old,zipfile.ZipFile(APK) as new:
        for name in old.namelist():
            if name.startswith('lib/') and name.endswith('.so'):assert old.read(name)==new.read(name),'Native inference library changed'
    receipt={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'apk':str(APK),'apk_sha256':sha(APK.read_bytes()),'model_sha256':NEW_SHA,'previous_apk_sha256':sha(OLDAPK.read_bytes()),'previous_model_sha256':OLD_SHA,'artistAccepted':False,'native_libraries_unchanged':True}
    def save():(OUT/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),'utf-8')
    save();changed=False
    try:
        print('Installing PBR renderer; private head, NPU and preferences backed up',flush=True)
        changed=True;assert b'Success' in adb('install','-r',str(APK));assert apksha()==receipt['apk_sha256']
        deploy(HEAD/'character.glb',HEAD/'avatar.json');receipt['deployed']=True;save()
        previous=json.loads(private('files/tripo-head-preview-check.json')).get('run_id')
        adb('shell','am','start','-n',PACKAGE+'/.AvatarPreviewActivity','--ez','test_private_head','true','--ez','verify_private_head','true')
        print('Checking actual Mali normal/ORM rendering and 16-view pixel equivalence',flush=True)
        deadline=time.monotonic()+240
        while time.monotonic()<deadline:
            time.sleep(3)
            try:p=json.loads(private('files/tripo-head-preview-check.json'));m=json.loads(private('files/tripo-head-multiview-check.json'))
            except (ValueError,RuntimeError):continue
            if p.get('run_id')==previous or p.get('running',True):continue
            assert p.get('passed'),p.get('error','Render gate failed')
            if m.get('running',True):continue
            assert m.get('passed'),json.dumps(m)
            assert p['avatar']['model_sha256']==NEW_SHA
            for gate in ('serial_vs_ovr','individual_vs_batch'):assert m[gate]['avatar']['model_sha256']==NEW_SHA
            (OUT/'candidate-driver.json').write_text(json.dumps(p,ensure_ascii=False,indent=2),'utf-8');(OUT/'candidate-multiview.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),'utf-8')
            renders=OUT/'candidate-renders';renders.mkdir()
            for frame in p['renders']:
                b=private('files/'+frame['file']);assert sha(b)==frame['sha256'] and b.startswith(b'\x89PNG');(renders/(frame['name']+'.png')).write_bytes(b)
            receipt['driver_gates_passed']=True;save();break
        else:raise RuntimeError('Driver checks exceeded 240 seconds')
        launch();print('PBR preview resumed on live camera',flush=True)
        deadline=time.monotonic()+45;first=None
        while time.monotonic()<deadline:
            time.sleep(3)
            try:s=json.loads(private('files/mirror-runtime-status.json'))
            except ValueError:continue
            renderer=s.get('renderer') or {};avatar=renderer.get('avatar') or {}
            if s.get('input')!='camera' or s.get('error') or avatar.get('model_sha256')!=NEW_SHA:continue
            if first and first['runtime_session_id']==s['runtime_session_id'] and s['status_sequence']>first['status_sequence']:
                (OUT/'live-status.json').write_text(json.dumps(s,ensure_ascii=False,indent=2),'utf-8');receipt.update(camera_preview_advancing=True,face_present=s.get('face_present'),scene_view=s['renderer'].get('scene_view'),pbr=s['renderer']['avatar'].get('pbr_materials'));break
            first=s
        else:raise RuntimeError('No advancing live PBR preview')
        for name,path in CONFIGS.items():(OUT/('after-'+name)).write_bytes(private(path))
        receipt['configuration_byte_exact']=all((OUT/('before-'+name)).read_bytes()==(OUT/('after-'+name)).read_bytes() for name in CONFIGS)
        (OUT/'candidate-screen.png').write_bytes(adb('exec-out','screencap','-p'));receipt['candidate_left_for_review']=True;save();print(json.dumps(receipt,ensure_ascii=False),flush=True)
    except Exception as error:
        receipt['error']=str(error);save()
        if changed:
            try:
                adb('install','-r',str(OLDAPK));deploy(OUT/'before-character.glb',OUT/'before-avatar.json');launch();receipt['rollback_files_verified']=apksha()==sha(OLDAPK.read_bytes()) and sha(private('files/tripo-head-check/character.glb'))==OLD_SHA
            except Exception as rollback:receipt['rollback_error']=str(rollback)
        save();raise


if __name__=='__main__':main()
