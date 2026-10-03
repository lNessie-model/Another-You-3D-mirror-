"""Offline, source-preserving explicit LayerNorm gamma broadcast experiment.

Prepare validates every candidate against the original TFLite before a separate
compile command is permitted. It never initializes a device runtime.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import traceback
import numpy as np
from compile_blendshape_loweropt import EXPECTED_SOURCE_SHA256, _toolkit, sha256, utc_now

GAMMA = re.compile(r".*/MixerBlock_[0-3]/layer_norm[12]/batchnorm/mul$")
ATOL = 1e-5

def require(condition, message):
    if not condition:
        raise ValueError(message)

def explicit_gamma_rank(source):
    """Only prepend singleton dimensions to eight private constant operands."""
    import onnx
    from onnx import numpy_helper
    model = copy.deepcopy(source)
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim]
              for v in list(source.graph.input) + list(source.graph.output) + list(source.graph.value_info)}
    constants = {c.name: c for c in model.graph.initializer}
    producers = {output: node for node in source.graph.node for output in node.output}
    changes = []
    targets = [n for n in model.graph.node if GAMMA.fullmatch(n.name)]
    require(len(targets) == 8, "Expected all eight LayerNorm gamma multiply nodes")
    for node in targets:
        require(node.op_type == "Mul" and len(node.input) == 2 and len(node.output) == 1,
                "Gamma consumer is not a binary single-output Mul")
        constant_names = [name for name in node.input if name in constants]
        require(len(constant_names) == 1, "Expected one constant gamma operand")
        name = constant_names[0]
        require(sum(name in n.input for n in model.graph.node) == 1, "Gamma has other consumers")
        constant = constants[name]
        values = numpy_helper.to_array(constant)
        require(values.dtype == np.float32 and values.shape == (64,), "Expected float32 gamma[64]")
        require(bool(np.isfinite(values).all()), "Gamma contains nonfinite values")
        variable = next(value for value in node.input if value != name)
        variable_shape = shapes.get(variable)
        shape_origin = {"kind": "declared_value_info"}
        if variable_shape is None:
            parent = producers.get(variable)
            require(parent is not None and parent.op_type == "Reshape" and len(parent.input) == 2
                    and parent.input[1] in constants, "Variable needs a literal static reshape shape")
            shape_values = numpy_helper.to_array(constants[parent.input[1]])
            require(shape_values.dtype in (np.int64, np.int32) and shape_values.shape == (4,)
                    and bool((shape_values > 0).all()), "Dynamic/invalid reshape cannot prove broadcast rank")
            variable_shape = shape_values.tolist()
            shape_origin = {"kind": "literal_reshape_shape", "node": parent.name,
                            "shape_initializer": parent.input[1]}
        require(variable_shape == [1, 1, 97, 1], "Unexpected variable broadcast layout")
        # All-positive Reshape dimensions define the output rank directly; no native shape inference.
        # ONNX's trailing-axis broadcast of that shape with gamma[64] is exactly [1,1,97,64].
        if node.output[0] in shapes:
            require(shapes[node.output[0]] == [1, 1, 97, 64], "Unexpected gamma product layout")
        changes.append({"constant": name, "consumer": node.name, "variable": variable,
                        "variable_shape": variable_shape, "variable_shape_origin": shape_origin, "from_shape": [64],
                        "to_shape": [1, 1, 1, 64],
                        "float32_bytes_sha256": hashlib.sha256(values.tobytes()).hexdigest(),
                        "min": float(values.min()), "max": float(values.max())})
        # Dimensions alone change. Raw values, node order, reductions and output names do not.
        del constant.dims[:]
        constant.dims.extend([1, 1, 1, 64])
    onnx.checker.check_model(model)
    return model, changes

def gamma_conv(source):
    """Broadcast gamma through a bias-free one-input-channel 1x1 convolution.

    [1,1,97,1] interpreted as NCHW -> Conv [1,64,97,1] -> transpose
    [0,3,2,1] gives the original [1,1,97,64]. Each output uses one product;
    all 64 gamma weights and the 97-position order remain intact.
    """
    import onnx
    from onnx import helper
    model, changes = explicit_gamma_rank(source)
    constants = {c.name: c for c in model.graph.initializer}
    targets = {change["consumer"]: change for change in changes}
    occupied = {node.name for node in model.graph.node}
    occupied.update(value for node in model.graph.node for value in list(node.input) + list(node.output))
    occupied.update(constants)
    rewritten = []
    for node in model.graph.node:
        change = targets.get(node.name)
        if change is None:
            rewritten.append(node)
            continue
        conv_name = node.name + "/gamma_conv"
        intermediate = conv_name + ":0"
        transpose_name = node.name + "/gamma_restore_axes"
        names = (conv_name, intermediate, transpose_name)
        require(all(name not in occupied for name in names), "Conv rewrite name collision")
        occupied.update(names)
        constant = constants[change["constant"]]
        del constant.dims[:]
        constant.dims.extend([64, 1, 1, 1])
        rewritten.append(helper.make_node("Conv", [change["variable"], constant.name],
                         [intermediate], name=conv_name, kernel_shape=[1, 1],
                         strides=[1, 1], pads=[0, 0, 0, 0], dilations=[1, 1], group=1))
        rewritten.append(helper.make_node("Transpose", [intermediate], list(node.output),
                         name=transpose_name, perm=[0, 3, 2, 1]))
        change["to_shape"] = [64, 1, 1, 1]
        change["replacement"] = {"conv": conv_name, "transpose": transpose_name,
                                 "conv_output_shape": [1, 64, 97, 1], "perm": [0, 3, 2, 1],
                                 "output_shape": [1, 1, 97, 64], "bias": False}
    del model.graph.node[:]
    model.graph.node.extend(rewritten)
    onnx.checker.check_model(model)
    return model, changes

def reciprocal_div(source):
    """Express the eight LayerNorm reciprocal values as literal float32 1 / x."""
    import onnx
    from onnx import numpy_helper
    model = copy.deepcopy(source)
    producers = {output: node for node in model.graph.node for output in node.output}
    targets = [n for n in model.graph.node if n.op_type == "Reciprocal"]
    require(len(targets) == 8, "Expected exactly eight LayerNorm reciprocals")
    occupied = {value for node in model.graph.node for value in list(node.input) + list(node.output)}
    occupied.update(c.name for c in model.graph.initializer)
    changes = []
    for node in targets:
        require(re.fullmatch(r".*/MixerBlock_[0-3]/layer_norm[12]/batchnorm/Rsqrt__\d+", node.name)
                and len(node.input) == 1 and len(node.output) == 1, "Unexpected reciprocal node")
        parent = producers.get(node.input[0])
        require(parent is not None and parent.op_type == "Sqrt" and len(parent.input) == 1,
                "LayerNorm reciprocal is not fed by Sqrt")
        name = node.name + "/literal_one"
        require(name not in occupied, "Reciprocal rewrite name collision")
        occupied.add(name)
        model.graph.initializer.append(numpy_helper.from_array(np.asarray([1.0], np.float32), name))
        denominator = node.input[0]
        node.op_type = "Div"
        node.input.insert(0, name)
        changes.append({"node": node.name, "from_op": "Reciprocal", "to_op": "Div",
                        "denominator": denominator, "numerator": name,
                        "numerator_float32": 1.0, "sqrt_unchanged": parent.name})
    onnx.checker.check_model(model)
    return model, changes

def neg_multiply(source):
    """Express eight LayerNorm negations as multiplication by literal float32 -1."""
    import onnx
    from onnx import numpy_helper
    model = copy.deepcopy(source)
    targets = [n for n in model.graph.node if n.op_type == "Neg"]
    require(len(targets) == 8, "Expected exactly eight LayerNorm negations")
    occupied = {value for node in model.graph.node for value in list(node.input) + list(node.output)}
    occupied.update(c.name for c in model.graph.initializer)
    changes = []
    for node in targets:
        require(re.fullmatch(r".*/MixerBlock_[0-3]/layer_norm[12]/batchnorm/Neg", node.name)
                and len(node.input) == 1 and len(node.output) == 1, "Unexpected negation node")
        name = node.name + "/literal_minus_one"
        require(name not in occupied, "Negation rewrite name collision")
        occupied.add(name)
        model.graph.initializer.append(numpy_helper.from_array(np.asarray([-1.0], np.float32), name))
        variable = node.input[0]
        node.op_type = "Mul"
        node.input.append(name)
        changes.append({"node": node.name, "from_op": "Neg", "to_op": "Mul",
                        "variable": variable, "factor": name, "factor_float32": -1.0})
    onnx.checker.check_model(model)
    return model, changes

def sqrt_pow(source):
    """Keep all nine nonnegative-domain square roots as Pow(x, exact float32 .5)."""
    import onnx
    from onnx import numpy_helper
    model = copy.deepcopy(source)
    targets = [n for n in model.graph.node if n.op_type == "Sqrt"]
    require(len(targets) == 9, "Expected eight LayerNorm Sqrts and one input-norm Sqrt")
    layer_norms = set()
    norm_count = 0
    occupied = {value for node in model.graph.node for value in list(node.input) + list(node.output)}
    occupied.update(c.name for c in model.graph.initializer)
    changes = []
    for node in targets:
        layer = re.fullmatch(r".*/MixerBlock_([0-3])/layer_norm([12])/batchnorm/Rsqrt", node.name)
        if layer:
            require(layer.groups() not in layer_norms, "Duplicate LayerNorm square-root target")
            layer_norms.add(layer.groups())
        else:
            require(node.name == "model_1/tf.compat.v1.norm/norm/Sqrt", "Unexpected square-root target")
            norm_count += 1
        require(len(node.input) == 1 and len(node.output) == 1, "Unexpected square-root arity")
        name = node.name + "/literal_half"
        require(name not in occupied, "Square-root rewrite name collision")
        occupied.add(name)
        model.graph.initializer.append(numpy_helper.from_array(np.asarray([0.5], np.float32), name))
        variable = node.input[0]
        node.op_type = "Pow"
        node.input.append(name)
        changes.append({"node": node.name, "from_op": "Sqrt", "to_op": "Pow",
                        "variable": variable, "exponent": name, "exponent_float32": 0.5,
                        "scope": "layer_norm" if layer else "input_coordinate_norm"})
    require(len(layer_norms) == 8 and norm_count == 1, "Incomplete square-root rewrite")
    onnx.checker.check_model(model)
    return model, changes

def div_multiply_pow(source):
    """Fixed-graph division rewrite; preserve each numerator and denominator exactly."""
    import onnx
    from onnx import helper, numpy_helper
    model = copy.deepcopy(source)
    prefix = "model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/"
    expected = {}
    for i in range(8):
        parent = prefix + "MixerBlock_%d/layer_norm%d/batchnorm/Rsqrt" % (i // 2, i % 2 + 1)
        name = parent + "__" + str(43 + 8 * i)
        expected[name] = (name + "/literal_one", parent)
    front = "model_1/tf.math.truediv_1/truediv"
    expected[front] = ("model_1/tf.math.subtract/Sub", "model_1/tf.math.reduce_mean_1/Mean")
    targets = [n for n in model.graph.node if n.op_type == "Div"]
    require(len(targets) == 9 and {n.name for n in targets} == set(expected),
            "Expected precisely the nine validated division nodes")
    producers = {v: n for n in model.graph.node for v in n.output}
    constants = {c.name: numpy_helper.to_array(c) for c in model.graph.initializer}
    occupied = {v for n in model.graph.node for v in list(n.input) + list(n.output)}
    occupied.update(c.name for c in model.graph.initializer)
    node_names = {n.name for n in model.graph.node}
    rewritten, changes = [], []
    for node in model.graph.node:
        if node.op_type != "Div":
            rewritten.append(node)
            continue
        require(tuple(node.input) == expected[node.name] and len(node.output) == 1,
                "Division operand identity or arity changed")
        numerator, denominator = node.input
        parent = producers.get(denominator)
        if node.name == front:
            require(parent is not None and parent.name == denominator and parent.op_type == "ReduceMean"
                    and numerator in producers and producers[numerator].op_type == "Sub"
                    and producers[numerator].name == numerator, "Coordinate normalization parents changed")
        else:
            one, half = constants.get(numerator), constants.get(denominator + "/literal_half")
            require(one is not None and one.dtype == np.float32 and one.shape == (1,) and one[0] == 1,
                    "LayerNorm division numerator is not literal float32 one")
            require(parent is not None and parent.name == denominator and parent.op_type == "Pow"
                    and len(parent.input) == 2 and parent.input[1] == denominator + "/literal_half"
                    and half is not None and half.dtype == np.float32 and half.shape == (1,) and half[0] == .5,
                    "LayerNorm division denominator is not the preserved half power")
        power = node.name + "/reciprocal_pow"
        exponent, reciprocal = power + "/literal_minus_one", power + ":0"
        require(power not in node_names and all(v not in occupied for v in (power, exponent, reciprocal)),
                "Division rewrite name collision")
        occupied.update((power, exponent, reciprocal)); node_names.add(power)
        model.graph.initializer.append(numpy_helper.from_array(np.asarray([-1.0], np.float32), exponent))
        rewritten.append(helper.make_node("Pow", [denominator, exponent], [reciprocal], name=power))
        node.op_type = "Mul"
        node.input[1] = reciprocal
        rewritten.append(node)
        changes.append({"node": node.name, "from_op": "Div", "to_ops": ["Pow", "Mul"],
                        "numerator": numerator, "denominator": denominator,
                        "reciprocal_node": power, "exponent_float32": -1.0,
                        "scope": "input_coordinate_normalization" if node.name == front else "layer_norm"})
    del model.graph.node[:]
    model.graph.node.extend(rewritten)
    onnx.checker.check_model(model)
    return model, changes

def save_json(path, value):
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)

def fixtures(quality_path, graph_path):
    match = re.search(r"kLandmarksSubsetIdxs = \{(.*?)\};", graph_path.read_text(), re.S)
    require(match is not None, "Official landmark subset missing")
    indices = [int(x) for x in match.group(1).replace("\n", "").split(",") if x.strip()]
    require(len(indices) == 146 and len(set(indices)) == 146 and min(indices) >= 0
            and max(indices) < 478, "Official 146-index contract differs")
    quality = json.loads(quality_path.read_text())
    require(quality["status"] == "success" and len(quality["samples"]) == 36, "Recorded fixture set differs")
    rows = []
    for sample in quality["samples"]:
        for prefix in ("npu", "reference"):
            xyz = np.asarray(sample[prefix + "_landmarks"], dtype=np.float32)
            require(xyz.shape == (1434,) and np.isfinite(xyz).all(), "Recorded landmarks invalid")
            points = xyz.reshape(478, 3)[indices, :2].copy()
            points *= np.asarray([640, 480], dtype=np.float32)
            rows.append((prefix + "-" + str(sample["time_ms"]), points[None],
                         np.asarray(sample[prefix + "_blendshapes"], dtype=np.float32)))
    random = np.random.RandomState(4103)
    for i in range(20):
        points = random.normal(size=(1, 146, 2)).astype(np.float32)
        rows.append(("synthetic-" + str(i), points * np.float32(2 ** (i % 7))
                     + np.asarray([i * 30, 230 - i * 9], dtype=np.float32), None))
    return rows, indices

def prepare(args):
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    report = {"status": "running", "started_utc": utc_now(), "script_sha256": sha256(Path(__file__)),
              "atol": ATOL, "rtol": 0, "reference": "original TFLite, no rewritten reference",
              "android_validated": False, "performance_validated": False}
    try:
        report["active_step"] = "import_onnx"
        save_json(directory / "numerical-validation.json", report)
        print("PREPARE import_onnx", flush=True)
        import onnx
        report["active_step"] = "import_onnxruntime"
        save_json(directory / "numerical-validation.json", report)
        print("PREPARE import_onnxruntime", flush=True)
        import onnxruntime as ort
        report["active_step"] = "import_tensorflow"
        save_json(directory / "numerical-validation.json", report)
        print("PREPARE import_tensorflow", flush=True)
        import tensorflow as tf
        report["active_step"] = "verify_and_rewrite"
        save_json(directory / "numerical-validation.json", report)
        print("PREPARE verify_and_rewrite", flush=True)
        require(sha256(args.source) == EXPECTED_SOURCE_SHA256, "Original TFLite SHA mismatch")
        report["source_sha256"] = sha256(args.source)
        report["source_path"] = str(args.source.resolve())
        report["base_onnx_sha256"] = sha256(args.base)
        report["fixture_files"] = {str(p.resolve()): sha256(p) for p in (args.quality, args.graph)}
        report["versions"] = {"tensorflow": tf.__version__, "onnx": onnx.__version__,
                              "onnxruntime": ort.__version__}
        baseline = onnx.load(str(args.base))
        candidate, report["changes"] = explicit_gamma_rank(baseline)
        conv_candidate, report["conv_changes"] = gamma_conv(baseline)
        div_candidate, report["reciprocal_changes"] = reciprocal_div(conv_candidate)
        neg_candidate, report["negation_changes"] = neg_multiply(div_candidate)
        pow_candidate, report["sqrt_changes"] = sqrt_pow(neg_candidate)
        mulpow_candidate, report["division_changes"] = div_multiply_pow(pow_candidate)
        _, report["toolkit_parser"] = _toolkit()
        from rknn.base.convertor.tensorflow2onnx.tf2onnx.tflite.Model import Model
        tflite = Model.GetRootAsModel(args.source.read_bytes(), 0)
        subgraph = tflite.Subgraphs(0)
        source_tensors = {subgraph.Tensors(i).Name().decode(): (i, subgraph.Tensors(i))
                          for i in range(subgraph.TensorsLength())}
        for change in report["changes"] + report["conv_changes"]:
            source_name = change["consumer"] + "/ReadVariableOp"
            require(source_name in source_tensors, "Gamma source tensor name not found")
            tensor_index, tensor = source_tensors[source_name]
            require(tensor.Type() == 1 and list(tensor.ShapeAsNumpy()) == [64],
                    "Expected original float16 gamma[64]")
            data = bytes(tflite.Buffers(tensor.Buffer()).DataAsNumpy())
            values = np.frombuffer(data, dtype="<f2").astype("<f4")
            require(hashlib.sha256(values.tobytes()).hexdigest() == change["float32_bytes_sha256"],
                    "ONNX gamma differs from original TFLite constant")
            change["source_tflite_tensor_index"] = tensor_index
            change["source_tflite_tensor_name"] = source_name
        paths = {name: directory / (name + ".onnx")
                 for name in ("baseline", "rank4-gamma", "gamma-conv", "gamma-conv-div", "gamma-conv-div-neg", "gamma-conv-div-neg-pow", "gamma-conv-mulpow")}
        onnx.save(baseline, str(paths["baseline"]))
        onnx.save(candidate, str(paths["rank4-gamma"]))
        onnx.save(conv_candidate, str(paths["gamma-conv"]))
        onnx.save(div_candidate, str(paths["gamma-conv-div"]))
        onnx.save(neg_candidate, str(paths["gamma-conv-div-neg"]))
        onnx.save(pow_candidate, str(paths["gamma-conv-div-neg-pow"]))
        onnx.save(mulpow_candidate, str(paths["gamma-conv-mulpow"]))
        report["candidates"] = {name: {"file": p.name, "sha256": sha256(p), "bytes": p.stat().st_size}
                                 for name, p in paths.items()}
        rows, report["official_indices"] = fixtures(args.quality, args.graph)
        report["input_contract"] = {"shape": [1, 146, 2], "attributes": ["x * 640", "y * 480"],
                                    "output_shape": [52], "output_order": "unchanged original model",
                                    "recorded_image_scaling_scope": "fixed 640x480, cross-checked to historical MediaPipe coefficients"}
        report["active_step"] = "numeric_reference_and_candidates"
        save_json(directory / "numerical-validation.json", report)
        print("PREPARE numeric_reference_and_candidates", flush=True)
        reference = tf.lite.Interpreter(model_path=str(args.source))
        reference.allocate_tensors()
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        sessions = {name: ort.InferenceSession(str(p), options) for name, p in paths.items()}
        for name, session in sessions.items():
            require(len(session.get_inputs()) == 1 and session.get_inputs()[0].shape == [1, 146, 2]
                    and len(session.get_outputs()) == 1 and session.get_outputs()[0].shape == [52],
                    "Candidate input/output contract changed: " + name)
        report["cases"] = []
        inputs, references = [], []
        for name, value, historical in rows:
            # These indices are from the exact hash-checked TFLite FlatBuffer, avoiding TF2.2 metadata getter bug.
            reference.set_tensor(0, value)
            reference.invoke()
            expected = reference.get_tensor(195)
            require(expected.shape == (52,) and np.isfinite(expected).all(), "TFLite reference invalid")
            inputs.append(value.copy()); references.append(expected.copy())
            row = {"fixture": name, "input_sha256": hashlib.sha256(value.tobytes()).hexdigest(),
                   "reference_sha256": hashlib.sha256(expected.tobytes()).hexdigest(), "candidates": {}}
            if historical is not None:
                row["historical_mediapipe_max_abs"] = float(np.max(np.abs(expected - historical)))
            for candidate_name, session in sessions.items():
                actual = session.run(None, {session.get_inputs()[0].name: value})[0]
                valid = actual.shape == (52,) and bool(np.isfinite(actual).all())
                difference = np.abs(actual - expected) if valid else np.asarray([np.inf])
                row["candidates"][candidate_name] = {
                    "max_abs": float(difference.max()), "mean_abs": float(difference.mean()),
                    "finite_full52": valid, "passed": valid and bool(np.all(difference <= ATOL))}
            report["cases"].append(row)
        np.savez_compressed(str(directory / "reference-fixtures.npz"),
                            inputs=np.stack(inputs), outputs=np.stack(references))
        report["case_count"] = len(rows)
        report["fixture_archive_sha256"] = sha256(directory / "reference-fixtures.npz")
        for name, p in paths.items():
            require(sha256(p) == report["candidates"][name]["sha256"], "Candidate changed during validation")
        require(len(rows) == 92, "Incomplete numerical fixture set")
        require(all(result["passed"] for row in report["cases"] for result in row["candidates"].values()),
                "Candidate exceeds the predetermined absolute error limit")
        require(max(row.get("historical_mediapipe_max_abs", 0) for row in report["cases"]) <= ATOL,
                "Pixel-coordinate preprocessing differs from historical MediaPipe output")
        report["status"] = "passed"
        report.pop("active_step", None)
    except BaseException as error:
        report["status"] = "error"; report["error"] = str(error)
        report["traceback"] = traceback.format_exc()
        traceback.print_exc()
    finally:
        report["finished_utc"] = utc_now()
        save_json(directory / "numerical-validation.json", report)
    print(json.dumps({"status": report["status"], "error": report.get("error")}), flush=True)
    return 0 if report["status"] == "passed" else 1

def compile_validated(args):
    validation = json.loads(args.validation.read_text())
    require(validation["status"] == "passed" and validation["case_count"] == 92
            and len(validation["cases"]) == 92 and validation["rtol"] == 0
            and validation["source_sha256"] == EXPECTED_SOURCE_SHA256 and validation["atol"] == ATOL,
            "Compilation requires completed original-TFLite numerical validation")
    selected = validation["candidates"][args.candidate]
    require(selected["file"] == args.candidate + ".onnx", "Unexpected candidate filename")
    model = args.validation.resolve().parent / selected["file"]
    require(sha256(model) == selected["sha256"], "Candidate SHA differs from validated bytes")
    require(all(row["candidates"][args.candidate]["passed"] for row in validation["cases"]),
            "Not all candidate cases passed")
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    report = {"status": "running", "started_utc": utc_now(), "model_sha256": sha256(model),
              "validation_sha256": sha256(args.validation), "candidate": args.candidate,
              "script_sha256": sha256(Path(__file__)), "steps": {},
              "config": {"target_platform": "rk3566", "float_dtype": "float16", "optimization_level": 3},
              "build_config": {"do_quantization": False}, "android_validated": False,
              "rknn_accuracy_validated": False, "performance_validated": False}
    compiler = None
    start = time.monotonic()
    try:
        factory, report["toolkit"] = _toolkit()
        save_json(directory / "compile-report.json", report)
        compiler = factory(verbose=True, verbose_file=str(directory / "compile.log"))
        artifact = directory / "face_blendshapes.rknn"
        for step, operation in (
                ("config", lambda: compiler.config(**report["config"])),
                ("load", lambda: compiler.load_onnx(model=str(model))),
                ("build", lambda: compiler.build(**report["build_config"])),
                ("export", lambda: compiler.export_rknn(str(artifact)))):
            report["active_step"] = step; save_json(directory / "compile-report.json", report)
            before = time.monotonic(); code = operation()
            report["steps"][step] = {"return_code": code, "elapsed_s": time.monotonic() - before}
            save_json(directory / "compile-report.json", report)
            require(code == 0, step + " returned " + str(code))
        require(artifact.is_file() and artifact.stat().st_size > 0, "Empty/missing export")
        report["artifact"] = {"sha256": sha256(artifact), "bytes": artifact.stat().st_size}
        report["status"] = "compiled"
    except BaseException as error:
        report["status"] = "error"; report["error"] = str(error)
        report["traceback"] = traceback.format_exc(); traceback.print_exc()
    finally:
        if compiler is not None:
            try:
                compiler.release()
            except BaseException as error:
                report["cleanup_error"] = str(error); report["status"] = "error"
        report["finished_utc"] = utc_now(); report["elapsed_s"] = time.monotonic() - start
        report.pop("active_step", None)
        save_json(directory / "compile-report.json", report)
    print(json.dumps({"status": report["status"], "error": report.get("error")}), flush=True)
    return 0 if report["status"] == "compiled" else 1

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    prepare_parser = modes.add_parser("prepare")
    for name in ("source", "base", "quality", "graph", "output"):
        prepare_parser.add_argument("--" + name, type=Path, required=True)
    compile_parser = modes.add_parser("compile")
    compile_parser.add_argument("--validation", type=Path, required=True)
    compile_parser.add_argument("--candidate", choices=("baseline", "rank4-gamma", "gamma-conv", "gamma-conv-div", "gamma-conv-div-neg", "gamma-conv-div-neg-pow", "gamma-conv-mulpow"), required=True)
    compile_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    return prepare(args) if args.mode == "prepare" else compile_validated(args)

if __name__ == "__main__":
    import faulthandler
    faulthandler.enable()
    sys.exit(main())
