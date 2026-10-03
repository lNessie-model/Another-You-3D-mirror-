"""Offline diagnosis of a frozen RKNN candidate; never selects an Android target.

The input and reference identities come from the original-TFLite validation gate.
Simulation is a diagnostic result, not evidence of Android accuracy or performance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import traceback
import numpy as np
from compile_blendshape_loweropt import EXPECTED_SOURCE_SHA256, _toolkit, sha256, utc_now
from blendshape_device_check import hash_listing


def require(value, message):
    if not value:
        raise ValueError(message)


def save(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    temp.replace(path)


def load_frozen(validation_path, candidate):
    report = json.loads(validation_path.read_text(encoding='utf-8'))
    require(report['status'] == 'passed' and report['source_sha256'] == EXPECTED_SOURCE_SHA256
            and report['case_count'] == len(report['cases']) == 92
            and report['atol'] == 1e-5 and report['rtol'] == 0, 'Original reference gate missing')
    info = report['candidates'][candidate]
    require(info['file'] == candidate + '.onnx', 'Candidate filename mismatch')
    path = validation_path.parent / info['file']
    require(sha256(path) == info['sha256'], 'Candidate bytes changed')
    archive = validation_path.parent / 'reference-fixtures.npz'
    require(sha256(archive) == report['fixture_archive_sha256'], 'Reference fixture bytes changed')
    with np.load(str(archive), allow_pickle=False) as data:
        inputs, expected = data['inputs'].copy(), data['outputs'].copy()
    require(inputs.dtype == np.float32 and inputs.shape == (92, 1, 146, 2)
            and expected.dtype == np.float32 and expected.shape == (92, 52)
            and np.isfinite(inputs).all() and np.isfinite(expected).all(), 'Fixture tensor contract changed')
    for i, row in enumerate(report['cases']):
        require(row['candidates'][candidate]['passed']
                and hashlib.sha256(inputs[i].tobytes()).hexdigest() == row['input_sha256']
                and hashlib.sha256(expected[i].tobytes()).hexdigest() == row['reference_sha256'],
                'Fixture order/content changed')
    return path, inputs, expected


def errors(actual, expected):
    require(actual.shape == expected.shape, 'Unexpected diagnostic output shape')
    finite = np.isfinite(actual)
    result = {'shape': list(actual.shape), 'nonfinite': int(np.count_nonzero(~finite))}
    if not finite.all():
        return result
    difference = np.abs(actual.astype(np.float64) - expected.astype(np.float64))
    result.update(max_abs=float(difference.max()), mean_abs=float(difference.mean()),
                  rms=float(np.sqrt(np.mean(difference ** 2))))
    return result


def distribution(data):
    require(np.isfinite(data).all(), 'Distribution requires finite values')
    return {'min': float(data.min()), 'max': float(data.max()), 'mean': float(data.mean()),
            'near_zero_0_01': int(np.count_nonzero(data < .01)),
            'near_one_0_99': int(np.count_nonzero(data > .99)),
            'per_channel_min': data.min(axis=0).tolist(), 'per_channel_max': data.max(axis=0).tolist(),
            'per_channel_mean': data.mean(axis=0).tolist(), 'per_channel_std': data.std(axis=0).tolist(),
            'consecutive_frame_abs_mean': float(np.abs(np.diff(data, axis=0)).mean())}


def analyze(args, report, path, inputs, expected):
    device = args.device_directory
    require(device is not None and args.compilation is not None,
            'analyze requires device directory and compilation report')
    compiled = json.loads(args.compilation.read_text(encoding='utf-8'))
    require(compiled.get('status') == 'compiled' and compiled.get('candidate') == args.candidate
            and compiled.get('model_sha256') == sha256(path)
            and compiled.get('validation_sha256') == sha256(args.validation)
            and all(compiled.get('steps', {}).get(step, {}).get('return_code') == 0
                    for step in ('config', 'load', 'build', 'export')),
            'Compilation is incomplete or unrelated to the frozen candidate/reference')
    artifact = args.compilation.parent / 'face_blendshapes.rknn'
    artifact_hash = sha256(artifact)
    require(compiled.get('artifact', {}).get('sha256') == artifact_hash
            and compiled['artifact'].get('bytes') == artifact.stat().st_size,
            'Compiled RKNN artifact changed')
    before, hashes = (hash_listing(device / name) for name in ('before.sha256', 'after.sha256'))
    input_hash = hashlib.sha256(inputs.astype('<f4', copy=False).tobytes()).hexdigest()
    for name, digest in [('inputs.f32', input_hash), ('face_blendshapes.rknn', artifact_hash)]:
        require(before.get(name) == digest and hashes.get(name) == digest,
                'Device before/after identity mismatch: ' + name)
    report['compilation_sha256'] = sha256(args.compilation)
    report['device_input_sha256'] = input_hash
    report['device_model_sha256'] = artifact_hash
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(path), options)
    require(session.get_inputs()[0].shape == [1, 146, 2], 'ONNX input changed')
    rounded = inputs.astype(np.float16).astype(np.float32)
    reports, all_outputs = {}, {}
    for name, data in [('original_float32', inputs), ('input_only_float16_roundtrip', rounded)]:
        outputs = np.stack([session.run(None, {session.get_inputs()[0].name: value})[0] for value in data])
        require(outputs.shape == (92, 52), 'ONNX output changed')
        all_outputs[name] = outputs
        reports[name] = errors(outputs, expected)
    report['host_reference_checks'] = reports
    report['input_rounding'] = errors(rounded, inputs)
    report['onnxruntime_version'] = ort.__version__
    report['reference_distribution'] = distribution(expected)
    exit_record = json.loads((device / 'native-exit.json').read_text(encoding='utf-8-sig'))
    require(exit_record['exit_code'] == 0, 'Incomplete device run')
    raw = (device / 'outputs.f32').read_bytes()
    rows = [json.loads(line) for line in (device / 'report.jsonl').read_text(encoding='utf-8').splitlines() if line]
    require(rows[-1]['event'] == 'finish' and rows[-1]['status'] == 'success'
            and rows[-1]['completed_measurements'] == 92, 'Incomplete native output log')
    require(len(raw) == 92 * 52 * 4, 'Device output size mismatch')
    require(hashes.get('outputs.f32') == sha256(device / 'outputs.f32')
            and hashes.get('report.jsonl') == sha256(device / 'report.jsonl'), 'Pulled evidence hash mismatch')
    actual = np.frombuffer(raw, dtype='<f4').reshape(92, 52)
    report['device_evidence_sha256'] = {name: sha256(device/name) for name in
                                      ['outputs.f32','report.jsonl','before.sha256','after.sha256','native-exit.json']}
    report['device_vs_original_tflite'] = errors(actual, expected)
    report['device_vs_input_rounded_onnx'] = errors(actual, all_outputs['input_only_float16_roundtrip'])
    report['device_distribution'] = distribution(actual)
    report['groups'] = {}
    for group, bounds in [('real', slice(0,72)), ('synthetic', slice(72,92))]:
        report['groups'][group] = {'device': errors(actual[bounds],expected[bounds]),
                                 'input_rounding_only': errors(all_outputs['input_only_float16_roundtrip'][bounds],expected[bounds])}
    report['channels'] = [{'index': i, **errors(actual[:,i], expected[:,i]),
                           'actual_mean':float(actual[:,i].mean()),'reference_mean':float(expected[:,i].mean())}
                          for i in range(52)]
    report['cases'] = [{'index': i, **errors(actual[i], expected[i])} for i in range(92)]


def simulate(args, report, path, inputs, expected):
    factory, report['toolkit'] = _toolkit()
    runtime = None
    try:
        runtime = factory(verbose=True, verbose_file=str(args.output / 'simulator.log'))
        config = dict(target_platform='rk3566',float_dtype='float16',optimization_level=3)
        report['config'] = config
        report['steps'] = {}
        for name, action in [('config',lambda: runtime.config(**config)),
                             ('load',lambda: runtime.load_onnx(model=str(path))),
                             ('build',lambda: runtime.build(do_quantization=False)),
                             ('simulator_init',lambda: runtime.init_runtime(target=None))]:
            report['active_step'] = name; save(args.output/'diagnostic.json', report)
            start=time.monotonic(); rc=action()
            report['steps'][name] = {'rc':rc,'wall_s':time.monotonic()-start}
            save(args.output/'diagnostic.json', report)
            require(rc==0,name+' failed')
        outputs = []
        report['active_step'] = 'simulator_inference'; save(args.output/'diagnostic.json', report)
        for value in inputs:
            result = runtime.inference(inputs=[value],inputs_pass_through=[0])
            require(isinstance(result,list) and len(result)==1,'Simulator output count changed')
            actual=np.asarray(result[0])
            require(actual.shape==(52,) and actual.dtype==np.float32,'Simulator output tensor contract changed')
            outputs.append(actual.copy())
        actual = np.stack(outputs)
        np.save(str(args.output/'simulator-outputs.npy'),actual,allow_pickle=False)
        report['output_sha256'] = sha256(args.output/'simulator-outputs.npy')
        report['simulator_vs_original_tflite'] = errors(actual,expected)
    finally:
        if runtime is not None:
            runtime.release()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['analyze','simulate'])
    parser.add_argument('--validation',type=Path,required=True)
    parser.add_argument('--candidate',choices=['gamma-conv-mulpow'],required=True)
    parser.add_argument('--device-directory',type=Path)
    parser.add_argument('--compilation',type=Path,help='Required for analyze: original candidate compile-report.json')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    report={'status':'running','started_utc':utc_now(),'mode':args.mode,'script_sha256':sha256(Path(__file__)),
            'validation_sha256':sha256(args.validation),'android_accuracy_validated':False,
            'application_performance_validated':False,'scope':'Offline numerical diagnosis; not a model acceptance gate'}
    try:
        path,inputs,expected=load_frozen(args.validation,args.candidate)
        report['candidate_sha256']=sha256(path)
        save(args.output/'diagnostic.json',report)
        (analyze if args.mode=='analyze' else simulate)(args,report,path,inputs,expected)
        report['status']='completed'
        report.pop('active_step',None)
    except BaseException as error:
        report['status']='error';report['error']=str(error);report['traceback']=traceback.format_exc()
        traceback.print_exc()
    finally:
        report['finished_utc']=utc_now();save(args.output/'diagnostic.json',report)
    print(json.dumps({'status':report['status'],'error':report.get('error')}),flush=True)
    return 0 if report['status']=='completed' else 1


if __name__=='__main__':
    sys.exit(main())
