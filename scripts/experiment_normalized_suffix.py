"""Fixed all8-copy suffix, fed original FP32 normalization. Offline RKNN only.

No device target, no graph rewrite, no new observation outputs, no threshold
relaxation. This is a separate experiment, not a replacement application model.
"""
import argparse
import json
from pathlib import Path
import sys
import time
import traceback
import numpy as np
from compile_blendshape_loweropt import _toolkit, sha256, utc_now
from diagnose_blendshape_numerics import require, save, load_frozen
from diagnose_normalized_boundary import (BOUNDARY as INPUT, FINAL, SOURCE_SHA, COPIED_SHA,
    VALIDATION_SHA, split_fixed_boundary, run_rows, session, metric, digest)

VARIANT = 'all8_copy_normalized_fp32_front_suffix_v1'
BOUNDARY_REPORT_SHA = 'dcde869aebbfd5fd3c160dfe906e5ef75bec2c0d97c775cccaba69847000cbc4'
BOUNDARY_FREEZE_SHA = 'b9c93eece793b1a35a1018c23d001fc24b285a98eb3d228e34b260f5b651851e'
MODEL_SHA = '61c61dda91356545c9397906b6872bcfc52849b796355b6f44cb03de72c6af9b'
INPUT_SHA = 'ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b'
TF_SHA = '5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602'
FP32_SHA = '4bfeeebea37c598573edee17f7e3daae07d3a8cc29c18056e663e25d7d93057d'
BOUNDARY_HELPER_SHA = '3f0c615ba0b691c92df4a4498e41f7bdb2b6967d1beed965eb48205800a3d57a'
TOOLKIT_API_SHA = '7ba303d017f4874757e00ac87d570e49e15403fe67ed21bcdef137e4bd670ace'
CONFIG = dict(target_platform='rk3566', float_dtype='float16', optimization_level=3)
PAYLOADS = [('candidate.onnx', MODEL_SHA, None), ('inputs.f32', INPUT_SHA, 92*292*4),
            ('original-tflite52.f32', TF_SHA, 92*52*4), ('reference-fp32-final52.f32', FP32_SHA, 92*52*4)]


def verify_boundary_source(path):
    require(sha256(Path(__file__).with_name('diagnose_normalized_boundary.py')) == BOUNDARY_HELPER_SHA,
            'Frozen boundary helper changed')
    require(sha256(path) == BOUNDARY_REPORT_SHA and sha256(path.parent/'freeze.json') == BOUNDARY_FREEZE_SHA,
            'Boundary experiment not the reviewed frozen version')
    frozen = json.loads((path.parent/'freeze.json').read_text(encoding='utf-8'))
    for row in frozen['files']:
        p = path.parent/row['file']
        require(p.stat().st_size == row['bytes'] and sha256(p) == row['sha256'], 'Frozen boundary file changed: '+row['file'])
    report = json.loads(path.read_text(encoding='utf-8'))
    require(report['status'] == 'diagnostic_complete' and report['equivalence_gate'] is True
            and report['original_reference_gate'] is True and report['case_count'] == 92,
            'Missing original FP32 equivalence proof')
    return report


def prepare(boundary_path, validation_path, output):
    import onnx
    output.mkdir(parents=True, exist_ok=False)
    boundary = verify_boundary_source(boundary_path)
    require(sha256(validation_path) == VALIDATION_SHA, 'Changed original TF validation')
    original_path, raw_inputs, tflite = load_frozen(validation_path, 'gamma-conv-mulpow')
    require(sha256(original_path) == SOURCE_SHA, 'Changed original graph')
    original = onnx.load(str(original_path)); front, _ = split_fixed_boundary(original)
    copied_path = next(Path(row['path']) for row in boundary['source_files'] if row['sha256'] == COPIED_SHA)
    require(sha256(copied_path) == COPIED_SHA, 'Changed all8 copied graph')
    copied = onnx.load(str(copied_path)); _, suffix = split_fixed_boundary(copied)
    candidate = boundary_path.parent/'copied_suffix.onnx'
    require(sha256(candidate) == MODEL_SHA and candidate.read_bytes() == suffix.SerializeToString(), 'Frozen suffix differs from exact partition')
    inputs = run_rows(session(front), raw_inputs, 'serving_default_input_points:0', (1,146,2))
    require(digest(inputs.tobytes()) == INPUT_SHA and inputs.tobytes() == (boundary_path.parent/'normalized_fp32.f32').read_bytes(),
            'Original FP32 normalization changed')
    base = run_rows(session(original), raw_inputs, 'serving_default_input_points:0', (52,))
    copied_full = run_rows(session(copied), raw_inputs, 'serving_default_input_points:0', (52,))
    result = run_rows(session(suffix), inputs, INPUT, (52,))
    require(result.tobytes() == base.tobytes() == copied_full.tobytes() and digest(base.tobytes()) == FP32_SHA,
            'Split FP32 final52 differs from original unsplit')
    full_metric = metric(result, tflite, list(range(92)))
    require(full_metric['original_1e_5_gate'] and digest(tflite.tobytes()) == TF_SHA, 'Original TFLite gate failed')
    (output/'candidate.onnx').write_bytes(candidate.read_bytes())
    for name, data in [('inputs.f32',inputs), ('original-tflite52.f32',tflite), ('reference-fp32-final52.f32',result)]:
        (output/name).write_bytes(data.astype('<f4',copy=False).tobytes())
    validation = json.loads(validation_path.read_text(encoding='utf-8'))
    cases = [dict(index=i,fixture=c['fixture'],group='real' if i<72 else 'synthetic',
                  original_input_sha256=c['input_sha256'],original_reference_sha256=c['reference_sha256'],
                  normalized_input_sha256=digest(inputs[i].tobytes())) for i,c in enumerate(validation['cases'])]
    save(output/'cases.json',cases)
    report = dict(status='prepared',model_variant=VARIANT,script_sha256=sha256(Path(__file__)),
        boundary_report=str(boundary_path),boundary_report_sha256=BOUNDARY_REPORT_SHA,boundary_freeze_sha256=BOUNDARY_FREEZE_SHA,
        source_validation=str(validation_path),source_validation_sha256=VALIDATION_SHA,source_candidate_sha256=SOURCE_SHA,
        copied_candidate_sha256=COPIED_SHA,model_sha256=MODEL_SHA,input_name=INPUT,input_shape=[1,146,2],input_elements=292,
        input_dtype='FLOAT32',input_semantics='original FP32 normalized coordinates, unchanged adjacent XY order; not raw pixels or pre-rounded half',
        output_name=FINAL,output_shape=[52],case_count=92,cases_sha256=sha256(output/'cases.json'),
        input_sha256=INPUT_SHA,reference_tflite52_sha256=TF_SHA,reference_fp32_sha256=FP32_SHA,
        atol=1e-5,rtol=0,full_chain_byte_identity=True,original_tflite_max_abs=full_metric['max_abs'],
        original_tflite_mean_abs=full_metric['mean_abs'],suffix_nodes=218,initializers=len(suffix.graph.initializer),
        graph_scope='Exact frozen copied_suffix bytes; only original 8-node normalization runs outside the candidate',
        application_eligible=False,android_accuracy_validated=False,performance_evidence=False,finished_utc=utc_now())
    save(output/'diagnostic.json',report)
    return report


def validate_prepared(path):
    m = json.loads(path.read_text(encoding='utf-8'))
    require(m['status']=='prepared' and m['model_variant']==VARIANT and m['script_sha256']==sha256(Path(__file__))
            and m['model_sha256']==MODEL_SHA and m['input_sha256']==INPUT_SHA and m['reference_tflite52_sha256']==TF_SHA
            and m['reference_fp32_sha256']==FP32_SHA and m['input_name']==INPUT and m['input_shape']==[1,146,2]
            and m['input_elements']==292 and m['input_dtype']=='FLOAT32' and m['output_name']==FINAL and m['output_shape']==[52]
            and m['case_count']==92 and m['full_chain_byte_identity'] is True and m['original_tflite_max_abs']<=1e-5
            and m['atol']==1e-5 and m['rtol']==0 and m['source_candidate_sha256']==SOURCE_SHA
            and m['copied_candidate_sha256']==COPIED_SHA, 'Invalid fixed normalized suffix manifest')
    boundary_path = Path(m['boundary_report']); verify_boundary_source(boundary_path)
    require(m['boundary_report_sha256']==BOUNDARY_REPORT_SHA and m['boundary_freeze_sha256']==BOUNDARY_FREEZE_SHA,
            'Changed boundary provenance')
    validation_path = Path(m['source_validation'])
    require(m['source_validation_sha256']==VALIDATION_SHA and sha256(validation_path)==VALIDATION_SHA, 'Changed original validation')
    for filename, expected_hash, size in PAYLOADS:
        p=path.parent/filename
        require(sha256(p)==expected_hash and (size is None or p.stat().st_size==size), 'Payload changed: '+filename)
    inputs=np.fromfile(str(path.parent/'inputs.f32'),dtype='<f4').reshape(92,1,146,2)
    expected=np.fromfile(str(path.parent/'original-tflite52.f32'),dtype='<f4').reshape(92,52)
    cases_path=path.parent/'cases.json';require(sha256(cases_path)==m['cases_sha256'],'Case file changed')
    cases=json.loads(cases_path.read_text(encoding='utf-8'));original_cases=json.loads(validation_path.read_text(encoding='utf-8'))['cases']
    require(len(cases)==92,'Changed case count')
    for i,(c,old) in enumerate(zip(cases,original_cases)):
        require(c==dict(index=i,fixture=old['fixture'],group='real' if i<72 else 'synthetic',
                       original_input_sha256=old['input_sha256'],original_reference_sha256=old['reference_sha256'],
                       normalized_input_sha256=digest(inputs[i].tobytes())), 'Case order/identity mismatch')
    require(np.isfinite(inputs).all() and np.isfinite(expected).all(),'Nonfinite fixture')
    return m,path.parent/'candidate.onnx',inputs,expected


def run_backend(mode, manifest, output):
    require(mode in ('compile','simulate'),'Unknown offline mode')
    output.mkdir(parents=True,exist_ok=False)
    report=dict(status='running',mode=mode,model_variant=VARIANT,started_utc=utc_now(),command=sys.argv,
        script_sha256=sha256(Path(__file__)),application_eligible=False,android_accuracy_validated=False,
        performance_evidence=False,config=CONFIG.copy(),build_config=dict(do_quantization=False),steps={},
        scope='Offline normalized suffix only; no device target or application model replacement')
    runtime=None
    def flush(): save(output/'diagnostic.json',report)
    flush()
    try:
        m,model,inputs,expected=validate_prepared(manifest)
        report.update(manifest_sha256=sha256(manifest),model_sha256=MODEL_SHA,input_sha256=INPUT_SHA,
                      original_tflite52_sha256=TF_SHA,input_name=INPUT,input_shape=[1,146,2],output_name=FINAL,case_count=92)
        factory,report['toolkit']=_toolkit()
        require(report['toolkit']['distribution_version']=='1.3.0-11912b58'
                and report['toolkit']['api_sha256']==TOOLKIT_API_SHA,'Unexpected toolkit identity')
        runtime=factory(verbose=True,verbose_file=str(output/'backend.log'))
        actions=[('config',lambda:runtime.config(**CONFIG)),('load',lambda:runtime.load_onnx(model=str(model))),
                 ('build',lambda:runtime.build(do_quantization=False))]
        artifact=output/'normalized-suffix.rknn'
        if mode=='compile':actions.append(('export',lambda:runtime.export_rknn(str(artifact))))
        else:actions.append(('simulator_init',lambda:runtime.init_runtime(target=None)))
        for name,action in actions:
            report['active_step']=name;flush();start=time.monotonic();rc=action()
            report['steps'][name]=dict(rc=rc,wall_s=time.monotonic()-start);flush()
            require(rc==0,name+' returned '+str(rc))
        if mode=='compile':
            require(artifact.is_file() and artifact.stat().st_size>0,'Empty RKNN export')
            report.update(status='compiled',artifact_sha256=sha256(artifact),artifact_bytes=artifact.stat().st_size)
        else:
            report['active_step']='simulator_inference';report['completed_cases']=0;flush();rows=[]
            for value in inputs:
                result=runtime.inference(inputs=[value],inputs_pass_through=[0])
                require(isinstance(result,list) and len(result)==1 and result[0].dtype==np.float32
                        and result[0].shape==(52,), 'Simulator output contract mismatch')
                rows.append(result[0].copy());report['completed_cases']=len(rows)
            actual=np.stack(rows);p=output/'simulator-final52.f32';p.write_bytes(actual.astype('<f4',copy=False).tobytes())
            report.update(output_sha256=sha256(p),output_bytes=p.stat().st_size,target=None,
                          nonfinite_values=int(np.count_nonzero(~np.isfinite(actual))))
            # Preserve full raw output even if it is nonfinite; JSON remains valid.
            require(np.isfinite(actual).all(),'Simulator produced nonfinite values')
            original_fp32=np.fromfile(str(manifest.parent/'reference-fp32-final52.f32'),dtype='<f4').reshape(92,52)
            groups=dict(all=list(range(92)),real=list(range(72)),synthetic=list(range(72,92)))
            report['vs_original_tflite']={group:metric(actual[ids],expected[ids],ids) for group,ids in groups.items()}
            report['vs_fp32_suffix']={group:metric(actual[ids],original_fp32[ids],ids) for group,ids in groups.items()}
            cases=json.loads((manifest.parent/'cases.json').read_text(encoding='utf-8'))
            report['cases']=[dict(**c,vs_original_tflite=metric(actual[i:i+1],expected[i:i+1],[i]),
                                 vs_fp32_suffix=metric(actual[i:i+1],original_fp32[i:i+1],[i])) for i,c in enumerate(cases)]
            report['simulator_numeric_gate']=all(report[key]['all']['device_numeric_gate'] for key in ['vs_original_tflite','vs_fp32_suffix'])
            report['status']='completed'
        require(sha256(model)==MODEL_SHA and sha256(manifest.parent/'inputs.f32')==INPUT_SHA
                and sha256(manifest.parent/'original-tflite52.f32')==TF_SHA,'Source changed during backend run')
    except BaseException as error:
        report.update(status='error',error=type(error).__name__+': '+str(error),traceback=traceback.format_exc())
    finally:
        if runtime is not None:
            try:runtime.release()
            except BaseException as error:
                report.update(status='error',cleanup_error=type(error).__name__+': '+str(error),cleanup_traceback=traceback.format_exc())
        report['finished_utc']=utc_now();report.pop('active_step',None);flush()
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['prepare','compile','simulate'])
    p.add_argument('--boundary-report',type=Path);p.add_argument('--validation',type=Path)
    p.add_argument('--manifest',type=Path);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    if args.mode=='prepare':
        require(args.boundary_report is not None and args.validation is not None,'Missing prepare source')
        report=prepare(args.boundary_report,args.validation,args.output)
    else:
        require(args.manifest is not None,'Missing prepared manifest')
        report=run_backend(args.mode,args.manifest,args.output)
    print(json.dumps({key:report.get(key) for key in ['status','simulator_numeric_gate','error','cleanup_error']}),flush=True)
    return 0 if report['status'] in ('prepared','compiled','completed') else 1


if __name__=='__main__':sys.exit(main())
