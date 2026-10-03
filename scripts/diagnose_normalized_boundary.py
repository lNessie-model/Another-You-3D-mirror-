"""Host-only fixed normalization-boundary rounding experiment. No RKNN calls.

The front and suffix contain original ONNX nodes, in original order. Only graph
IO and the partition node lists change. A single F32 -> F16 -> F32 roundtrip is
performed by NumPy between the two FP32 sessions; this is not a full F16 model.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
import traceback
import numpy as np
from compile_blendshape_loweropt import sha256, utc_now
from diagnose_blendshape_numerics import load_frozen, require, save

SOURCE_SHA = 'fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287'
COPIED_SHA = '5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200'
VALIDATION_SHA = '8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746'
PREPARED_SHA = '7e12160f8e2660a080901ad753e47a73810250323cf173499f45c337ce959318'
SIM_REPORT_SHA = 'bcb7e2cd8f90927281fbd02f5e7141402f0ca00dabfcdac1f1ab888b0099069a'
SIM_FINAL_SHA = '84b9d97b34b9107619ed88375e1879ea591b70de69adf5efeb14000b7cc3c242'
INPUT = 'serving_default_input_points:0'
BOUNDARY = 'model_1/tf.math.truediv_1/truediv'
FINAL = 'StatefulPartitionedCall:0'
SHAPE = (1, 146, 2)
FRONT_OPS = ('ReduceMean', 'Sub', 'Mul', 'ReduceSum', 'Pow', 'ReduceMean', 'Pow', 'Mul')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def io_contract(value, name, shape):
    require(value.name == name and value.type.tensor_type.elem_type == 1
            and [d.dim_value for d in value.type.tensor_type.shape.dim] == list(shape)
            and all(not d.dim_param for d in value.type.tensor_type.shape.dim), 'Changed fixed tensor contract')


def check_closed(model):
    """Reject missing inputs, raw-input bypasses, duplicate producers and wrong order."""
    available = {v.name for v in model.graph.input}
    constants = [v.name for v in model.graph.initializer]
    require(len(available) == len(model.graph.input) and len(set(constants)) == len(constants)
            and not available.intersection(constants), 'Duplicate input/initializer')
    available.update(constants)
    for node in model.graph.node:
        require(all(not name or name in available for name in node.input), 'Unresolved dependency: ' + node.name)
        require(all(name and name not in available for name in node.output)
                and len(set(node.output)) == len(node.output), 'Duplicate/empty producer: ' + node.name)
        available.update(node.output)
    require(all(v.name in available for v in model.graph.output), 'Unresolved graph output')


def verify_partition(original, front, suffix):
    require(len(front.graph.input) == len(front.graph.output) == len(suffix.graph.input) == len(suffix.graph.output) == 1,
            'Changed partition IO count')
    io_contract(front.graph.input[0], INPUT, SHAPE)
    io_contract(front.graph.output[0], BOUNDARY, SHAPE)
    io_contract(suffix.graph.input[0], BOUNDARY, SHAPE)
    io_contract(suffix.graph.output[0], FINAL, (52,))
    require(len(front.graph.node) == 8, 'Changed front node count')
    require([n.SerializeToString() for n in list(front.graph.node) + list(suffix.graph.node)]
            == [n.SerializeToString() for n in original.graph.node], 'Changed node bytes/order')
    # Restoring each partition's node list and IO must recover the whole original
    # protobuf, including every initializer, metadata field and operator attribute.
    for part in (front, suffix):
        restored = copy.deepcopy(part)
        for field in ('node', 'input', 'output'):
            target = getattr(restored.graph, field); del target[:]
            target.extend(copy.deepcopy(list(getattr(original.graph, field))))
        require(restored.SerializeToString() == original.SerializeToString(), 'Changed parameter/metadata bytes')
        check_closed(part)


def split_fixed_boundary(original):
    import onnx
    from onnx import helper, TensorProto
    require(digest(original.SerializeToString()) in (SOURCE_SHA, COPIED_SHA), 'Only the two frozen source graphs are accepted')
    require(len(original.graph.input) == len(original.graph.output) == 1, 'Changed source IO')
    io_contract(original.graph.input[0], INPUT, SHAPE)
    io_contract(original.graph.output[0], FINAL, (52,))
    require(tuple(n.op_type for n in original.graph.node[:8]) == FRONT_OPS
            and list(original.graph.node[7].output) == [BOUNDARY]
            and list(original.graph.node[8].input)[0] == BOUNDARY, 'Fixed cut position changed')
    check_closed(original)
    front, suffix = copy.deepcopy(original), copy.deepcopy(original)
    del front.graph.node[8:]; del suffix.graph.node[:8]
    del front.graph.output[:]
    front.graph.output.append(helper.make_tensor_value_info(BOUNDARY, TensorProto.FLOAT, SHAPE))
    del suffix.graph.input[:]
    suffix.graph.input.append(helper.make_tensor_value_info(BOUNDARY, TensorProto.FLOAT, SHAPE))
    verify_partition(original, front, suffix)
    onnx.checker.check_model(front); onnx.checker.check_model(suffix)
    return front, suffix


def round_boundary(values):
    require(values.dtype == np.float32 and values.ndim == 4 and values.shape[1:] == SHAPE
            and 1 <= len(values) <= 92 and np.isfinite(values).all(), 'Invalid fixed boundary rows')
    with np.errstate(over='ignore', invalid='ignore'):
        rounded = values.astype(np.float16).astype(np.float32)
    require(np.isfinite(rounded).all(), 'Half roundtrip overflow')
    return rounded


def session(model):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    options.log_severity_level = 3
    return ort.InferenceSession(model.SerializeToString(), options)


def run_rows(runtime, values, input_name, output_shape):
    require(len(runtime.get_inputs()) == 1 and runtime.get_inputs()[0].name == input_name
            and runtime.get_inputs()[0].shape == list(SHAPE), 'Session input mismatch')
    output = []
    for value in values:
        row = runtime.run(None, {input_name: value})
        require(len(row) == 1 and row[0].dtype == np.float32 and row[0].shape == output_shape
                and np.isfinite(row[0]).all(), 'Session output mismatch/nonfinite')
        output.append(row[0])
    return np.stack(output)


def metric(actual, expected, indices):
    require(actual.shape == expected.shape == (len(indices), 52)
            and np.isfinite(actual).all() and np.isfinite(expected).all(), 'Invalid final52 metric input')
    delta = np.abs(actual.astype(np.float64) - expected.astype(np.float64))
    case, channel = np.unravel_index(np.argmax(delta), delta.shape)
    maximum, mean = float(delta.max()), float(delta.mean())
    valid_range = bool((actual >= -1e-5).all() and (actual <= 1 + 1e-5).all())
    return dict(max_abs=maximum, mean_abs=mean, rms=float(np.sqrt(np.mean(delta ** 2))),
        values_gt_0_01=int(np.count_nonzero(delta > .01)), cases_gt_0_01=int(np.count_nonzero(delta.max(axis=1) > .01)),
        bit_differences=int(np.count_nonzero(actual.view('u4') != expected.view('u4'))),
        finite=True, range_valid=valid_range, original_1e_5_gate=maximum <= 1e-5,
        device_numeric_gate=maximum <= .01 and mean <= .002 and valid_range,
        worst=dict(case_index=int(indices[case]), channel=int(channel), actual=float(actual[case, channel]),
                   reference=float(expected[case, channel]), abs_error=maximum))


def analyze(args, report):
    import onnx
    import onnxruntime as ort
    require(sha256(args.validation) == VALIDATION_SHA, 'Changed frozen validation')
    source_path, inputs, tflite = load_frozen(args.validation, 'gamma-conv-mulpow')
    require(sha256(source_path) == SOURCE_SHA and sha256(args.copied) == COPIED_SHA, 'Changed source/copy graph')
    prepared_path = args.copied.parent / 'diagnostic.json'
    require(sha256(prepared_path) == PREPARED_SHA and sha256(args.simulation) == SIM_REPORT_SHA, 'Changed prepared/simulator evidence')
    prepared = json.loads(prepared_path.read_text(encoding='utf-8'))
    sim_report = json.loads(args.simulation.read_text(encoding='utf-8'))
    require(prepared['candidate_sha256'] == COPIED_SHA and prepared['source_validation_sha256'] == VALIDATION_SHA
            and prepared['input_sha256'] == digest(inputs.tobytes())
            and prepared['reference_tflite52_sha256'] == digest(tflite.tobytes()), 'Prepared fixture/source chain mismatch')
    observed_path = args.copied.parent / 'scale-taps.onnx'
    require(sha256(observed_path) == prepared['model_sha256'] == sim_report['model_sha256']
            and sim_report['candidate_sha256'] == COPIED_SHA and sim_report['manifest_sha256'] == PREPARED_SHA
            and sim_report['status'] == 'completed' and sim_report['target'] is None
            and all(s['rc'] == 0 for s in sim_report['steps'].values()), 'Simulator/source chain mismatch')
    sim_entry = sim_report['outputs'][-1]
    require(sim_entry['label'] == 'final52' and sim_entry['name'] == FINAL and sim_entry['shape'] == [52]
            and sim_entry['file'] == 'simulator-final52.f32' and sim_entry['bytes'] == 92 * 52 * 4,
            'Simulator final contract mismatch')
    sim_path = args.simulation.parent / sim_entry['file']
    require(sha256(sim_path) == sim_entry['sha256'] == SIM_FINAL_SHA and sim_path.stat().st_size == 92 * 52 * 4,
            'Changed simulator output')
    simulated = np.frombuffer(sim_path.read_bytes(), dtype='<f4').reshape(92, 52).copy()
    source, copied = onnx.load(str(source_path)), onnx.load(str(args.copied))
    front, suffix = split_fixed_boundary(source)
    copied_front, copied_suffix = split_fixed_boundary(copied)
    require([n.SerializeToString() for n in front.graph.node] == [n.SerializeToString() for n in copied_front.graph.node],
            'Copied model front differs')
    observer = copy.deepcopy(source)
    observer.graph.output.append(copy.deepcopy(front.graph.output[0]))
    check_closed(observer); onnx.checker.check_model(observer)
    # The observation model changes only graph.output, not a final-path node.
    restored = copy.deepcopy(observer); del restored.graph.output[1:]
    require(restored.SerializeToString() == source.SerializeToString(), 'Observer modified original graph')
    models = dict(front=front, original_suffix=suffix, copied_suffix=copied_suffix, source_observer=observer)
    for name, model in models.items(): onnx.save(model, str(args.output / (name + '.onnx')))
    baseline = run_rows(session(source), inputs, INPUT, (52,))
    copied_full = run_rows(session(copied), inputs, INPUT, (52,))
    normalized = run_rows(session(front), inputs, INPUT, SHAPE)
    observed = session(observer)
    observed_values = [observed.run(None, {INPUT: value}) for value in inputs]
    require(np.stack([v[0] for v in observed_values]).tobytes() == baseline.tobytes()
            and np.stack([v[1] for v in observed_values]).tobytes() == normalized.tobytes(),
            'Front partition or observation changes original FP32 result')
    normalized_half = round_boundary(normalized)
    raw_half = round_boundary(inputs)
    results = dict(original_unsplit_fp32=baseline, copied_unsplit_fp32=copied_full,
        original_cut_unrounded=run_rows(session(suffix), normalized, BOUNDARY, (52,)),
        copied_cut_unrounded=run_rows(session(copied_suffix), normalized, BOUNDARY, (52,)),
        normalized_boundary_half=run_rows(session(suffix), normalized_half, BOUNDARY, (52,)),
        copied_normalized_boundary_half=run_rows(session(copied_suffix), normalized_half, BOUNDARY, (52,)),
        raw_input_half=run_rows(session(source), raw_half, INPUT, (52,)),
        copied_raw_input_half=run_rows(session(copied), raw_half, INPUT, (52,)), all8_simulator=simulated)
    identities = {name: value.tobytes() == baseline.tobytes() for name, value in results.items()
                  if name in ('copied_unsplit_fp32', 'original_cut_unrounded', 'copied_cut_unrounded')}
    identities.update(copied_rounded_boundary_same_as_original=results['normalized_boundary_half'].tobytes() == results['copied_normalized_boundary_half'].tobytes(),
                      copied_rounded_input_same_as_original=results['raw_input_half'].tobytes() == results['copied_raw_input_half'].tobytes())
    validation = json.loads(args.validation.read_text(encoding='utf-8'))
    case_ids = [dict(index=i, fixture=c['fixture'], group='real' if i < 72 else 'synthetic',
                    input_sha256=c['input_sha256'], reference_sha256=c['reference_sha256']) for i, c in enumerate(validation['cases'])]
    groups = dict(all=list(range(92)), real=list(range(72)), synthetic=list(range(72, 92)))
    report['metrics_vs_original_tflite'] = {name: {group: metric(data[ids], tflite[ids], ids) for group, ids in groups.items()}
                                          for name, data in results.items()}
    report['cases'] = [dict(**identity, results={name: metric(data[i:i+1], tflite[i:i+1], [i])
                                               for name, data in results.items()}) for i, identity in enumerate(case_ids)]
    report['worst_case_indices'] = {name: {group: sorted(ids, key=lambda i: (-report['cases'][i]['results'][name]['max_abs'], i))[:10]
                                            for group, ids in groups.items()} for name in results}
    report['comparisons'] = {name: {group: metric(data[ids], results['normalized_boundary_half'][ids], ids)
                                    for group, ids in groups.items()} for name, data in results.items()
                                    if name in ('original_unsplit_fp32', 'raw_input_half', 'all8_simulator')}
    arrays = dict(inputs=inputs, original_tflite52=tflite, normalized_fp32=normalized,
                  normalized_half_roundtrip=normalized_half, raw_half_roundtrip=raw_half, **results)
    report['artifacts'] = {}
    for name, data in arrays.items():
        filename = name + '.f32'; path = args.output / filename
        path.write_bytes(data.astype('<f4', copy=False).tobytes())
        report['artifacts'][name] = dict(file=filename, shape=list(data.shape), sha256=sha256(path), bytes=path.stat().st_size)
    for name in models:
        path = args.output / (name + '.onnx')
        report['artifacts'][name] = dict(file=path.name, sha256=sha256(path), bytes=path.stat().st_size)
    report['source_files'] = [dict(path=str(p), sha256=sha256(p), bytes=p.stat().st_size) for p in
                             [args.validation, source_path, args.validation.parent/'reference-fixtures.npz',
                              args.copied, prepared_path, observed_path, args.simulation, sim_path,
                              Path(__file__).with_name('diagnose_blendshape_numerics.py'),
                              Path(__file__).with_name('compile_blendshape_loweropt.py')]]
    report['roundtrip_input_errors'] = {}
    for label, exact, rounded in [('raw', inputs, raw_half), ('normalized', normalized, normalized_half)]:
        difference = np.abs(exact.astype(np.float64) - rounded.astype(np.float64))
        report['roundtrip_input_errors'][label] = dict(max_abs=float(difference.max()), mean_abs=float(difference.mean()),
            exact_min=float(exact.min()), exact_max=float(exact.max()), rounded_min=float(rounded.min()), rounded_max=float(rounded.max()),
            bit_differences=int(np.count_nonzero(exact.view('u4') != rounded.view('u4'))))
    report.update(versions=dict(onnx=onnx.__version__, onnxruntime=ort.__version__, numpy=np.__version__),
        boundary=dict(name=BOUNDARY, dtype='FLOAT32', shape=list(SHAPE), elements=292, index_order='unchanged 146 points x XY',
            front_nodes=[dict(index=i, name=n.name, op=n.op_type, inputs=list(n.input), outputs=list(n.output),
                              serialized_sha256=digest(n.SerializeToString())) for i, n in enumerate(front.graph.node)],
            original_suffix_nodes=len(suffix.graph.node), copied_suffix_nodes=len(copied_suffix.graph.node),
            initializers='All source initializers retained byte-for-byte in each partition, including unused constants',
            parameters_and_ops_unchanged=True, dependency_closure_checked=True, partitions_restore_complete_source_bytes=True),
        unrounded_equivalence=identities, equivalence_gate=all(identities.values()),
        original_reference_gate=report['metrics_vs_original_tflite']['original_unsplit_fp32']['all']['original_1e_5_gate'],
        case_count=92, rounding='NumPy float32 -> float16 -> float32, once only at stated boundary; all subsequent ORT arithmetic remains FP32',
        status='diagnostic_complete')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('validation', 'copied', 'simulation', 'output'): parser.add_argument('--' + flag, type=Path, required=True)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=False)
    report = dict(status='running', started_utc=utc_now(), script_sha256=sha256(Path(__file__)),
                  application_eligible=False, rknn_compiled=False, device_executed=False, performance_evidence=False,
                  scope='Fixed 92-case host sensitivity experiment, not full-FP16 inference or a deployable model')
    try: analyze(args, report)
    except BaseException as error:
        report.update(status='error', error=str(error), traceback=traceback.format_exc()); traceback.print_exc()
    finally:
        report['finished_utc'] = utc_now(); save(args.output/'diagnostic.json', report)
    print(json.dumps({k: report.get(k) for k in ('status', 'equivalence_gate', 'original_reference_gate', 'error')}), flush=True)
    return 0 if report['status'] == 'diagnostic_complete' and report['equivalence_gate'] and report['original_reference_gate'] else 1


if __name__ == '__main__': sys.exit(main())
