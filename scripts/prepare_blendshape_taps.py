"""Prepare five bounded diagnostic outputs on the frozen, inaccurate RKNN candidate.

This observes the graph; it does not replace the application's model or accept its accuracy.
Only original input/centered coordinates/scale/normalized coordinates/final52 are exported.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
import time
import traceback
import numpy as np
from compile_blendshape_loweropt import _toolkit, sha256, utc_now
from diagnose_blendshape_numerics import load_frozen, require, save

TAPS = [
    ('input_echo', 'diagnostic_input_echo', [1,146,2]),
    ('centered', 'model_1/tf.math.subtract/Sub', [1,146,2]),
    ('scale', 'model_1/tf.math.reduce_mean_1/Mean', [1,1,1]),
    ('normalized', 'model_1/tf.math.truediv_1/truediv', [1,146,2]),
    ('final52', 'StatefulPartitionedCall:0', [52]),
]


def attach_taps(original):
    import onnx
    from onnx import helper, TensorProto
    require(len(original.graph.input)==1 and original.graph.input[0].name=='serving_default_input_points:0'
            and len(original.graph.output)==1 and original.graph.output[0].name==TAPS[-1][1],
            'Unexpected original graph IO')
    producers={v:n for n in original.graph.node for v in n.output}
    require(all(name in producers for _,name,_ in TAPS[1:]),'Missing observation tensor')
    require(producers[TAPS[1][1]].op_type=='Sub' and producers[TAPS[2][1]].op_type=='ReduceMean'
            and producers[TAPS[3][1]].op_type=='Mul','Frozen candidate front-end changed')
    occupied={v for n in original.graph.node for v in list(n.input)+list(n.output)}
    occupied.update(c.name for c in original.graph.initializer)
    require(TAPS[0][1] not in occupied and all(n.name!='diagnostic_input_identity' for n in original.graph.node),
            'Diagnostic echo name collision')
    model=copy.deepcopy(original)
    model.graph.node.append(helper.make_node('Identity',['serving_default_input_points:0'],[TAPS[0][1]],
                                            name='diagnostic_input_identity'))
    del model.graph.output[:]
    model.graph.output.extend([helper.make_tensor_value_info(name,TensorProto.FLOAT,shape)
                               for _,name,shape in TAPS[:-1]])
    model.graph.output.append(copy.deepcopy(original.graph.output[0]))
    onnx.checker.check_model(model)
    return model


def prepare(args, report):
    import onnx
    import onnxruntime as ort
    path, inputs, tflite=load_frozen(args.validation,'gamma-conv-mulpow')
    original=onnx.load(str(path)); tapped=attach_taps(original)
    require([n.SerializeToString() for n in original.graph.node]==
            [n.SerializeToString() for n in tapped.graph.node[:-1]],'Original nodes mutated')
    require([c.SerializeToString() for c in original.graph.initializer]==
            [c.SerializeToString() for c in tapped.graph.initializer],'Original parameters mutated')
    model_path=args.output/'front-taps.onnx';onnx.save(tapped,str(model_path))
    opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
    base=ort.InferenceSession(str(path),opts);probe=ort.InferenceSession(str(model_path),opts)
    expected=[[] for _ in TAPS]; changed=0; max_full=0.
    for value in inputs:
        baseline=base.run(None,{base.get_inputs()[0].name:value})[0]
        actual=probe.run(None,{probe.get_inputs()[0].name:value})
        require(len(actual)==len(TAPS),'Tap count mismatch')
        for i,(_,_,shape) in enumerate(TAPS):
            require(actual[i].dtype==np.float32 and actual[i].shape==tuple(shape)
                    and np.isfinite(actual[i]).all(),'Tap shape/type/finite changed')
            expected[i].append(actual[i].copy())
        require(actual[0].tobytes()==value.tobytes(),'Input echo does not preserve exact order/values')
        changed+=int(actual[-1].tobytes()!=baseline.tobytes())
        max_full=max(max_full,float(np.max(np.abs(actual[-1]-baseline))))
    # No tolerance is used for diagnostic-output instrumentation on this fixed ONNX graph.
    require(changed==0,'Adding diagnostic outputs changed full52 FP32 results')
    inputs.astype('<f4').tofile(str(args.output/'inputs.f32'))
    tflite.astype('<f4').tofile(str(args.output/'original-tflite52.f32'))
    outputs=[]
    for i,(label,name,shape) in enumerate(TAPS):
        values=np.stack(expected[i]); filename='reference-'+label+'.f32'
        values.astype('<f4').tofile(str(args.output/filename))
        outputs.append(dict(index=i,label=label,name=name,shape=shape,elements=int(np.prod(shape)),
                            file=filename,sha256=sha256(args.output/filename),bytes=(args.output/filename).stat().st_size,
                            min=float(values.min()),max=float(values.max()),
                            reference='unchanged candidate ONNX FLOAT32 intermediate'))
    report.update(status='prepared',candidate_sha256=sha256(path),model_sha256=sha256(model_path),
                  source_validation_sha256=sha256(args.validation),case_count=92,input_shape=[1,146,2],
                  input_elements=292,outputs=outputs,output_elements_per_case=929,
                  input_sha256=sha256(args.output/'inputs.f32'),reference_tflite52_sha256=sha256(args.output/'original-tflite52.f32'),
                  full52_instrumentation_changed_cases=changed,full52_instrumentation_max_abs=max_full,
                  original_tflite_max_abs=float(np.max(np.abs(np.stack(expected[-1])-tflite))),
                  scope='Diagnostic intermediates only; source RKNN failed accuracy; graph outputs may change compiler optimization')


def compile_probe(args, report):
    manifest=json.loads(args.manifest.read_text(encoding='utf-8'))
    require(manifest['status']=='prepared' and manifest['case_count']==92
            and manifest['full52_instrumentation_changed_cases']==0
            and manifest['original_tflite_max_abs']<=1e-5,'Unvalidated diagnostic ONNX')
    path=args.manifest.parent/'front-taps.onnx'
    require(sha256(path)==manifest['model_sha256'],'Tap model changed')
    factory,report['toolkit']=_toolkit(); compiler=None
    report['manifest_sha256']=sha256(args.manifest);report['model_sha256']=sha256(path)
    report['config']=dict(target_platform='rk3566',float_dtype='float16',optimization_level=3)
    report['steps']={}
    try:
        compiler=factory(verbose=True,verbose_file=str(args.output/'compile.log'))
        for name,action in [('config',lambda:compiler.config(**report['config'])),
                            ('load',lambda:compiler.load_onnx(model=str(path))),
                            ('build',lambda:compiler.build(do_quantization=False)),
                            ('export',lambda:compiler.export_rknn(str(args.output/'front-taps.rknn')))]:
            report['active_step']=name;save(args.output/'diagnostic.json',report)
            start=time.monotonic();rc=action();report['steps'][name]=dict(rc=rc,wall_s=time.monotonic()-start)
            save(args.output/'diagnostic.json',report);require(rc==0,name+' failed')
        report['artifact_sha256']=sha256(args.output/'front-taps.rknn')
        report['artifact_bytes']=(args.output/'front-taps.rknn').stat().st_size
        report['status']='compiled';report.pop('active_step',None)
    finally:
        if compiler is not None:compiler.release()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['prepare','compile'])
    parser.add_argument('--validation',type=Path)
    parser.add_argument('--manifest',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    report=dict(status='running',mode=args.mode,started_utc=utc_now(),script_sha256=sha256(Path(__file__)),
                android_accuracy_validated=False,application_performance_validated=False)
    try:
        require(args.validation is not None if args.mode=='prepare' else args.manifest is not None,'Missing mode input')
        (prepare if args.mode=='prepare' else compile_probe)(args,report)
    except BaseException as error:
        report['status']='error';report['error']=str(error);report['traceback']=traceback.format_exc();traceback.print_exc()
    finally:
        report['finished_utc']=utc_now();save(args.output/'diagnostic.json',report)
    print(json.dumps({'status':report['status'],'error':report.get('error')}),flush=True)
    return 0 if report['status'] in ('prepared','compiled') else 1


if __name__=='__main__':sys.exit(main())
