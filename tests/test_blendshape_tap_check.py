"""Synthetic evidence exercises the real fixed-tap checker without RKNN/ADB."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import blendshape_tap_check as t


class TapCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.bundle = self.root / 'bundle'; self.bundle.mkdir()
        self.evidence = self.root / 'evidence'; self.evidence.mkdir()
        self.arrays = [np.full((92, n), 0.125, dtype='<f4') for n in t.ELEMENTS]
        self.arrays[0][:] = np.arange(292, dtype=np.float32) / 13
        self.actual = [a.copy() for a in self.arrays]
        self.actual[0] = self.actual[0].astype('<f2').astype('<f4')
        contract = t.fixed_contract()
        files = {'front-taps.rknn': b'model', 'rknn_tap_probe': b'\x7fELFhelper',
                 'compiled-contract.json': json.dumps(contract).encode(),
                 'inputs.f32': self.arrays[0].tobytes(), 'original-tflite52.f32': self.arrays[4].tobytes()}
        files.update({f'reference-{label}.f32': a.tobytes() for label, a in zip(t.LABELS, self.arrays)})
        self.manifest = dict(schema_version=1, kind=t.KIND, contract=contract,
            runtime_sha256=t.RUNTIME_SHA, reference_tflite_sha256=t.TFLITE_SHA,
            thresholds=t.THRESHOLDS, cases=[dict(index=i,fixture=('npu-' if i<72 else 'synthetic-')+str(i),group='real' if i<72 else 'synthetic',
            input_sha256=t.sha(self.arrays[0][i].tobytes()),reference_sha256=t.sha(self.arrays[4][i].tobytes())) for i in range(92)],
            files={name:dict(sha256=t.sha(data),bytes=len(data)) for name,data in files.items()})
        for name,data in files.items(): (self.bundle/name).write_bytes(data)
        self.write_manifest()
        self.rows=[]; self.seq=0
        def row(event, **kwargs): self.rows.append(dict(event=event,**kwargs))
        self.row=row
        row('start',schema_version=1,kind=t.KIND,cases=92,warmup=5,input_floats=292,outputs=5,output_floats_per_case=929,runtime_path='/vendor/lib64/librknnrt.so',performance_evidence=False)
        row('files_loaded',model_bytes=5,input_bytes=92*292*4)
        self.api('init'); self.api('query_sdk'); row('sdk',api='1.3.0',driver='0.7.2')
        self.api('query_io_count'); row('io_count',inputs=1,outputs=5)
        self.api('query_input_attr',tensor=0)
        row('tensor_attr',kind='input',index=0,name=t.INPUT_NAME,n_dims=3,dims=[1,146,2],n_elems=292,size=584,fmt=3,type=1)
        for i in range(5):
            self.api('query_output_attr',tensor=i)
            row('tensor_attr',kind='output',index=i,name=t.NAMES[i],n_dims=len(t.SHAPES[i][0]),dims=t.SHAPES[i][0],n_elems=t.ELEMENTS[i],size=2*t.ELEMENTS[i],fmt=3,type=1)
        row('feed_contract',type=0,fmt=3,pass_through=0,want_float=1,input_floats=292,output_count=5,ordering='C-order unchanged; no transpose')
        for phase,count in [('warmup',5),('measurement',92)]:
            for i in range(count):
                for api in t.APIS: self.api(api,phase,i)
                for j,n in enumerate(t.ELEMENTS):
                    a=self.actual[j][i]
                    row('tensor_result',phase=phase,iteration=i,fixture=i,index=j,returned_index=j,label=t.LABELS[j],file=f'output-{t.LABELS[j]}.f32',elements=n,returned_bytes=4*n,offset_floats=-1 if phase=='warmup' else i*n,valid_buffer=True,nonfinite=0,minimum=float(a.min()),maximum=float(a.max()))
                row('iteration',phase=phase,iteration=i,fixture=i,complete=True,return_codes={api:0 for api in t.APIS})
        self.api('destroy','cleanup')
        row('finish',status='success',exit_code=0,completed_measurements=92,expected_measurements=92,destroy_rc=0)
        self.write_evidence()

    def tearDown(self): self.tmp.cleanup()
    def write_manifest(self): (self.bundle/'manifest.json').write_text(json.dumps(self.manifest))
    def api(self,name,phase='setup',iteration=-1,tensor=-1):
        common=dict(sequence=self.seq,phase=phase,iteration=iteration,tensor=tensor,api=name)
        self.rows.extend([dict(event='api_begin',**common),dict(event='api_end',rc=0,elapsed_ms=.125,**common)])
        self.seq+=1
    def write_evidence(self):
        for label,a in zip(t.LABELS,self.actual): (self.evidence/f'output-{label}.f32').write_bytes(a.astype('<f4').tobytes())
        (self.evidence/'report.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in self.rows))
        (self.evidence/'native-exit.json').write_text('{"exit_code":0}')
        hashes={name:v['sha256'] for name,v in self.manifest['files'].items() if name in t.DEVICE_FILES}
        hashes['librknnrt.so']=t.RUNTIME_SHA
        (self.evidence/'before.sha256').write_text(''.join(f'{v}  /tmp/{k}\n' for k,v in hashes.items()))
        hashes.update({p.name:t.file_sha(p) for p in self.evidence.glob('output-*.f32')})
        hashes['report.jsonl']=t.file_sha(self.evidence/'report.jsonl')
        (self.evidence/'after.sha256').write_text(''.join(f'{v}  /tmp/{k}\n' for k,v in hashes.items()))
    def compare(self): return t.compare_bundle(self.bundle,self.evidence)
    def selected(self,event,**fields): return next(r for r in self.rows if r['event']==event and all(r.get(k)==v for k,v in fields.items()))
    def test_complete_is_diagnostic_not_model_acceptance(self):
        r=self.compare(); self.assertTrue(r['execution_completed']); self.assertTrue(r['final52']['original_tflite']['passed'])
        self.assertFalse(r['application_eligible']); self.assertTrue(r['echo']['bitwise_equal']); self.assertEqual(len(r['taps']),5)
        self.assertEqual(len(r['taps'][4]['cases']),92); self.assertEqual(len(r['final52']['original_tflite']['channels']),52)
    def test_final_large_error_is_complete_but_failed(self):
        self.actual[4][88,11]=.99; self.update_extrema(4,88); self.write_evidence()
        r=self.compare(); self.assertTrue(r['execution_completed']); self.assertFalse(r['final52']['original_tflite']['passed'])
    def update_extrema(self,j,i):
        r=self.selected('tensor_result',phase='measurement',iteration=i,index=j); a=self.actual[j][i]; finite=a[np.isfinite(a)]
        r.update(nonfinite=int((~np.isfinite(a)).sum()),minimum=float(finite.min()) if finite.size else None,maximum=float(finite.max()) if finite.size else None)
    def test_nonfinite_intermediate_is_reported_not_hidden(self):
        self.actual[1][7,9]=np.nan; self.update_extrema(1,7); self.write_evidence()
        r=self.compare(); self.assertEqual(r['taps'][1]['metrics']['nonfinite_values'],1); self.assertNotIn('passed',r['taps'][1])
        json.dumps(r,allow_nan=False)
    def test_nonfinite_final_fails(self):
        self.actual[4][7,9]=np.inf; self.update_extrema(4,7); self.write_evidence(); self.assertFalse(self.compare()['final52']['original_tflite']['passed'])
    def test_echo_mismatch_is_not_masked_by_tolerance(self):
        self.actual[0][0,1]=np.nextafter(self.actual[0][0,1],np.float32(1)); self.write_evidence()
        self.assertFalse(self.compare()['echo']['bitwise_equal'])
    def test_rejects_native_nonzero_even_success_footer(self):
        (self.evidence/'native-exit.json').write_text('{"exit_code":16}')
        with self.assertRaisesRegex(ValueError,'process'): self.compare()
    def test_report_hash_required(self):
        (self.evidence/'report.jsonl').write_text((self.evidence/'report.jsonl').read_text().replace('0.125','0.126',1))
        with self.assertRaisesRegex(ValueError,'report'): self.compare()
    def test_output_hash_required(self):
        p=self.evidence/'output-scale.f32'; p.write_bytes(b'\0'*368)
        with self.assertRaisesRegex(ValueError,'output-scale'): self.compare()
    def test_structural_mutations(self):
        original=copy.deepcopy(self.rows)
        mutations=[('tensor_attr',{'kind':'input'},'fmt',2),('tensor_attr',{'kind':'input'},'dims',[1,2,146]),
            ('tensor_attr',{'kind':'output','index':1},'name',t.NAMES[3]),('tensor_attr',{'kind':'output','index':2},'type',0),
            ('tensor_attr',{'kind':'output','index':0},'size',1168),('tensor_attr',{'kind':'output','index':0},'n_dims',4),
            ('tensor_attr',{'kind':'output','index':0},'fmt',2),('tensor_attr',{'kind':'output','index':0},'dims',[146,2]),
            ('feed_contract',{},'pass_through',1),('tensor_result',{'phase':'measurement','iteration':91,'index':4},'offset_floats',0),
            ('tensor_result',{'phase':'measurement','iteration':0,'index':0},'returned_bytes',584),
            ('tensor_result',{'phase':'warmup','iteration':0,'index':0},'valid_buffer',False),
            ('api_end',{'phase':'warmup','iteration':0,'api':'outputs_release'},'rc',-1),
            ('api_end',{'phase':'measurement','iteration':0,'api':'run'},'elapsed_ms',-1),
            ('finish',{},'completed_measurements',91)]
        for event,where,key,value in mutations:
            with self.subTest(event=event,key=key,value=value):
                self.rows=copy.deepcopy(original); self.selected(event,**where)[key]=value; self.write_evidence()
                with self.assertRaises(ValueError): self.compare()
    def test_missing_or_reordered_event_rejected(self):
        original=copy.deepcopy(self.rows)
        for event in ['api_begin','api_end','tensor_result','iteration','finish']:
            with self.subTest(event=event):
                self.rows=copy.deepcopy(original); self.rows.remove(next(r for r in self.rows if r['event']==event)); self.write_evidence()
                with self.assertRaises(ValueError): self.compare()
    def test_allowed_terminal_singleton_only(self):
        for i in [1,2,3]:
            r=self.selected('tensor_attr',kind='output',index=i);r['dims']=t.SHAPES[i][1];r['n_dims']=4
        self.write_evidence(); self.assertTrue(self.compare()['execution_completed'])
    def test_rejects_truncated_tensor_even_new_hash(self):
        self.actual[1]=self.actual[1][:-1];self.write_evidence()
        with self.assertRaises(ValueError): self.compare()
    def test_bundle_local_hash_required(self):
        (self.bundle/'reference-final52.f32').write_bytes(b'bad')
        with self.assertRaises(ValueError): self.compare()
    def test_threshold_cannot_be_relaxed(self):
        self.manifest['thresholds']=dict(t.THRESHOLDS,max_absolute_error=1);self.write_manifest()
        with self.assertRaises(ValueError): self.compare()
    def test_raw_extrema_must_match_log(self):
        self.selected('tensor_result',phase='measurement',iteration=10,index=1)['maximum']=99;self.write_evidence()
        with self.assertRaises(ValueError): self.compare()
    def test_ratio_excludes_zero_and_counts_it(self):
        m=t.metrics(np.array([0.,1.,np.nan]),np.array([0.,.5,1.]));self.assertEqual(m['ratio']['excluded_values'],2)
        self.assertEqual(m['ratio']['mean'],2)
    def test_baseline_hash_pin_required(self):
        p=self.root/'baseline.f32';p.write_bytes(self.actual[4].tobytes())
        with self.assertRaisesRegex(ValueError,'baseline'): t.compare_bundle(self.bundle,self.evidence,p)

    def preparation(self,name,change=None):
        base=self.root/name;base.mkdir();source=base/'source';source.mkdir()
        for filename in ['inputs.f32','original-tflite52.f32',*[f'reference-{s}.f32' for s in t.LABELS]]:
            (source/filename).write_bytes((self.bundle/filename).read_bytes())
        graph=source/'front-taps.onnx';graph.write_bytes(b'synthetic graph for provenance tests')
        model=base/'model.rknn';model.write_bytes(b'model');helper=base/'helper';helper.write_bytes(b'\x7fELFhelper')
        log=base/'compiler.log';log.write_text('compiler log')
        validation=base/'validation.json'
        v=dict(status='passed',source_sha256=t.TFLITE_SHA,reference='original TFLite, no rewritten reference',cases=copy.deepcopy(self.manifest['cases']))
        c=t.fixed_contract();c['acceptance']=dict(final52=t.THRESHOLDS,model_acceptance=False,intermediate_metrics_only=True)
        c['input'].update(file='inputs.f32',sha256=t.file_sha(source/'inputs.f32'))
        c['original_tflite52']=dict(file='original-tflite52.f32',sha256=t.file_sha(source/'original-tflite52.f32'))
        for i,o in enumerate(c['outputs']):
            filename=f'reference-{t.LABELS[i]}.f32';o.update(file=filename,sha256=t.file_sha(source/filename),bytes=92*t.ELEMENTS[i]*4,shape=t.SHAPES[i][0])
        p=dict(status='prepared',case_count=92,input_shape=[1,146,2],input_elements=292,output_elements_per_case=929,
            full52_instrumentation_changed_cases=0,full52_instrumentation_max_abs=0,model_sha256=t.file_sha(graph),
            input_sha256=c['input']['sha256'],reference_tflite52_sha256=c['original_tflite52']['sha256'],outputs=copy.deepcopy(c['outputs']))
        comp=dict(status='compiled',steps={s:dict(rc=0) for s in ('config','load','build','export')},
            model_sha256=t.file_sha(graph),artifact_sha256=t.file_sha(model),artifact_bytes=5)
        if change: change(c,p,comp,v)
        validation.write_text(json.dumps(v));p['source_validation_sha256']=t.file_sha(validation)
        (source/'diagnostic.json').write_text(json.dumps(p));comp['manifest_sha256']=t.file_sha(source/'diagnostic.json')
        compilation=base/'compilation.json';compilation.write_text(json.dumps(comp))
        c.update(model_sha256=t.file_sha(model),model_bytes=5,prepare_report_sha256=t.file_sha(source/'diagnostic.json'),
            compile_report_sha256=t.file_sha(compilation),compiler_host_log_sha256=t.file_sha(log))
        (source/'compiled-contract.json').write_text(json.dumps(c))
        return [source,compilation,log,validation,model,helper,base/'new-bundle']

    def test_prepare_validates_then_copies_exact_data_no_overwrite(self):
        args=self.preparation('prepare-good');m=t.prepare_bundle(*args)
        self.assertEqual(m['files']['inputs.f32']['sha256'],t.file_sha(args[0]/'inputs.f32'))
        self.assertEqual((args[-1]/'original-tflite52.f32').read_bytes(),self.arrays[4].tobytes())
        with self.assertRaisesRegex(ValueError,'already exists'):t.prepare_bundle(*args)

    def test_prepare_rejects_semantically_wrong_but_rehashed_chain(self):
        mutations=[lambda c,p,co,v:c.update(schema_version=2),
            lambda c,p,co,v:c['input'].update(allowed_runtime_formats=[1,3]),
            lambda c,p,co,v:c['outputs'][1].update(allowed_runtime_shapes=[[1,2,146]]),
            lambda c,p,co,v:p.update(full52_instrumentation_changed_cases=1),
            lambda c,p,co,v:co['steps']['build'].update(rc=-1),
            lambda c,p,co,v:v.update(reference='rewritten model'),
            lambda c,p,co,v:v['cases'][0].update(input_sha256='f'*64),
            lambda c,p,co,v:p['outputs'][3].update(name='wrong-name'),
            lambda c,p,co,v:c['acceptance'].update(model_acceptance=True)]
        for i,change in enumerate(mutations):
            with self.subTest(index=i):
                args=self.preparation('wrong'+str(i),change)
                with self.assertRaises(ValueError):t.prepare_bundle(*args)
                self.assertFalse(args[-1].exists(),'Must reject before creating partial bundle')

    def test_prepare_rejects_stale_compile_log_and_model(self):
        for position in [1,2,3,4,5]:
            with self.subTest(position=position):
                args=self.preparation('stale'+str(position));args[position].write_bytes(b'changed')
                with self.assertRaises((ValueError,json.JSONDecodeError)):t.prepare_bundle(*args)
                self.assertFalse(args[-1].exists())

if __name__=='__main__': unittest.main()
