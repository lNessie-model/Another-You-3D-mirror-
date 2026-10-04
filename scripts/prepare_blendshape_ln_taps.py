"""Fixed first-LayerNorm diagnostic, with no computational graph rewrites.

Only five explicitly named outputs are exposed. Never selects an Android device,
accepts a deployment model, or changes original TFLite precision thresholds.
"""
import argparse
import copy
import json
from pathlib import Path
import sys
import time
import traceback
import numpy as np
from compile_blendshape_loweropt import _toolkit, sha256, utc_now
from diagnose_blendshape_numerics import load_frozen, require, save, errors

KIND='fixed_first_layernorm_five_tap_diagnostic'
SOURCE_SHA='fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287'
P = 'model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/MixerBlock_0/layer_norm1/'
TOKEN = 'model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/AddExtraTokens/concat'
SOURCE_TAPS = [
    ('token_embedding', TOKEN, [1,64,1,97]),
    ('mean', P+'moments/mean', [1,1,1,97]),
    ('inv_std', P+'batchnorm/Rsqrt__43:0', [1,1,1,97]),
    ('affine', P+'batchnorm/add_1', [1,1,97,64]),
    ('final52', 'StatefulPartitionedCall:0', [52]),
]
TAPS = [(label, alias, dims) for (label, _, dims), alias in zip(SOURCE_TAPS,
    ('diagnostic_ln_token', 'diagnostic_ln_mean', 'diagnostic_ln_invstd',
     'diagnostic_ln_affine', 'StatefulPartitionedCall:0'))]
TOTAL_ELEMENTS = 12662
INPUT = 'serving_default_input_points:0'


def audit_ln_semantics(original):
    """Reject changed statistics/epsilon/affine topology before adding observations.

    No algebraic rewrite, shape inference, or normalization is performed here.
    prepare additionally pins the entire source model, including all weights.
    """
    import hashlib
    from onnx import helper, numpy_helper
    producers = {}
    for node in original.graph.node:
        for name in node.output:
            require(name not in producers, 'Duplicate producer: '+name)
            producers[name] = node
    constants = {c.name: numpy_helper.to_array(c) for c in original.graph.initializer}
    def node(output, op, inputs=None, attrs=None):
        n = producers.get(output)
        require(n is not None and n.op_type == op and (inputs is None or list(n.input) == inputs),
                'Changed first-LN operator/wiring: '+output)
        for name, value in (attrs or {}).items():
            found = [helper.get_attribute_value(a) for a in n.attribute if a.name == name]
            require(found == [value], 'Changed first-LN attribute: '+output+'/'+name)
        return n
    def literal(name, value, shape=(1,), dtype=np.float32):
        a = constants.get(name)
        require(a is not None and a.dtype == dtype and a.shape == shape
                and a.tobytes() == np.full(shape, value, dtype=dtype).tobytes(), 'Changed literal: '+name)
    mean = P+'moments/mean'; variance = P+'moments/variance'
    diff = P+'moments/SquaredDifference'; squared = P+'moments/SquaredDifference__42:0'
    node(TOKEN, 'Concat', attrs={'axis': 3})
    for output, source in ((mean,TOKEN), (variance,squared)):
        node(output, 'ReduceMean', [source], {'axes':[1], 'keepdims':1})
    node(diff, 'Sub', [TOKEN,mean]); node(squared,'Mul',[diff,diff])
    node('Transpose__164:0','Reshape',[mean,'new_shape__333'])
    a = constants.get('new_shape__333')
    require(a is not None and a.dtype == np.int64 and a.tolist() == [1,1,97,1], 'Changed mean reshape')
    neg = node(P+'batchnorm/Neg','Mul')
    require(len(neg.input)==2 and neg.input[0]=='Transpose__164:0','Changed negative-mean order')
    literal(neg.input[1],-1)
    node('Transpose__127:0','Transpose',[TOKEN],{'perm':[0,2,3,1]})
    epsilon = constants.get('const_fold_opt__389')
    gamma = constants.get('const_fold_opt__388')
    require(epsilon is not None and epsilon.dtype == np.float32 and epsilon.shape == ()
            and hashlib.sha256(epsilon.tobytes()).hexdigest() ==
            'b213852deb9c883bea76a885d484bfccd6aa025fab4714959e5bed1a32a41d22', 'Changed epsilon bytes')
    require(gamma is not None and gamma.dtype == np.float32 and gamma.shape == (64,1,1,1)
            and hashlib.sha256(gamma.tobytes()).hexdigest() ==
            '1afc69f8c025b18297241346060211825e083066982f9af1c5ecad3bc3f2a955', 'Changed gamma bytes')
    node('Transpose__246:0','Transpose',['Reshape__245:0'],{'perm':[0,3,1,2]})
    epsilon_reshape = node('Reshape__245:0','Reshape')
    require(list(epsilon_reshape.input)==['const_fold_opt__389','Concat__244:0'],'Changed epsilon source')
    node(P+'batchnorm/add','Add',[variance,'Transpose__246:0'])
    sqrt = node(P+'batchnorm/Rsqrt','Pow')
    require(len(sqrt.input)==2 and sqrt.input[0]==P+'batchnorm/add','Changed sqrt order')
    literal(sqrt.input[1],.5)
    inverse = node(P+'batchnorm/Rsqrt__43/reciprocal_pow:0','Pow')
    require(len(inverse.input)==2 and inverse.input[0]==P+'batchnorm/Rsqrt','Changed reciprocal order')
    literal(inverse.input[1],-1)
    inv = node(P+'batchnorm/Rsqrt__43:0','Mul')
    require(len(inv.input)==2 and inv.input[1]==P+'batchnorm/Rsqrt__43/reciprocal_pow:0','Changed inverse scale order')
    literal(inv.input[0],1)
    node('Transpose__232:0','Reshape',[P+'batchnorm/Rsqrt__43:0','new_shape__333'])
    node(P+'batchnorm/mul/gamma_conv:0','Conv',['Transpose__232:0','const_fold_opt__388'],
         {'strides':[1,1], 'dilations':[1,1], 'kernel_shape':[1,1], 'pads':[0,0,0,0], 'group':1})
    node(P+'batchnorm/mul','Transpose',[P+'batchnorm/mul/gamma_conv:0'],{'perm':[0,3,2,1]})
    node(P+'batchnorm/mul_2','Mul',[P+'batchnorm/Neg',P+'batchnorm/mul'])
    node(P+'batchnorm/mul_1','Mul',['Transpose__127:0',P+'batchnorm/mul'])
    node(P+'batchnorm/add_1','Add',[P+'batchnorm/mul_1',P+'batchnorm/mul_2'])
    return dict(reduce_axes=[1], keepdims=1, epsilon_value=float(epsilon),
                epsilon_bytes_sha256=hashlib.sha256(epsilon.tobytes()).hexdigest(),
                gamma_shape=list(gamma.shape),gamma_bytes_sha256=hashlib.sha256(gamma.tobytes()).hexdigest(),
                affine_order='x*scale + (-mean)*scale',
                inverse_std_order='1 * Pow(Pow(variance + epsilon, 0.5), -1)',
                observation_purpose='Bound the first statistics/affine interval; not a root-cause claim')


def attach_ln_taps(original):
    import onnx
    from onnx import helper, TensorProto
    def shape(v): return [d.dim_value for d in v.type.tensor_type.shape.dim]
    require(len(original.graph.input)==1 and original.graph.input[0].name==INPUT
            and original.graph.input[0].type.tensor_type.elem_type==TensorProto.FLOAT
            and shape(original.graph.input[0])==[1,146,2]
            and len(original.graph.output)==1 and original.graph.output[0].name==TAPS[-1][1]
            and original.graph.output[0].type.tensor_type.elem_type==TensorProto.FLOAT
            and shape(original.graph.output[0])==[52], 'Unexpected frozen model IO')
    audit_ln_semantics(original)
    occupied={v for n in original.graph.node for v in list(n.input)+list(n.output)}
    occupied.update(c.name for c in original.graph.initializer)
    node_names={n.name for n in original.graph.node}
    for _,alias,_ in TAPS[:4]:
        require(alias not in occupied and alias+'_identity' not in node_names,'Observation name collision')
    model=copy.deepcopy(original)
    for (_,source,_),(_,alias,_) in zip(SOURCE_TAPS[:4],TAPS[:4]):
        model.graph.node.append(helper.make_node('Identity',[source],[alias],name=alias+'_identity'))
    del model.graph.output[:]
    model.graph.output.extend(helper.make_tensor_value_info(name,TensorProto.FLOAT,dims) for _,name,dims in TAPS)
    onnx.checker.check_model(model)
    return model


def prepare(args,report):
    import onnx
    import onnxruntime as ort
    path,inputs,tflite=load_frozen(args.validation,'gamma-conv-mulpow')
    require(sha256(path)==SOURCE_SHA,'This profile only observes the frozen MulPow candidate')
    original=onnx.load(str(path));tapped=attach_ln_taps(original)
    require([n.SerializeToString() for n in original.graph.node]==[n.SerializeToString() for n in tapped.graph.node[:-4]],
            'Computational nodes changed')
    require([n.SerializeToString() for n in original.graph.initializer]==[n.SerializeToString() for n in tapped.graph.initializer],
            'Parameters changed')
    model_path=args.output/'ln-taps.onnx';onnx.save(tapped,str(model_path))
    opts=ort.SessionOptions();opts.intra_op_num_threads=opts.inter_op_num_threads=1
    base=ort.InferenceSession(str(path),opts);probe=ort.InferenceSession(str(model_path),opts)
    values=[[] for _ in TAPS]
    for value in inputs:
        baseline=base.run(None,{INPUT:value})[0];actual=probe.run(None,{INPUT:value})
        require(len(actual)==5,'Unexpected tap count')
        for i,(_,_,shape) in enumerate(TAPS):
            require(actual[i].dtype==np.float32 and actual[i].shape==tuple(shape) and np.isfinite(actual[i]).all(),
                    'Unexpected LN tap tensor')
            values[i].append(actual[i].copy())
        require(actual[-1].tobytes()==baseline.tobytes(),'Instrumentation changed final52 values')
    final=np.stack(values[-1]);tf_error=errors(final,tflite)
    require(tf_error['nonfinite']==0 and tf_error['max_abs']<=1e-5,'Original TFLite FP32 gate failed')
    inputs.astype('<f4').tofile(str(args.output/'inputs.f32'))
    tflite.astype('<f4').tofile(str(args.output/'original-tflite52.f32'))
    outputs=[]
    for i,(label,name,shape) in enumerate(TAPS):
        data=np.stack(values[i]);filename='reference-'+label+'.f32'
        data.astype('<f4').tofile(str(args.output/filename))
        outputs.append(dict(index=i,label=label,name=name,shape=shape,elements=int(np.prod(shape)),file=filename,
                            sha256=sha256(args.output/filename),bytes=(args.output/filename).stat().st_size,
                            min=float(data.min()),max=float(data.max())))
    report.update(status='prepared',kind=KIND,candidate_sha256=SOURCE_SHA,model_sha256=sha256(model_path),
        source_validation_sha256=sha256(args.validation),case_count=92,input_shape=[1,146,2],input_elements=292,
        outputs=outputs,output_elements_per_case=TOTAL_ELEMENTS,input_sha256=sha256(args.output/'inputs.f32'),
        reference_tflite52_sha256=sha256(args.output/'original-tflite52.f32'),
        full52_instrumentation_changed_cases=0,full52_instrumentation_max_abs=0,original_tflite_error=tf_error,
        original_tflite_max_abs=tf_error['max_abs'],onnxruntime_version=ort.__version__,
        observation_identity_nodes=4,ln_semantics=audit_ln_semantics(original),source_tensors=[name for _,name,_ in SOURCE_TAPS])


def validate_manifest(path):
    m=json.loads(path.read_text(encoding='utf-8'))
    require(m['kind']==KIND and m['status']=='prepared' and m['candidate_sha256']==SOURCE_SHA
            and m['case_count']==92 and m['input_shape']==[1,146,2] and m['input_elements']==292
            and m['output_elements_per_case']==TOTAL_ELEMENTS and m['full52_instrumentation_changed_cases']==0
            and m['full52_instrumentation_max_abs']==0 and m['original_tflite_max_abs']<=1e-5,
            'Unvalidated fixed first-LN model')
    require(len(m['outputs'])==5,'Unexpected manifest output count')
    model=path.parent/'ln-taps.onnx'
    require(sha256(model)==m['model_sha256'],'LN model bytes changed')
    for i,((label,name,dims),entry) in enumerate(zip(TAPS,m['outputs'])):
        filename='reference-'+label+'.f32';p=path.parent/filename
        require(entry['index']==i and entry['label']==label and entry['name']==name and entry['shape']==dims
                and entry['elements']==int(np.prod(dims)) and entry['file']==filename
                and entry['bytes']==p.stat().st_size==92*int(np.prod(dims))*4 and entry['sha256']==sha256(p),
                'LN reference contract changed')
    for name,key,size in [('inputs.f32','input_sha256',92*292*4),('original-tflite52.f32','reference_tflite52_sha256',92*52*4)]:
        require((path.parent/name).stat().st_size==size and sha256(path.parent/name)==m[key],'Input/reference bytes changed')
    return m,model


def backend(args,report):
    manifest,path=validate_manifest(args.manifest)
    factory,report['toolkit']=_toolkit();runtime=None
    report.update(kind=KIND,manifest_sha256=sha256(args.manifest),model_sha256=sha256(path),steps={},
                  config=dict(target_platform='rk3566',float_dtype='float16',optimization_level=3),
                  build_config=dict(do_quantization=False))
    try:
        runtime=factory(verbose=True,verbose_file=str(args.output/'backend.log'))
        actions=[('config',lambda:runtime.config(**report['config'])),('load',lambda:runtime.load_onnx(model=str(path))),
                 ('build',lambda:runtime.build(do_quantization=False))]
        if args.mode=='compile':actions.append(('export',lambda:runtime.export_rknn(str(args.output/'ln-taps.rknn'))))
        else:actions.append(('simulator_init',lambda:runtime.init_runtime(target=None)))
        for name,action in actions:
            report['active_step']=name;save(args.output/'diagnostic.json',report)
            start=time.monotonic();rc=action();report['steps'][name]=dict(rc=rc,wall_s=time.monotonic()-start)
            save(args.output/'diagnostic.json',report);require(rc==0,name+' failed')
        if args.mode=='compile':
            artifact=args.output/'ln-taps.rknn';require(artifact.stat().st_size>0,'Empty RKNN export')
            report.update(status='compiled',artifact_sha256=sha256(artifact),artifact_bytes=artifact.stat().st_size)
        else:
            report['active_step']='simulator_inference';save(args.output/'diagnostic.json',report)
            inputs=np.fromfile(str(args.manifest.parent/'inputs.f32'),dtype='<f4').reshape(92,1,146,2)
            values=[[] for _ in TAPS]
            for value in inputs:
                result=runtime.inference(inputs=[value],inputs_pass_through=[0])
                require(isinstance(result,list) and len(result)==5,'Unexpected simulator count')
                for i,(_,_,shape) in enumerate(TAPS):
                    require(result[i].dtype==np.float32 and result[i].shape==tuple(shape),'Unexpected simulator shape/type')
                    require(np.isfinite(result[i]).all(),'Nonfinite simulator tensor')
                    values[i].append(result[i].copy())
            outputs=[]
            for i,(label,_,shape) in enumerate(TAPS):
                data=np.stack(values[i]).reshape(92,-1);filename='simulator-'+label+'.f32'
                data.astype('<f4').tofile(str(args.output/filename))
                reference=np.fromfile(str(args.manifest.parent/('reference-'+label+'.f32')),dtype='<f4').reshape(92,-1)
                outputs.append(dict(label=label,name=TAPS[i][1],shape=shape,elements=int(np.prod(shape)),
                                    file=filename,bytes=(args.output/filename).stat().st_size,sha256=sha256(args.output/filename),
                                    error_vs_fp32=errors(data,reference)))
            report.update(status='completed',target=None,outputs=outputs)
        report.pop('active_step',None)
    finally:
        if runtime is not None:runtime.release()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['prepare','compile','simulate'])
    p.add_argument('--validation',type=Path);p.add_argument('--manifest',type=Path);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    report=dict(status='running',mode=args.mode,started_utc=utc_now(),script_sha256=sha256(Path(__file__)),
                application_eligible=False,android_accuracy_validated=False,performance_evidence=False,
                scope='Fixed first-LN observations only; graph outputs may change RKNN optimization')
    try:
        require(args.validation is not None if args.mode=='prepare' else args.manifest is not None,'Missing mode input')
        (prepare if args.mode=='prepare' else backend)(args,report)
    except BaseException as error:
        report.update(status='error',error=str(error),traceback=traceback.format_exc());traceback.print_exc()
    finally:
        report['finished_utc']=utc_now();save(args.output/'diagnostic.json',report)
    print(json.dumps(dict(status=report['status'],error=report.get('error'))),flush=True)
    return 0 if report['status'] in ('prepared','compiled','completed') else 1


if __name__=='__main__':sys.exit(main())
