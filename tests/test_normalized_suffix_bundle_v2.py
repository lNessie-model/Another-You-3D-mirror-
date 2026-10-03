"""Actual fixed payload provenance plus adversarial device-log fixtures; no ADB."""
import copy,json,shutil,sys,tempfile,unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import prepare_normalized_suffix_bundle_v2 as m

class BundleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work=tempfile.TemporaryDirectory();cls.source=Path(cls.work.name)/'frozen-bundle'
        m.prepare_bundle(cls.source)
    @classmethod
    def tearDownClass(cls):cls.work.cleanup()
    def setUp(self):
        self.workcase=tempfile.TemporaryDirectory();self.root=Path(self.workcase.name)
        self.bundle=self.root/'bundle';shutil.copytree(self.source,self.bundle)
        self.evidence=self.root/'evidence';self.evidence.mkdir();self.manifest=m.validate_bundle(self.bundle)
        self.values=np.fromfile(self.bundle/'references.f32',dtype='<f4').reshape(92,52)
        self.rows=[dict(event='start',schema_version=1,cases=92,cycles=1,warmup=5,input_floats=292,output_floats=52,runtime_path='/vendor/lib64/librknnrt.so',scope=m.SCOPE),
            dict(event='init',rc=0,flags=0,model_bytes=1209569,input_bytes=107456,elapsed_ms=1),
            dict(event='sdk',rc=0,api='1.3.0 host structural fixture',driver='0.7.2'),dict(event='io_count',rc=0,inputs=1,outputs=1)]
        for kind,name,dims,size in [('input',m.INPUT,[1,146,2],584),('output',m.FINAL,[52],104)]:
            self.rows.append(dict(event='tensor_attr',kind=kind,rc=0,index=0,name=name,n_dims=len(dims),dims=dims,n_elems=size//2,size=size,fmt=3,type=1,qnt_type=2,zp=0,scale=1,w_stride=0,size_with_stride=size))
        self.rows.append(dict(event='feed_contract',type=0,fmt=3,pass_through=0,want_float=1,input_order=m.INPUT_ORDER))
        for phase,count in [('warmup',5),('measurement',92)]:
            for i in range(count):
                a=self.values[i]
                self.rows.append(dict(event='iteration',phase=phase,iteration=i,fixture=i,cycle=0,
                    return_codes=dict(inputs_set=0,run=0,outputs_get=0,outputs_release=0),
                    timing_ms=dict(inputs_set=.1,run=.2,outputs_get=.1,outputs_release=.1,total=.5),output_bytes=208,
                    nonfinite=0,output_min=float(a.min()),output_max=float(a.max()),output_offset_floats=-1 if phase=='warmup' else i*52,ok=True))
        self.rows.append(dict(event='finish',status='success',exit_code=0,completed_measurements=92,expected_measurements=92,destroy_rc=0))
        (self.evidence/'native-exit.json').write_text('{"exit_code":0}')
        (self.evidence/'outputs.f32').write_bytes(self.values.tobytes());self.refresh()
    def tearDown(self):self.workcase.cleanup()
    def refresh(self):
        log=self.evidence/'report.jsonl';log.write_text(''.join(json.dumps(r)+'\n' for r in self.rows))
        before=''.join(self.manifest['files'][name]['sha256']+'  /isolated/'+name+'\n' for name in ['face_blendshapes.rknn','inputs.f32','rknn_blendshape_probe'])+m.core.RUNTIME_SHA+'  /vendor/lib64/librknnrt.so\n'
        (self.evidence/'before.sha256').write_text(before)
        (self.evidence/'after.sha256').write_text(before+m.core.file_sha(log)+'  /isolated/report.jsonl\n'+m.core.file_sha(self.evidence/'outputs.f32')+'  /isolated/outputs.f32\n')
    def test_real_bundle_and_exact_two_native_literals(self):
        self.assertEqual(self.manifest['case_count'],92)
        self.assertNotEqual(self.manifest['cases'][0]['raw_input_sha256'],self.manifest['cases'][0]['input_sha256'])
        old=(self.bundle/'provenance/original_native.bin').read_bytes();new=(self.bundle/'provenance/normalized_native.bin').read_bytes()
        m.verify_native_diff(old,new)
        with self.assertRaises(ValueError):m.verify_native_diff(old,new.replace(b'OUTPUT_FLOATS 52u',b'OUTPUT_FLOATS 51u'))
        r=m.check_bundle(self.bundle,self.evidence);self.assertTrue(r['passed']);self.assertFalse(r['application_eligible'])
    def test_external_native_exit_missing_or_failed_rejected(self):
        for payload in ['{"exit_code":134}','{"exit_code":false}']:
            (self.evidence/'native-exit.json').write_text(payload)
            with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
        (self.evidence/'native-exit.json').unlink()
        with self.assertRaises(OSError):m.check_bundle(self.bundle,self.evidence)
    def test_attrs_and_normalized_literals_strict(self):
        baseline=copy.deepcopy(self.rows)
        for index,key,value in [(4,'name','serving_default_input_points:0'),(5,'name','wrong'),(4,'type',0),(5,'type',0),(4,'size',1168),(5,'size',208),(4,'n_dims',2),(5,'dims',[1,52]),(4,'fmt',0),(5,'fmt',2),(4,'index',1),(6,'input_order',m.OLD_ORDER),(0,'scope',m.OLD_SCOPE)]:
            self.rows=copy.deepcopy(baseline);self.rows[index][key]=value;self.refresh()
            with self.subTest(key=key,value=value):
                with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
    def test_normalized_input_cannot_be_replaced_even_with_manifest_rehash(self):
        p=self.bundle/'inputs.f32';a=np.fromfile(p,dtype='<f4').astype('f2').astype('f4');p.write_bytes(a.tobytes())
        d=json.loads((self.bundle/'manifest.json').read_text());d['files']['inputs.f32']['sha256']=m.core.file_sha(p)
        (self.bundle/'manifest.json').write_text(json.dumps(d))
        with self.assertRaises(ValueError):m.validate_bundle(self.bundle)
    def test_changed_provenance_reference_and_case_semantics_rejected(self):
        d=json.loads((self.bundle/'manifest.json').read_text())
        for key in ['input_contract','accuracy_scope','cases']:
            mutated=copy.deepcopy(d)
            if key in ['input_contract','accuracy_scope']:mutated[key]=m.OLD_ORDER
            else:mutated[key][0]['raw_input_sha256']='0'*64
            (self.bundle/'manifest.json').write_text(json.dumps(mutated))
            with self.assertRaises(ValueError):m.validate_bundle(self.bundle)
        (self.bundle/'manifest.json').write_text(json.dumps(d))
        p=self.bundle/'provenance/original_validation.bin';data=bytearray(p.read_bytes());data[-2]^=1;p.write_bytes(data)
        with self.assertRaises(ValueError):m.validate_bundle(self.bundle)
    def test_core_numeric_gate_and_complete_output_not_bypassed(self):
        a=self.values.copy();a[0,0]=.5;(self.evidence/'outputs.f32').write_bytes(a.tobytes());self.refresh()
        self.assertFalse(m.check_bundle(self.bundle,self.evidence)['passed'])
        (self.evidence/'outputs.f32').write_bytes(a.tobytes()[:-4]);self.refresh()
        with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
    def test_core_failure_and_afterhash_are_required(self):
        self.rows[7]['return_codes']['outputs_release']=-1;self.refresh()
        with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
        self.rows[7]['return_codes']['outputs_release']=0;self.refresh()
        with (self.evidence/'outputs.f32').open('ab') as f:f.write(b'\0\0\0\0')
        with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
    def test_never_overwrite_bundle(self):
        with self.assertRaises(FileExistsError):m.prepare_bundle(self.bundle)

    def test_exact_observed_quantization_metadata(self):
        baseline=copy.deepcopy(self.rows)
        for index in (4,5):
            for key,value in [('qnt_type',0),('qnt_type',1),('qnt_type',3),('zp',1),('scale',0),('scale',.5),('scale',True),('type',True),('rc',False),('w_stride',1),('size_with_stride',999)]:
                self.rows=copy.deepcopy(baseline);self.rows[index][key]=value;self.refresh()
                with self.subTest(index=index,key=key,value=value):
                    with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
            for key in ('zp','scale','qnt_type','rc'):
                self.rows=copy.deepcopy(baseline);del self.rows[index][key];self.refresh()
                with self.subTest(index=index,missing=key):
                    with self.assertRaises(ValueError):m.check_bundle(self.bundle,self.evidence)
        self.rows=baseline;self.refresh()
        self.assertTrue(m.check_bundle(self.bundle,self.evidence)['passed'])
    def test_manifest_exact_profile_cannot_be_relabeled(self):
        path=self.bundle/'manifest.json';d=json.loads(path.read_text())
        d['tensor_attributes'][0]['qnt_type']=0;path.write_text(json.dumps(d))
        with self.assertRaises(ValueError):m.validate_bundle(self.bundle)

if __name__=='__main__':unittest.main()
