"""Fault injection of the real deployment main; subprocess is an in-memory ADB only."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT=Path(__file__).resolve().parents[1]/'scripts'/'preview_face_feedback.py'
spec=importlib.util.spec_from_file_location('preview_face_feedback_under_test',SCRIPT)
deployment=importlib.util.module_from_spec(spec);spec.loader.exec_module(deployment)


class FakeAdb:
    def __init__(self,root,mode):
        self.mode=mode;self.now=0;self.stage='before';self.live_reads=0;self.stopped=False
        self.head=root/'head';self.head.mkdir();self.output=root/'evidence'
        self.candidate=root/'candidate.apk';self.previous=root/'previous.apk'
        for path,dex in [(self.candidate,b'new dex'),(self.previous,b'old dex')]:
            with zipfile.ZipFile(path,'w') as apk:
                apk.writestr('lib/arm64-v8a/example.so',b'unchanged native')
                apk.writestr('classes.dex',dex)
        (self.head/'character.glb').write_bytes(b'new model')
        self.model_sha=deployment.sha(b'new model')
        (self.head/'avatar.json').write_text(json.dumps({'modelSha256':self.model_sha}),'utf-8')
        self.files={'files/tripo-head-check/character.glb':b'old model',
                    'files/tripo-head-check/avatar.json':b'{"old_manifest":true}',
                    **{path:('original '+label).encode() for label,path in deployment.CONFIGS.items()}}
        self.before=dict(self.files);self.installed=self.previous.read_bytes();self.staging={}

    def sleep(self,seconds):self.now+=seconds

    def report(self,path):
        if path=='files/tripo-head-preview-check.json':
            report={'run_id':'before' if self.stage=='before' else 'fresh',
                    'running':False,'passed':self.mode!='driver_failure',
                    'avatar':{'model_sha256':self.model_sha},'renders':[]}
            if self.mode=='missing_preview_id' and self.stage!='before':report.pop('run_id')
            return report
        if path=='files/tripo-head-multiview-check.json':
            return {'run_id':'before' if self.mode=='stale_multiview' else 'fresh',
                    'running':False,'passed':True,
                    **{name:{'avatar':{'model_sha256':self.model_sha}}
                       for name in ['serial_vs_ovr','individual_vs_batch']}}
        if path=='files/mirror-runtime-status.json':
            self.live_reads+=self.stage=='live'
            return {'input':'camera','view_count':16,'view_width':400,'view_height':640,
                    'debug_npu_blendshapes':True,'runtime_session_id':'fresh-session',
                    'status_sequence':self.live_reads,'face_present':False,
                    'renderer':{'avatar':None if self.stage=='before' or self.live_reads==1 else {'model_sha256':self.model_sha},
                                'runtime_gl_frame_ready':self.live_reads>1,
                                'face_playback':{'response_policy':'artist_response_v3'}}}
        raise AssertionError('Unexpected private path: '+path)

    def run(self,command,**kwargs):
        assert command[:3]==[deployment.ADB,'-s',deployment.SERIAL]
        args=command[3:];result=b'';code=0
        if args[0]=='install':
            self.installed=Path(args[2]).read_bytes();self.stopped=True
            if self.mode=='install_failure' and Path(args[2])==self.candidate:
                # Failure after a partial package transition must still recover
                # saved bytes, even if a migration touched them before failing.
                for path in deployment.CONFIGS.values():self.files[path]=b'partial install migration'
                code=1
            else:result=b'Success'
        elif args[0]=='push':self.staging[args[2]]=Path(args[1]).read_bytes()
        elif args[:3]==['shell','pm','path']:result=b'package:/data/app/base.apk\n'
        elif args[:2]==['shell','sha256sum']:result=(deployment.sha(self.installed)+'  /data/app/base.apk\n').encode()
        elif args[:2]==['exec-out','screencap']:result=b'fake screenshot'
        elif args[:4]==['exec-out','run-as',deployment.PACKAGE,'cat']:
            path=args[4];result=self.files[path] if path in self.files else json.dumps(self.report(path)).encode()
        elif args[:4]==['shell','run-as',deployment.PACKAGE,'cp']:
            if args[5] in deployment.CONFIGS.values():
                assert self.stopped,'Configuration restored while the app is running'
                assert self.installed==self.previous.read_bytes(),'Configuration restored before the rollback APK'
            self.files[args[5]]=self.staging[args[4]]
        elif args[:3]==['shell','am','start']:
            self.stopped=False
            activity=args[4]
            if activity.endswith('/.AvatarPreviewActivity'):self.stage='driver'
            else:
                self.stage='live' if self.installed==self.candidate.read_bytes() else 'rollback'
                if self.stage=='live' and self.mode=='config_migration':
                    for path in deployment.CONFIGS.values():self.files[path]=b'candidate migrated preferences'
            result=b'Status: ok'
        elif args[:3]==['shell','am','force-stop']:self.stopped=True
        elif args[:3]!=['shell','mkdir','-p']:
            raise AssertionError('Unexpected ADB operation: '+repr(args))
        return subprocess.CompletedProcess(command,code,result,b'injected failure' if code else b'')

    def execute(self):
        argv=['preview_face_feedback','--head',str(self.head),'--apk',str(self.candidate),
              '--out',str(self.output),'--current-apk',str(self.previous),
              '--current-head-sha256',deployment.sha(b'old model')]
        # This is the only subprocess boundary; no real executable is invoked.
        with patch.object(deployment.subprocess,'run',side_effect=self.run),patch.object(sys,'argv',argv),\
             patch.object(deployment.time,'monotonic',side_effect=lambda:self.now),\
             patch.object(deployment.time,'sleep',side_effect=self.sleep),contextlib.redirect_stdout(io.StringIO()):
            deployment.main()


class DeploymentFailureTests(unittest.TestCase):
    def assert_rollback(self,fake):
        self.assertEqual(fake.installed,fake.previous.read_bytes())
        self.assertEqual(fake.files,fake.before,'Model, manifest and all three configurations must recover byte exactly')
        receipt=json.loads((fake.output/'receipt.json').read_text('utf-8'))
        self.assertFalse(receipt['passed']);self.assertTrue(receipt['rollback_verified'])

    def test_install_failure_restores_all_saved_state(self):
        with tempfile.TemporaryDirectory() as directory:
            fake=FakeAdb(Path(directory),'install_failure')
            with self.assertRaises(RuntimeError):fake.execute()
            self.assert_rollback(fake)

    def test_fresh_driver_failure_restores_all_saved_state(self):
        with tempfile.TemporaryDirectory() as directory:
            fake=FakeAdb(Path(directory),'driver_failure')
            with self.assertRaises(AssertionError):fake.execute()
            self.assert_rollback(fake)

    def test_candidate_configuration_migration_is_reverted(self):
        with tempfile.TemporaryDirectory() as directory:
            fake=FakeAdb(Path(directory),'config_migration')
            with self.assertRaisesRegex(AssertionError,'Saved configuration'):fake.execute()
            self.assert_rollback(fake)

    def test_loading_null_avatar_is_waited_for(self):
        with tempfile.TemporaryDirectory() as directory:
            fake=FakeAdb(Path(directory),'normal');fake.execute()
            receipt=json.loads((fake.output/'receipt.json').read_text('utf-8'))
            self.assertTrue(receipt['passed']);self.assertGreaterEqual(fake.live_reads,3)
            self.assertEqual(fake.installed,fake.candidate.read_bytes())

    def test_stale_same_model_multiview_report_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            fake=FakeAdb(Path(directory),'stale_multiview')
            with self.assertRaisesRegex(RuntimeError,'Driver gates timed out'):fake.execute()
            self.assert_rollback(fake)

    def test_completed_preview_without_run_id_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            fake=FakeAdb(Path(directory),'missing_preview_id')
            with self.assertRaisesRegex(RuntimeError,'Driver gates timed out'):fake.execute()
            self.assert_rollback(fake)


if __name__=='__main__':unittest.main()
