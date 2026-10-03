"""Bounded real-board atlas preview; always restores pinned v20 APK and replay.

No UI clicks, system/driver changes, preference edits or stored-avatar selection.
"""
from pathlib import Path
import argparse,datetime,hashlib,json,shutil,subprocess,sys,time,uuid
ROOT=Path(__file__).resolve().parents[1]
assert datetime.datetime.now(datetime.timezone.utc)<datetime.datetime(2026,10,3,22,30,tzinfo=datetime.timezone.utc),'Deadline handoff window: no new installs'
ADB=r'C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe'
SERIAL='6L32552009566714';PACKAGE='com.mirror.bench'
BASE=Path(r'E:\tripo\output\mirror-program\20261003\400x640\AvatarRuntime-v20-production-npu.apk')
BASE_SHA='21ee8a3d11e94c0ac399740364f4ba235ceea8b54d177d9bfcda44b56b01b3da'
parser=argparse.ArgumentParser(description='Reversible app-private head diagnostic; does not select a stored avatar')
parser.add_argument('--head',type=Path,default=Path(r'E:\tripo\assets\mirror-models-20261003\heads-v1\geralt-rig-stage1'))
parser.add_argument('--benchmark-seconds',type=int,default=0,choices=[0,60,90,180],help='Optional complete-face replay plus actual head interlacing, separate from diagnostic readbacks')
parser.add_argument('--avatar-batched',action='store_true',help='Compare the existing opt-in packed draw backend in the optional benchmark')
parser.add_argument('--active-target-fps',type=int,default=31,choices=[31,35],help='Existing debug frame-pacing override; never saves preferences')
parser.add_argument('--view-count',type=int,default=16,choices=[16,20],help='Runtime benchmark count; frozen driver smoke comparisons always use 16 views')
args=parser.parse_args();HEAD=args.head.resolve()
if args.avatar_batched and not args.benchmark_seconds:parser.error('Batch experiment requires a runtime benchmark')
assert (HEAD/'character.glb').is_file() and (HEAD/'avatar.json').is_file()
HEAD_MANIFEST=json.loads((HEAD/'avatar.json').read_text('utf-8'))
assert HEAD_MANIFEST['model']=='character.glb'
assert HEAD_MANIFEST['modelSha256']==hashlib.sha256((HEAD/'character.glb').read_bytes()).hexdigest()
OUT=ROOT/'app/build'/('v22-albedo-device-'+str(uuid.uuid4()));OUT.mkdir()
PIN=Path(r'E:\tripo\output\mirror-program\20261004');PIN.mkdir(parents=True,exist_ok=True)
CANDIDATE=PIN/('AvatarRuntime-v22-albedo-'+OUT.name[-36:]+'.apk')
shutil.copyfile(ROOT/'app/build/outputs/apk/debug/app-debug.apk',CANDIDATE)
def sha(data):return hashlib.sha256(data).hexdigest()
assert sha(BASE.read_bytes())==BASE_SHA
commands=[];report={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'candidate_apk':str(CANDIDATE),'candidate_sha256':sha(CANDIDATE.read_bytes()),'head_sha256':sha((HEAD/'character.glb').read_bytes()),'passed':False,'rollback_verified':False,'scope':'Actual-board preview and private-head 9-pose driver smoke gates; optional complete-face joint runtime benchmark reported separately. Artwork acceptance remains false.'}
report.update(head_source=str(HEAD),manifest_sha256=sha((HEAD/'avatar.json').read_bytes()),normal_policy=HEAD_MANIFEST['normalPolicy'],runtime_view_count=args.view_count,driver_smoke_view_count=16)
def save():
    (OUT/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    (OUT/'commands.json').write_text(json.dumps(commands,ensure_ascii=False,indent=2),'utf-8')
def adb(*args,check=True):
    p=subprocess.run([ADB,'-s',SERIAL,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
    binary=args[0]=='exec-out' and args[-1].endswith('.png')
    commands.append({'args':list(args),'returncode':p.returncode,'stdout':('<PNG '+str(len(p.stdout))+' bytes>') if binary else p.stdout.decode('utf-8','replace'),'stderr':p.stderr.decode('utf-8','replace')});save()
    if check and p.returncode:raise RuntimeError('ADB failed: '+str(args))
    return p.stdout
def installed_sha():
    path=adb('shell','pm','path',PACKAGE).decode().strip();assert path.startswith('package:') and '\n' not in path
    return adb('shell','sha256sum',path[len('package:'):]).decode().split()[0]
def private(name):return adb('exec-out','run-as',PACKAGE,'cat',name)
def replay():
    adb('shell','am','force-stop',PACKAGE);adb('shell','am','start','-n',PACKAGE+'/.MirrorActivity','--es','runtime_input','replay')
try:
    assert adb('get-state').strip()==b'device'
    assert installed_sha()==BASE_SHA,'Unexpected installed baseline; preserve it instead of replacing'
    prefs=private('shared_prefs/mirror-runtime.xml');selection=private('files/avatars/state.json');json.loads(selection)
    (OUT/'preferences-before.xml').write_bytes(prefs);(OUT/'selection-before.json').write_bytes(selection)
    status=json.loads(private('files/mirror-runtime-status.json'));(OUT/'status-before.json').write_text(json.dumps(status,ensure_ascii=False,indent=2),'utf-8')
    report['baseline_preferences_sha256']=sha(prefs);report['baseline_selection_sha256']=sha(selection);save()
    report['candidate_install_attempted']=True;save()
    adb('install','-r',str(CANDIDATE));assert installed_sha()==report['candidate_sha256'];report['candidate_installed']=True;save()
    staging='/data/local/tmp/tripo-head-check-'+str(uuid.uuid4());adb('shell','mkdir','-p',staging)
    adb('shell','run-as',PACKAGE,'mkdir','-p','files/tripo-head-check')
    for name in ['character.glb','avatar.json']:
        adb('push',str(HEAD/name),staging+'/'+name)
        adb('shell','run-as',PACKAGE,'sh','-c',"'cat "+staging+'/'+name+' > files/tripo-head-check/'+name+"'")
        assert sha(private('files/tripo-head-check/'+name))==sha((HEAD/name).read_bytes())
    report['preview_started_host_epoch']=time.time();save()
    previous=adb('exec-out','run-as',PACKAGE,'cat','files/tripo-head-preview-check.json',check=False)
    try:previous_id=json.loads(previous).get('run_id')
    except ValueError:previous_id=None
    adb('shell','am','start','-n',PACKAGE+'/.AvatarPreviewActivity','--ez','test_private_head','true','--ez','verify_private_head','true')
    limit=time.monotonic()+300;result=None;multi=None
    while time.monotonic()<limit:
        time.sleep(3)
        raw=adb('exec-out','run-as',PACKAGE,'cat','files/tripo-head-preview-check.json',check=False)
        try:result=json.loads(raw)
        except (ValueError,UnicodeError):continue
        if result.get('run_id')==previous_id:continue
        if not result.get('running',True):
            if not result.get('passed'):break
            raw=adb('exec-out','run-as',PACKAGE,'cat','files/tripo-head-multiview-check.json',check=False)
            try:multi=json.loads(raw)
            except ValueError:continue
            if multi.get('run_id')==result.get('run_id') and not multi.get('running',True):break
    assert result and not result.get('running',True),'Preview did not finish in bounded time'
    (OUT/'preview-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    assert result.get('passed'),result.get('error','Preview failed')
    assert multi and not multi.get('running',True),'Head multiview gate did not finish'
    (OUT/'head-multiview-result.json').write_text(json.dumps(multi,ensure_ascii=False,indent=2),'utf-8')
    assert multi.get('passed'),multi.get('error','Head multiview or batch comparison failed')
    for gate in ['serial_vs_ovr','individual_vs_batch']:
        assert multi[gate]['avatar']['model_sha256']==report['head_sha256'],'Wrong multiview head'
    assert result['avatar']['model_sha256']==report['head_sha256'],'Wrong head loaded'
    assert len(result['renders'])==10
    frames=OUT/'renders';frames.mkdir()
    for frame in result['renders']:
        name=frame['name'];assert name.replace('-','').isalpha()
        png=private('files/'+frame['file']);assert sha(png)==frame['sha256']
        (frames/(name+'.png')).write_bytes(png)
    report['passed']=True;report['driver_geometry_passed']=True;report['visual_acceptance']=False;save()
    if args.benchmark_seconds:
        report['passed']=False;save()
        runtime=OUT/'runtime';name='head'+str(args.view_count)+'-npu-'+str(args.benchmark_seconds)+'s'
        argv=[sys.executable,'-X','utf8','-B',str(ROOT/'scripts/run_runtime_check.py'),'--name',name,'--input','replay','--seconds',str(args.benchmark_seconds),'--output-dir',str(runtime),
              '--private-head','--npu-blendshapes','--view-count',str(args.view_count),'--view-preset','400x640','--persistent-fbos','--active-target-fps',str(args.active_target_fps)]
        if args.avatar_batched:argv.append('--avatar-batched')
        (OUT/'runtime-command.json').write_text(json.dumps(argv,ensure_ascii=False,indent=2),'utf-8')
        with (OUT/'runtime-stdout.log').open('wb') as stdout,(OUT/'runtime-stderr.log').open('wb') as stderr:
            process=subprocess.run(argv,cwd=ROOT,stdout=stdout,stderr=stderr,timeout=args.benchmark_seconds+180)
        measurement=json.loads((runtime/(name+'.json')).read_text('utf-8'))
        assert process.returncode==0 and measurement['collection_status']=='completed','Runtime collection failed'
        assert measurement['apk_sha256']==report['candidate_sha256'],'Wrong runtime APK'
        active=[s for s in measurement['statuses'] if s.get('state')=='INTERACTIVE' and s.get('face_present')]
        assert len(active)>=2,'No advancing complete-face active status samples'
        for value in active:
            renderer=value['renderer'];avatar=renderer['avatar']
            assert avatar['model_sha256']==report['head_sha256'],'Runtime rendered a different model'
            assert renderer['avatar_source']=='private_head_test','Runtime used stored/builtin fallback'
            assert avatar['normal_policy']==HEAD_MANIFEST['normalPolicy'],'Runtime used a different normal policy'
            assert renderer['avatar_batched_requested']==args.avatar_batched and avatar['draw_backend']==('batched' if args.avatar_batched else 'individual'),'Wrong draw backend'
            assert renderer['render_target_fps']==args.active_target_fps,'Wrong frame-pacing experiment'
            assert value['view_count']==args.view_count and value['metrics']['landmarks']==478 and value['metrics']['blendshapes']==52,'Incomplete runtime workload'
        assert active[-1]['status_sequence']>active[0]['status_sequence'],'Runtime did not advance'
        report['runtime_measurement']=str(runtime/(name+'.json'));report['performance_measured']=True
        report['meets_30_fps']=measurement['presentation']['meets_30_fps'];report['draw_backend']='batched' if args.avatar_batched else 'individual'
        report['passed']=True;save()
except Exception as error:
    report['error']=str(error);save()
finally:
    if report.get('candidate_install_attempted'):
        try:
            adb('install','-r',str(BASE));assert installed_sha()==BASE_SHA
            assert sha(private('shared_prefs/mirror-runtime.xml'))==report['baseline_preferences_sha256']
            assert sha(private('files/avatars/state.json'))==report['baseline_selection_sha256']
            replay();limit=time.monotonic()+90;first=None;second=None
            while time.monotonic()<limit:
                time.sleep(3);value=json.loads(private('files/mirror-runtime-status.json'))
                if value.get('face_present') and value.get('input')=='replay' and not value.get('error'):
                    if first and value['runtime_session_id']==first['runtime_session_id'] and value['status_sequence']>first['status_sequence']:
                        second=value;break
                    first=value
            assert first and second,'No two advancing face-present replay snapshots within 90 seconds'
            for label,value in [('rollback-first',first),('rollback-second',second)]:
                (OUT/(label+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),'utf-8')
                assert value['input']=='replay' and not value.get('error') and not value.get('render_fault') and not value.get('processing_fault')
            assert second['status_sequence']>first['status_sequence'] and second['face_present']
            report['rollback_verified']=True
        except Exception as error:report['rollback_error']=str(error)
    report['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat();save()
print(json.dumps({'evidence':str(OUT),'passed':report['passed'],'rollback_verified':report['rollback_verified'],'error':report.get('error'),'rollback_error':report.get('rollback_error')},ensure_ascii=False))
if not (report['passed'] and report['rollback_verified']):raise SystemExit(1)
