"""Fixed 92-case/five-tap diagnostic; no ADB, model rewriting, or application acceptance."""
import argparse
import json
import math
from pathlib import Path
import sys
from typing import NamedTuple
import numpy as np
from blendshape_device_check import (CHANNELS, RUNTIME_SHA, TFLITE_SHA, THRESHOLDS,
    sha, file_sha, read_json, write_json, require, hash_listing, distribution, errors)

KIND = 'fixed_front_end_five_tap_diagnostic'
LABELS = ('input_echo', 'centered', 'scale', 'normalized', 'final52')
NAMES = ('diagnostic_input_echo', 'model_1/tf.math.subtract/Sub',
    'model_1/tf.math.reduce_mean_1/Mean', 'model_1/tf.math.truediv_1/truediv', 'StatefulPartitionedCall:0')
INPUT_NAME = 'serving_default_input_points:0'
ELEMENTS = (292, 292, 1, 292, 52)
SHAPES = ([[1,146,2]], [[1,146,2],[1,146,2,1]], [[1,1,1],[1,1,1,1]], [[1,146,2],[1,146,2,1]], [[52]])
APIS = ('inputs_set', 'run', 'outputs_get', 'outputs_release')
DEVICE_FILES = ('front-taps.rknn', 'inputs.f32', 'rknn_tap_probe')
LOCAL_FILES = (*DEVICE_FILES, 'compiled-contract.json', 'original-tflite52.f32', *(f'reference-{s}.f32' for s in LABELS))
BASELINE_SHA = '66b35d13c997d989811bc3b9cd5477c4b2334939349673ea4fa51f45df3bcd11'


class TapProfile(NamedTuple):
    kind: str
    labels: tuple
    names: tuple
    elements: tuple
    shapes: tuple
    model: str
    graph: str
    echo: bool
    @property
    def device_files(self): return (self.model,'inputs.f32','rknn_tap_probe')
    @property
    def local_files(self): return (*self.device_files,'compiled-contract.json','original-tflite52.f32',*(f'reference-{s}.f32' for s in self.labels))
    @property
    def total(self): return sum(self.elements)


FRONT_PROFILE=TapProfile(KIND,LABELS,NAMES,ELEMENTS,SHAPES,'front-taps.rknn','front-taps.onnx',True)
STEM_PROFILE=TapProfile('fixed_stem_five_tap_diagnostic',
    ('normalized','stem_input','stem_projection','token_embedding','final52'),
    ('model_1/tf.math.truediv_1/truediv','diagnostic_stem_input','diagnostic_stem_projection',
     'model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/AddExtraTokens/concat','StatefulPartitionedCall:0'),
    (292,292,192,6208,52),([[1,146,2],[1,146,2,1]],[[1,146,1,2]],[[1,96,1,2]],[[1,64,1,97]],[[52]]),
    'stem-taps.rknn','stem-taps.onnx',False)
LN_PROFILE=TapProfile('fixed_first_layernorm_five_tap_diagnostic',
    ('token_embedding','mean','inv_std','affine','final52'),
    ('diagnostic_ln_token','diagnostic_ln_mean','diagnostic_ln_invstd','diagnostic_ln_affine','StatefulPartitionedCall:0'),
    (6208,97,97,6208,52),([[1,64,1,97]],[[1,1,1,97]],[[1,1,1,97]],[[1,1,97,64]],[[52]]),
    'ln-taps.rknn','ln-taps.onnx',False)
SCALE_PROFILE=TapProfile('fixed_first_layernorm_scale_five_tap_diagnostic',
    ('gamma_conv','restore_scale','x_scaled','negative_mean_scaled','final52'),
    ('diagnostic_scale_gamma','diagnostic_scale_restored','diagnostic_scale_x','diagnostic_scale_negmean','StatefulPartitionedCall:0'),
    (6208,6208,6208,6208,52),([[1,64,97,1]],[[1,1,97,64]],[[1,1,97,64]],[[1,1,97,64]],[[52]]),
    'scale-taps.rknn','scale-taps.onnx',False)


def profile_for_kind(kind):
    for profile in (FRONT_PROFILE,STEM_PROFILE,LN_PROFILE,SCALE_PROFILE):
        if kind==profile.kind:return profile
    raise ValueError('Unknown fixed tap profile')


def fixed_contract(profile=FRONT_PROFILE):
    result=dict(schema_version=1, kind=profile.kind, case_count=92, warmup=5,
        input=dict(name=INPUT_NAME, allowed_runtime_shapes=[[1,146,2]], allowed_runtime_types=[1],
            allowed_runtime_formats=[3], elements=292, feed_type=0, pass_through=0, feed_format='queried_format'),
        outputs=[dict(index=i,label=label,name=profile.names[i],elements=profile.elements[i],
            allowed_runtime_names=[profile.names[i]],allowed_runtime_shapes=[list(s) for s in profile.shapes[i]],allowed_runtime_types=[1],
            want_float=1,returned_bytes=profile.elements[i]*4,device_output_file=f'output-{label}.f32') for i,label in enumerate(profile.labels)])
    if profile in (STEM_PROFILE,LN_PROFILE,SCALE_PROFILE):
        result['output_elements_per_case']=profile.total
        for output in result['outputs']:output['allowed_runtime_formats']=[0,1,3]
    return result


def validate_contract(c):
    profile=profile_for_kind(c.get('kind'));expected=fixed_contract(profile)
    for key in ('schema_version','kind','case_count','warmup'): require(c.get(key)==expected[key],f'Unknown contract {key}')
    if profile in (STEM_PROFILE,LN_PROFILE,SCALE_PROFILE):
        require(c.get('output_elements_per_case')==profile.total,'Wrong fixed profile capacity')
    for key,value in expected['input'].items(): require(c.get('input',{}).get(key)==value,f'Input contract {key}')
    require(len(c.get('outputs',[]))==5,'Expected exactly five tap outputs')
    for i,(actual,wanted) in enumerate(zip(c['outputs'],expected['outputs'])):
        for key,value in wanted.items(): require(actual.get(key)==value,f'Tap {i} contract {key}')
    return profile


def load_floats(path, elements):
    require(Path(path).stat().st_size==92*elements*4,f'Wrong float byte count: {path}')
    return np.fromfile(path,dtype='<f4').reshape(92,elements)


def validate_cases(cases, inputs, reference):
    require(len(cases)==92,'Expected 92 cases')
    result=[]
    for i,c in enumerate(cases):
        name=c.get('fixture','')
        group='real' if name.startswith(('npu-','reference-')) else 'synthetic' if name.startswith('synthetic-') else None
        require(group is not None,f'Unknown fixture category {name}')
        require(c.get('input_sha256')==sha(inputs[i].tobytes()) and c.get('reference_sha256')==sha(reference[i].tobytes()),f'Case {i} hash mismatch')
        result.append(dict(index=i,fixture=name,group=group,input_sha256=c['input_sha256'],reference_sha256=c['reference_sha256']))
    require(sum(c['group']=='real' for c in result)==72 and sum(c['group']=='synthetic' for c in result)==20,'Expected real72 / synthetic20')
    return result


def prepare_bundle(source, compilation, compiler_log, validation, model, helper, bundle):
    source,compilation,compiler_log,validation,model,helper,bundle=map(Path,(source,compilation,compiler_log,validation,model,helper,bundle))
    require(not bundle.exists(),'Bundle already exists; never overwrite')
    contract_path=source/'compiled-contract.json'; c=read_json(contract_path); profile=validate_contract(c)
    p=read_json(source/'diagnostic.json'); compiled=read_json(compilation); v=read_json(validation)
    require(c.get('acceptance',{}).get('final52')==THRESHOLDS and c['acceptance'].get('model_acceptance') is False and c['acceptance'].get('intermediate_metrics_only') is True,'Changed diagnostic acceptance')
    require(c.get('prepare_report_sha256')==file_sha(source/'diagnostic.json') and c.get('compile_report_sha256')==file_sha(compilation) and c.get('compiler_host_log_sha256')==file_sha(compiler_log),'Diagnostic provenance hashes do not match')
    require(p.get('status')=='prepared' and p.get('case_count')==92 and p.get('input_shape')==[1,146,2] and p.get('input_elements')==292 and p.get('output_elements_per_case')==profile.total,'Invalid preparation')
    require(p.get('full52_instrumentation_changed_cases')==0 and p.get('full52_instrumentation_max_abs')==0,'Tap graph changed candidate final output')
    require(compiled.get('status')=='compiled' and compiled.get('manifest_sha256')==file_sha(source/'diagnostic.json') and all(compiled.get('steps',{}).get(s,{}).get('rc')==0 for s in ('config','load','build','export')),'Compilation incomplete or unrelated')
    require(compiled.get('model_sha256')==p.get('model_sha256')==file_sha(source/profile.graph),'Compiled ONNX graph mismatch')
    require(c.get('model_sha256')==compiled.get('artifact_sha256')==file_sha(model) and c.get('model_bytes')==compiled.get('artifact_bytes')==model.stat().st_size,'RKNN hash/size mismatch')
    require(p.get('source_validation_sha256')==file_sha(validation) and v.get('status')=='passed' and v.get('source_sha256')==TFLITE_SHA and v.get('reference')=='original TFLite, no rewritten reference','Unverified original TFLite reference')
    require(c['input'].get('file')=='inputs.f32' and c['input'].get('sha256')==p.get('input_sha256')==file_sha(source/'inputs.f32'),'Input hash mismatch')
    inputs=load_floats(source/'inputs.f32',292)
    require(np.isfinite(inputs).all(),'Nonfinite inputs')
    require(c.get('original_tflite52',{}).get('file')=='original-tflite52.f32','Unexpected TFLite reference filename')
    require(c['original_tflite52'].get('sha256')==p.get('reference_tflite52_sha256')==file_sha(source/'original-tflite52.f32'),'Original TFLite reference hash mismatch')
    original=load_floats(source/'original-tflite52.f32',52)
    require(np.isfinite(original).all() and np.all((original>=-1e-5)&(original<=1+1e-5)),'Invalid original TFLite reference values')
    cases=validate_cases(v.get('cases',[]),inputs,original)
    require(len(p.get('outputs',[]))==5,'Missing preparation outputs')
    for i,o in enumerate(c['outputs']):
        path=source/f'reference-{profile.labels[i]}.f32'; po=p['outputs'][i]
        require(o.get('file')==path.name and po.get('file')==path.name and po.get('name')==profile.names[i] and po.get('index')==i and po.get('elements')==profile.elements[i] and po.get('shape')==profile.shapes[i][0],'Reference identity mismatch')
        require(o.get('sha256')==po.get('sha256')==file_sha(path) and o.get('bytes')==po.get('bytes')==92*profile.elements[i]*4,'Reference hash/size mismatch')
        require(np.isfinite(load_floats(path,profile.elements[i])).all(),'Nonfinite ONNX reference')
    if profile.echo: require((source/'reference-input_echo.f32').read_bytes()==inputs.tobytes(),'Reference echo changed input order')
    require(errors(load_floats(source/'reference-final52.f32',52),original)['max_absolute_error']<=1e-5,'Diagnostic ONNX failed original FP32 gate')
    require(helper.read_bytes().startswith(b'\x7fELF'),'Helper must be compiled ELF')
    provenance_paths=[contract_path,source/'diagnostic.json',compilation,compiler_log,validation,model,helper,source/profile.graph]
    if profile in (STEM_PROFILE,LN_PROFILE,SCALE_PROFILE):
        metadata_path=helper.parent/'build.json';metadata=read_json(metadata_path)
        build_profile='stem' if profile is STEM_PROFILE else 'ln' if profile is LN_PROFILE else 'scale'
        require(metadata.get('profile')==build_profile and metadata.get('helper_sha256')==file_sha(helper) and metadata.get('bytes')==helper.stat().st_size,'Requires matching fixed '+build_profile+' helper build metadata')
        provenance_paths.append(metadata_path)
    payload={profile.model:model.read_bytes(),'rknn_tap_probe':helper.read_bytes()}
    payload.update({name:(source/name).read_bytes() for name in profile.local_files if name not in payload})
    manifest=dict(schema_version=1,kind=profile.kind,contract=fixed_contract(profile),thresholds=THRESHOLDS,
        runtime_sha256=RUNTIME_SHA,reference_tflite_sha256=TFLITE_SHA,cases=cases,
        scope='Diagnostic only; extra graph outputs may alter RKNN optimization; never application acceptance',
        output_formats_guard=[0,1,3],output_order='Query index and exact name/shape; preserve C-order, no transpose',
        provenance={str(path.resolve()):file_sha(path) for path in provenance_paths},
        files={name:dict(sha256=sha(data),bytes=len(data)) for name,data in payload.items()})
    bundle.mkdir(parents=True,exist_ok=False)
    for name,data in payload.items(): (bundle/name).write_bytes(data)
    write_json(bundle/'manifest.json',manifest)
    return manifest


def metrics(actual,reference):
    a=np.asarray(actual,dtype=np.float64); r=np.asarray(reference,dtype=np.float64)
    result=errors(a,r); finite=np.isfinite(a)&np.isfinite(r)
    eligible=finite&(np.abs(r)>1e-12)
    ratio=a[eligible]/r[eligible]
    result['ratio']=dict(excluded_values=int(a.size-ratio.size),denominator_min_absolute=1e-12,**(distribution(ratio) if ratio.size else dict(count=0)))
    relative=np.abs(a[eligible]-r[eligible])/np.abs(r[eligible])
    result['relative_error']=distribution(relative) if relative.size else dict(count=0)
    values=a[np.isfinite(a)]
    result['minimum']=float(values.min()) if values.size else None
    result['maximum']=float(values.max()) if values.size else None
    return result


def accuracy(actual,reference,cases,warmup_ok):
    m=metrics(actual,reference)
    in_range=bool(np.isfinite(actual).all() and ((actual>=-1e-5)&(actual<=1+1e-5)).all())
    passed=bool(warmup_ok and in_range and m['max_absolute_error']<=.01 and m['mean_absolute_error']<=.002)
    return dict(passed=passed,thresholds=THRESHOLDS,finite_and_range=in_range,warmup_finite_and_range=warmup_ok,metrics=m,
        groups={g:metrics(actual[[c['group']==g for c in cases]],reference[[c['group']==g for c in cases]]) for g in ('real','synthetic')},
        channels=[dict(index=i,name=name,**errors(actual[:,i],reference[:,i])) for i,name in enumerate(CHANNELS)])


class Events:
    def __init__(self,rows): self.rows=rows;self.index=0;self.sequence=0;self.timings={}
    def take(self,event,**expected):
        require(self.index<len(self.rows),f'Missing {event}')
        row=self.rows[self.index];self.index+=1
        require(row.get('event')==event,f'Expected {event}, got {row.get("event")} at row {self.index}')
        for key,value in expected.items(): require(row.get(key)==value,f'{event}: wrong {key} at row {self.index}')
        return row
    def api(self,api,phase='setup',iteration=-1,tensor=-1):
        values=dict(sequence=self.sequence,phase=phase,iteration=iteration,tensor=tensor,api=api)
        self.take('api_begin',**values); end=self.take('api_end',rc=0,**values)
        elapsed=end.get('elapsed_ms');require(type(elapsed) in (int,float) and math.isfinite(elapsed) and elapsed>=0,'Invalid API timing')
        if phase=='measurement': self.timings.setdefault(api,[]).append(elapsed)
        self.sequence+=1


def validate_attr(row,index,input_tensor=False,profile=FRONT_PROFILE):
    shapes=[[1,146,2]] if input_tensor else profile.shapes[index]
    name=INPUT_NAME if input_tensor else profile.names[index]; n=292 if input_tensor else profile.elements[index]
    require(row.get('name')==name and row.get('dims') in shapes and row.get('n_dims')==len(row['dims']),'Tensor name/shape/rank mismatch; no layout guessing')
    require(row.get('n_elems')==n and row.get('size')==n*2 and row.get('type')==1,'Tensor type/size mismatch')
    require(row.get('fmt') in ([3] if input_tensor else [0,1,3]),'Unknown/packed tensor format')


def compare_bundle(bundle,evidence,baseline_output=None):
    bundle,evidence=map(Path,(bundle,evidence)); manifest=read_json(bundle/'manifest.json')
    require(manifest.get('schema_version')==1,'Unknown bundle')
    profile=profile_for_kind(manifest.get('kind'));require(validate_contract(manifest.get('contract',{})) is profile,'Bundle contract profile mismatch')
    require(manifest.get('thresholds')==THRESHOLDS and manifest.get('runtime_sha256')==RUNTIME_SHA and manifest.get('reference_tflite_sha256')==TFLITE_SHA,'Changed thresholds/runtime/reference pin')
    require(set(manifest.get('files',{}))==set(profile.local_files),'Unexpected bundle files')
    for name,entry in manifest['files'].items():
        require(file_sha(bundle/name)==entry['sha256'] and (bundle/name).stat().st_size==entry['bytes'],f'Local bundle hash/size: {name}')
    require(validate_contract(read_json(bundle/'compiled-contract.json')) is profile,'Compiled contract profile mismatch')
    refs=[load_floats(bundle/f'reference-{label}.f32',n) for label,n in zip(profile.labels,profile.elements)]
    inputs=load_floats(bundle/'inputs.f32',292); original=load_floats(bundle/'original-tflite52.f32',52)
    require(np.isfinite(inputs).all() and np.isfinite(original).all() and all(np.isfinite(r).all() for r in refs),'Nonfinite bundle reference')
    cases=validate_cases(manifest.get('cases',[]),inputs,original)
    require(cases==manifest['cases'],'Case group/index mismatch')
    before,after=(hash_listing(evidence/name) for name in ('before.sha256','after.sha256'))
    for name in (*profile.device_files,'librknnrt.so'):
        expected=RUNTIME_SHA if name=='librknnrt.so' else manifest['files'][name]['sha256']
        require(before.get(name)==expected and after.get(name)==expected,f'Device before/after hash mismatch: {name}')
    actual=[]
    for label,n in zip(profile.labels,profile.elements):
        path=evidence/f'output-{label}.f32'
        require(after.get(path.name)==file_sha(path),f'Pulled {path.name} hash mismatch')
        actual.append(load_floats(path,n))
    log=evidence/'report.jsonl'
    require(after.get(log.name)==file_sha(log),'Pulled native report hash mismatch')
    require(read_json(evidence/'native-exit.json').get('exit_code')==0,'Native process exit must independently be zero')
    require(log.stat().st_size<=4*1024*1024,'Oversized fixed-case report')
    def invalid_constant(x): raise ValueError(f'Non-JSON constant {x}')
    rows=[json.loads(line,parse_constant=invalid_constant) for line in log.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    e=Events(rows)
    e.take('start',schema_version=1,kind=profile.kind,cases=92,warmup=5,input_floats=292,outputs=5,output_floats_per_case=profile.total,runtime_path='/vendor/lib64/librknnrt.so',performance_evidence=False)
    e.take('files_loaded',model_bytes=manifest['files'][profile.model]['bytes'],input_bytes=92*292*4)
    e.api('init');e.api('query_sdk');sdk=e.take('sdk')
    require(sdk.get('api','').startswith('1.3.0') and sdk.get('driver','').startswith('0.7.2'),'Unvalidated SDK/driver')
    e.api('query_io_count');e.take('io_count',inputs=1,outputs=5)
    e.api('query_input_attr',tensor=0);validate_attr(e.take('tensor_attr',kind='input',index=0),0,True)
    attrs=[]
    for i in range(5):
        e.api('query_output_attr',tensor=i);a=e.take('tensor_attr',kind='output',index=i);validate_attr(a,i,profile=profile);attrs.append(a)
    e.take('feed_contract',type=0,fmt=3,pass_through=0,want_float=1,input_floats=292,output_count=5,ordering='C-order unchanged; no transpose')
    warmup_ok=True
    for phase,count in [('warmup',5),('measurement',92)]:
        for i in range(count):
            for api in APIS: e.api(api,phase,i)
            for j,n in enumerate(profile.elements):
                row=e.take('tensor_result',phase=phase,iteration=i,fixture=i,index=j,returned_index=j,label=profile.labels[j],file=f'output-{profile.labels[j]}.f32',elements=n,returned_bytes=4*n,offset_floats=-1 if phase=='warmup' else i*n,valid_buffer=True)
                nf=row.get('nonfinite');lo=row.get('minimum');hi=row.get('maximum')
                require(type(nf) is int and 0<=nf<=n,'Invalid nonfinite count')
                require((nf==n and lo is None and hi is None) or (nf<n and type(lo) in (int,float) and type(hi) in (int,float) and math.isfinite(lo) and math.isfinite(hi) and lo<=hi),'Invalid tensor extrema')
                if phase=='measurement':
                    values=actual[j][i];finite=values[np.isfinite(values)]
                    require(nf==int(n-finite.size),'Raw nonfinite count differs from log')
                    if finite.size: require(np.float32(lo)==finite.min() and np.float32(hi)==finite.max(),'Raw extrema differ from log')
                elif j==4: warmup_ok &= nf==0 and lo>=-1e-5 and hi<=1+1e-5
            e.take('iteration',phase=phase,iteration=i,fixture=i,complete=True,return_codes={a:0 for a in APIS})
    e.api('destroy','cleanup');e.take('finish',status='success',exit_code=0,completed_measurements=92,expected_measurements=92,destroy_rc=0)
    require(e.index==len(rows),'Unexpected trailing events')
    echo_result=dict(available=False,scope='No input echo in this fixed stem profile; no echo correctness claim')
    if profile is LN_PROFILE:
        echo_result['scope']='No input echo in this fixed first-LN profile; no echo correctness claim'
    if profile is SCALE_PROFILE:
        echo_result['scope']='No input echo in this fixed first-LN scale profile; no echo correctness claim'
    if profile.echo:
        echo=inputs.astype('<f2').astype('<f4')
        echo_result=dict(bitwise_equal=actual[0].tobytes()==echo.tobytes(),mismatched_values=int((actual[0].view('<u4')!=echo.view('<u4')).sum()),expected='F32 input -> F16 -> F16 output -> F32, no permutation',metrics=metrics(actual[0],echo))
    taps=[]
    for j,label in enumerate(profile.labels):
        taps.append(dict(index=j,label=label,metrics=metrics(actual[j],refs[j]),
            groups={g:metrics(actual[j][[c['group']==g for c in cases]],refs[j][[c['group']==g for c in cases]]) for g in ('real','synthetic')},
            cases=[dict(index=i,fixture=c['fixture'],group=c['group'],output_sha256=sha(actual[j][i].tobytes()),reference_sha256=sha(refs[j][i].tobytes()),**errors(actual[j][i],refs[j][i])) for i,c in enumerate(cases)]))
    result=dict(status='diagnostic_complete',execution_completed=True,application_eligible=False,performance_evidence=False,
        scope='Fixed graph with extra outputs; intermediate errors are measurements, not acceptance gates. Graph changes can alter RKNN optimization.',
        contract=manifest['contract'],thresholds=THRESHOLDS,sdk=sdk,output_attrs=attrs,
        echo=echo_result,
        taps=taps,final52=dict(original_tflite=accuracy(actual[4],original,cases,warmup_ok),diagnostic_onnx=accuracy(actual[4],refs[4],cases,warmup_ok)),
        diagnostic_api_timing_ms={a:distribution(v) for a,v in e.timings.items()},
        hashes={str(p.resolve()):file_sha(p) for p in [bundle/'manifest.json',evidence/'before.sha256',evidence/'after.sha256',evidence/'native-exit.json',log,*[evidence/f'output-{s}.f32' for s in profile.labels]]})
    if baseline_output is not None:
        baseline_output=Path(baseline_output);require(file_sha(baseline_output)==BASELINE_SHA,'Unexpected original failed-v4 baseline hash')
        baseline=load_floats(baseline_output,52)
        result['original_failed_v4_comparison']=dict(reference_sha256=BASELINE_SHA,metrics=metrics(actual[4],baseline),bitwise_equal=actual[4].tobytes()==baseline.tobytes(),scope='Diagnostic only; changed output graph can change RKNN compilation; not a pass gate')
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('prepare')
    for key in ('source','compilation','compiler-log','validation','model','helper','bundle'): p.add_argument('--'+key,required=True,type=Path)
    c=sub.add_parser('check')
    for key in ('bundle','evidence','output'): c.add_argument('--'+key,required=True,type=Path)
    c.add_argument('--baseline-output',type=Path)
    a=parser.parse_args()
    if a.command=='prepare':
        manifest=prepare_bundle(a.source,a.compilation,a.compiler_log,a.validation,a.model,a.helper,a.bundle)
        print(json.dumps(dict(bundle=str(a.bundle),manifest_sha256=file_sha(a.bundle/'manifest.json'),files=manifest['files']),indent=2));return 0
    try:
        report=compare_bundle(a.bundle,a.evidence,a.baseline_output)
        code=0 if report['echo'].get('bitwise_equal',report['echo'].get('available') is False) and all(g['passed'] for g in report['final52'].values()) else 1
    except (ValueError,KeyError,OSError,TypeError) as error:
        report=dict(status='error',execution_completed=False,application_eligible=False,error=str(error));code=2
    write_json(a.output,report)
    print(json.dumps(dict(status=report['status'],application_eligible=False,report=str(a.output),exit_code=code)));return code


if __name__=='__main__': sys.exit(main())
