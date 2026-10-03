"""Prepare/verify an isolated RKNN expression run. Never invokes adb or edits app assets."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

import numpy as np

TFLITE_SHA = '4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082'
RUNTIME_SHA = '01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de'
THRESHOLDS = dict(max_absolute_error=.01, global_mean_absolute_error=.002, range_tolerance=1e-5)
FILE_NAMES = ('face_blendshapes.rknn', 'inputs.f32', 'references.f32', 'rknn_blendshape_probe')
# Official MediaPipe output order, matching the production BlendshapeSchema.
CHANNELS = ('_neutral browDownLeft browDownRight browInnerUp browOuterUpLeft browOuterUpRight '
    'cheekPuff cheekSquintLeft cheekSquintRight eyeBlinkLeft eyeBlinkRight eyeLookDownLeft eyeLookDownRight '
    'eyeLookInLeft eyeLookInRight eyeLookOutLeft eyeLookOutRight eyeLookUpLeft eyeLookUpRight eyeSquintLeft '
    'eyeSquintRight eyeWideLeft eyeWideRight jawForward jawLeft jawOpen jawRight mouthClose mouthDimpleLeft '
    'mouthDimpleRight mouthFrownLeft mouthFrownRight mouthFunnel mouthLeft mouthLowerDownLeft mouthLowerDownRight '
    'mouthPressLeft mouthPressRight mouthPucker mouthRight mouthRollLower mouthRollUpper mouthShrugLower '
    'mouthShrugUpper mouthSmileLeft mouthSmileRight mouthStretchLeft mouthStretchRight mouthUpperUpLeft '
    'mouthUpperUpRight noseSneerLeft noseSneerRight').split()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with Path(path).open('rb') as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
        return digest.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write_json(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False, allow_nan=False)
        stream.write('\n')


def prepare_bundle(fixtures, validation, model, compilation, helper, bundle):
    fixtures, validation, model, compilation, helper, bundle = map(Path, (fixtures, validation, model, compilation, helper, bundle))
    if bundle.exists():
        raise FileExistsError(bundle)
    v, c = read_json(validation), read_json(compilation)
    require(v.get('status') == 'passed' and v.get('source_sha256') == TFLITE_SHA, 'Unverified original TFLite reference')
    require(v.get('reference') == 'original TFLite, no rewritten reference', 'Reference must be original TFLite')
    require(c.get('status') == 'compiled' and c.get('validation_sha256') == file_sha(validation), 'Compile/validation hash mismatch')
    require(c.get('artifact', {}).get('sha256') == file_sha(model) and c['artifact'].get('bytes') == model.stat().st_size, 'Compiled model hash/size mismatch')
    require(v.get('fixture_archive_sha256') == file_sha(fixtures), 'Fixture archive hash mismatch')
    with np.load(fixtures, allow_pickle=False) as archive:
        inputs, outputs = archive['inputs'], archive['outputs']
    require(inputs.dtype == np.float32 and inputs.shape == (92, 1, 146, 2), 'Expected 92 x [1,146,2] float32 inputs')
    require(outputs.dtype == np.float32 and outputs.shape == (92, 52), 'Expected 92 x 52 float32 references')
    require(np.isfinite(inputs).all() and np.isfinite(outputs).all(), 'Nonfinite fixture')
    require(np.all(outputs >= -1e-5) and np.all(outputs <= 1 + 1e-5), 'Reference outside probability range')
    require(v.get('case_count') == 92 and len(v.get('cases', [])) == 92, 'Expected all 92 validated cases')
    cases = []
    for index, case in enumerate(v['cases']):
        require(case.get('input_sha256') == sha(inputs[index].astype('<f4').tobytes()), f'Input hash mismatch {index}')
        require(case.get('reference_sha256') == sha(outputs[index].astype('<f4').tobytes()), f'Reference hash mismatch {index}')
        name = case['fixture']
        group = 'synthetic' if name.startswith('synthetic-') else 'real' if name.startswith(('npu-', 'reference-')) else None
        require(group is not None, f'Unknown fixture category: {name}')
        cases.append(dict(index=index, fixture=name, group=group, input_sha256=case['input_sha256'], reference_sha256=case['reference_sha256']))
    require(sum(x['group'] == 'real' for x in cases) == 72 and sum(x['group'] == 'synthetic' for x in cases) == 20, 'Expected real72 / synthetic20')
    require(helper.read_bytes().startswith(b'\x7fELF'), 'Helper must be compiled ELF executable')
    # Validate every source before creating any output. Existing bundles are immutable.
    payloads = {'face_blendshapes.rknn': model.read_bytes(), 'inputs.f32': inputs.astype('<f4').tobytes(),
                'references.f32': outputs.astype('<f4').tobytes(), 'rknn_blendshape_probe': helper.read_bytes()}
    manifest = dict(schema_version=1, cases=cases, case_count=92, input_shape=[1, 146, 2], output_shape=[52],
        input_contract='C-order little-endian float32; adjacent pixel x/y, already selected/scaled; no preprocessing or transpose',
        thresholds=THRESHOLDS, accuracy_scope='Predefined preliminary FP16 engineering screen; not final face/visual accuracy',
        runtime_sha256=RUNTIME_SHA, reference_tflite_sha256=TFLITE_SHA,
        provenance={str(p.resolve()): file_sha(p) for p in (fixtures, validation, model, compilation, helper)},
        files={name: dict(sha256=sha(data), bytes=len(data)) for name, data in payloads.items()})
    bundle.mkdir(parents=True, exist_ok=False)
    for name, data in payloads.items():
        (bundle / name).write_bytes(data)
    write_json(bundle / 'manifest.json', manifest)
    return manifest


def hash_listing(path):
    result = {}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        match = re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?(.+)', line.strip())
        require(match is not None, f'Invalid sha256sum line in {path}: {line!r}')
        name = match[2].replace('\\', '/').rsplit('/', 1)[-1]
        require(name not in result, f'Duplicate device hash: {name}')
        result[name] = match[1].lower()
    return result


def distribution(values):
    values = np.asarray(values, dtype=np.float64)
    return dict(count=int(values.size), mean=float(np.mean(values)), minimum=float(np.min(values)),
                maximum=float(np.max(values)), p50=float(np.percentile(values, 50)),
                p95=float(np.percentile(values, 95)), p99=float(np.percentile(values, 99)))


def errors(actual, expected):
    finite = np.isfinite(actual)
    difference = np.abs(actual.astype(np.float64)[finite] - expected.astype(np.float64)[finite])
    return dict(values=int(actual.size), nonfinite_values=int((~finite).sum()),
        max_absolute_error=float(difference.max()) if difference.size else None,
        mean_absolute_error=float(difference.mean()) if difference.size else None,
        root_mean_square_error=float(np.sqrt(np.mean(difference ** 2))) if difference.size else None)


def compare_bundle(bundle, log, actual_path, before_path, after_path):
    bundle, log, actual_path = map(Path, (bundle, log, actual_path))
    manifest = read_json(bundle / 'manifest.json')
    require(manifest.get('schema_version') == 1 and manifest.get('case_count') == 92, 'Unknown manifest schema/count')
    require(manifest.get('thresholds') == THRESHOLDS, 'Thresholds changed after preparation')
    require(manifest.get('runtime_sha256') == RUNTIME_SHA and manifest.get('reference_tflite_sha256') == TFLITE_SHA, 'Unknown runtime/reference pin')
    require(set(manifest['files']) == set(FILE_NAMES), 'Unexpected bundle files')
    for name, entry in manifest['files'].items():
        require(file_sha(bundle / name) == entry['sha256'] and (bundle / name).stat().st_size == entry['bytes'], f'Local bundle changed: {name}')
    before, after = hash_listing(before_path), hash_listing(after_path)
    for name in ('face_blendshapes.rknn', 'inputs.f32', 'rknn_blendshape_probe', 'librknnrt.so'):
        expected_hash = RUNTIME_SHA if name == 'librknnrt.so' else manifest['files'][name]['sha256']
        require(before.get(name) == expected_hash and after.get(name) == expected_hash, f'Device before/after hash mismatch: {name}')
    require(after.get('outputs.f32') == file_sha(actual_path), 'Pulled output hash mismatch')
    require(after.get('report.jsonl') == file_sha(log), 'Pulled native report hash mismatch')
    rows = [json.loads(line) for line in log.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    require(rows and rows[0].get('event') == 'start' and rows[-1].get('event') == 'finish', 'Missing start/finish; incomplete run')
    def one(event):
        found = [row for row in rows if row.get('event') == event]
        require(len(found) == 1, f'Expected one {event}')
        return found[0]
    start, init, sdk, counts, feed, finish = (one(key) for key in ('start', 'init', 'sdk', 'io_count', 'feed_contract', 'finish'))
    cycles = start.get('cycles')
    require(type(cycles) is int and 1 <= cycles <= 100000 // 92, 'Invalid cycles')
    count = 92 * cycles
    require(start.get('schema_version') == 1 and start.get('cases') == 92 and start.get('warmup') == 5 and start.get('input_floats') == 292 and start.get('output_floats') == 52, 'Wrong run contract')
    require(start.get('runtime_path') == '/vendor/lib64/librknnrt.so', 'Unexpected runtime path')
    require(init.get('rc') == 0 and init.get('flags') == 0 and init.get('model_bytes') == manifest['files']['face_blendshapes.rknn']['bytes'] and init.get('input_bytes') == 92*292*4, 'Initialization mismatch/failure')
    require(sdk.get('rc') == 0 and sdk.get('api', '').startswith('1.3.0') and sdk.get('driver', '').startswith('0.7.2'), 'Unvalidated runtime/driver version')
    require(counts.get('rc') == 0 and counts.get('inputs') == 1 and counts.get('outputs') == 1, 'Unexpected IO count')
    attrs = [row for row in rows if row.get('event') == 'tensor_attr']
    require(len(attrs) == 2 and [x.get('kind') for x in attrs] == ['input', 'output'], 'Expected ordered input/output attrs')
    for attr, target, size in zip(attrs, ([146, 2], [52]), (292, 52)):
        dims = attr.get('dims', [])
        require(attr.get('rc') == 0 and 1 <= len(dims) <= 16 and all(type(d) is int and d > 0 for d in dims), 'Invalid tensor dimensions')
        require([d for d in dims if d != 1] == target and math.prod(dims) == size and attr.get('n_elems') == size, 'Tensor shape mismatch; never infer a transpose')
    known_format = attrs[0].get('fmt') in (0, 1)
    observed_xy = attrs[0].get('fmt') == 3 and attrs[0].get('n_dims') == 3 and attrs[0]['dims'] == [1, 146, 2]
    require((known_format or observed_xy) and feed.get('fmt') == attrs[0]['fmt'] and feed.get('type') == 0 and feed.get('pass_through') == 0 and feed.get('want_float') == 1, 'Float32 input/output contract mismatch')
    require(finish.get('status') == 'success' and finish.get('exit_code') == 0 and finish.get('destroy_rc') == 0 and finish.get('completed_measurements') == count and finish.get('expected_measurements') == count, 'Incomplete/failed native run')
    iterations = [row for row in rows if row.get('event') == 'iteration']
    require(len(iterations) == 5 + count and len(rows) == 8 + len(iterations), 'Unexpected/missing events')
    warmup_range = True
    for offset, row in enumerate(iterations):
        warming = offset < 5
        index = offset if warming else offset - 5
        require(row.get('phase') == ('warmup' if warming else 'measurement') and row.get('iteration') == index and row.get('fixture') == index % 92 and row.get('cycle') == index // 92 and row.get('output_offset_floats') == (-1 if warming else index * 52), f'Wrong output identity/order at {offset}')
        require(row.get('ok') is True and row.get('return_codes') == dict(inputs_set=0, run=0, outputs_get=0, outputs_release=0) and row.get('nonfinite') == 0 and row.get('output_bytes', 0) >= 208, f'Native iteration failed at {offset}')
        timing = row.get('timing_ms', {})
        require(set(timing) == {'inputs_set', 'run', 'outputs_get', 'outputs_release', 'total'} and all(isinstance(x, (int, float)) and math.isfinite(x) and x >= 0 for x in timing.values()), f'Invalid timing at {offset}')
        lo, hi = row.get('output_min'), row.get('output_max')
        require(isinstance(lo, (int, float)) and isinstance(hi, (int, float)) and math.isfinite(lo) and math.isfinite(hi) and lo <= hi, f'Invalid output extrema at {offset}')
        if warming:
            warmup_range &= lo >= -THRESHOLDS['range_tolerance'] and hi <= 1 + THRESHOLDS['range_tolerance']
    require(actual_path.stat().st_size == count * 52 * 4, 'Truncated/extra output data')
    actual = np.fromfile(actual_path, dtype='<f4').reshape(count, 52)
    reference = np.fromfile(bundle / 'references.f32', dtype='<f4').reshape(92, 52)
    expected = np.tile(reference, (cycles, 1))
    # Recheck the identity map against local raw arrays; manifest labels cannot silently reclassify rows.
    inputs = np.fromfile(bundle / 'inputs.f32', dtype='<f4').reshape(92, 292)
    cases = manifest['cases']
    require(len(cases) == 92, 'Missing manifest case identities')
    for i, case in enumerate(cases):
        expected_group = 'synthetic' if case['fixture'].startswith('synthetic-') else 'real' if case['fixture'].startswith(('npu-', 'reference-')) else None
        require(case.get('index') == i and case.get('group') == expected_group and expected_group is not None and case.get('input_sha256') == sha(inputs[i].tobytes()) and case.get('reference_sha256') == sha(reference[i].tobytes()), f'Changed case identity {i}')
    global_errors = errors(actual, expected)
    outside = np.isfinite(actual) & ((actual < -THRESHOLDS['range_tolerance']) | (actual > 1 + THRESHOLDS['range_tolerance']))
    passed = (global_errors['nonfinite_values'] == 0 and int(outside.sum()) == 0 and warmup_range and
              global_errors['max_absolute_error'] <= THRESHOLDS['max_absolute_error'] and
              global_errors['mean_absolute_error'] <= THRESHOLDS['global_mean_absolute_error'])
    groups = {}
    for group, expected_count in (('real', 72), ('synthetic', 20)):
        mask = np.array([case['group'] == group for case in cases])
        require(int(mask.sum()) == expected_count, 'Group count changed')
        groups[group] = dict(cases=expected_count, measurements=expected_count * cycles,
                             **errors(actual[np.tile(mask, cycles)], expected[np.tile(mask, cycles)]))
    channels = [dict(index=i, name=CHANNELS[i], **errors(actual[:, i], expected[:, i])) for i in range(52)]
    per_case = [dict(index=i, fixture=case['fixture'], group=case['group'], **errors(actual[i::92], expected[i::92])) for i, case in enumerate(cases)]
    return dict(schema_version=1, status='passed' if passed else 'failed', passed=bool(passed), thresholds=THRESHOLDS,
        scope=manifest['accuracy_scope'], full_pipeline_validated=False, application_performance_validated=False,
        native_process_exit_code_requires_external_zero_check=True,
        runtime_scope='Mixed CPU/NPU model, total API timing includes CPU fallback; not NPU utilization or screen FPS',
        case_count=92, cycles=cycles, measurements=count, **global_errors, out_of_range_values=int(outside.sum()),
        warmup_probability_range_passed=bool(warmup_range), groups=groups, channels=channels, cases=per_case,
        timing_ms={phase: {key: distribution([row['timing_ms'][key] for row in iterations if row['phase'] == phase]) for key in ('inputs_set', 'run', 'outputs_get', 'outputs_release', 'total')} for phase in ('warmup', 'measurement')},
        init=init, sdk=sdk, tensor_attrs=attrs, device_hashes_before=before, device_hashes_after=after,
        evidence_hashes={str(p.resolve()): file_sha(p) for p in (bundle / 'manifest.json', log, actual_path, Path(before_path), Path(after_path))})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prepare = sub.add_parser('prepare')
    for option in ('fixtures', 'validation', 'model', 'compilation', 'helper', 'bundle'):
        prepare.add_argument('--' + option, required=True, type=Path)
    check = sub.add_parser('check')
    for option in ('bundle', 'log', 'actual', 'before', 'after', 'output'):
        check.add_argument('--' + option, required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            manifest = prepare_bundle(args.fixtures, args.validation, args.model, args.compilation, args.helper, args.bundle)
            print(json.dumps(dict(status='prepared', bundle=str(args.bundle.resolve()), cases=manifest['case_count'], thresholds=THRESHOLDS)))
            return 0
        report = compare_bundle(args.bundle, args.log, args.actual, args.before, args.after)
        write_json(args.output, report)
        print(json.dumps({key: report[key] for key in ('status', 'passed', 'measurements', 'nonfinite_values', 'out_of_range_values', 'max_absolute_error', 'mean_absolute_error')}))
        return 0 if report['passed'] else 1
    except (ValueError, KeyError, OSError, TypeError) as error:
        result = dict(status='error', passed=False, error=str(error), thresholds=THRESHOLDS)
        if args.command == 'check' and not args.output.exists():
            write_json(args.output, result)
        print(json.dumps(result), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
