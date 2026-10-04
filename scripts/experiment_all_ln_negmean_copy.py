"""Isolated fixed-eight-LN negative-mean replication candidate; never deploys a model.

Only the fixed eight negative-mean Mul inputs are expanded by a no-bias all-one 1x1
Conv followed by Transpose. Five fixed scale observations are reused, without
changing the frozen observational prepare script or its source SHA gate.
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
from diagnose_blendshape_numerics import load_frozen, require, save, errors
from prepare_blendshape_ln_taps import audit_ln_semantics, P
from experiment_first_ln_negmean_copy import original_gate as first_original_gate, replication_nodes as first_replication_nodes
from prepare_blendshape_scale_taps import TAPS, SOURCE_TAPS, TOTAL_ELEMENTS, INPUT, KIND, semantics_helper_sha

SOURCE_SHA = 'fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287'
SCALE_HELPER_SHA = 'd15171c1034108dd884304ae8a58a876f014aeaff7405ad697b5573499a9c510'
VARIANT = 'all_eight_ln_negative_mean_explicit_copy_v1'
FIRST_HELPER_SHA = '2d46d085cac380790335c2c55be9290231af9be27a78ab038a003c61679cb439'
ROOT = 'model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/'
PREFIXES = tuple(ROOT+'MixerBlock_'+str(b)+'/layer_norm'+str(n)+'/' for b in range(4) for n in (1,2))


def helper_hashes():
    scale = sha256(Path(__file__).with_name('prepare_blendshape_scale_taps.py'))
    require(scale == SCALE_HELPER_SHA, 'Frozen scale observation contract changed')
    first = sha256(Path(__file__).with_name('experiment_first_ln_negmean_copy.py'))
    require(first == FIRST_HELPER_SHA, 'Frozen first-copy helper changed')
    return dict(scale=scale, semantics=semantics_helper_sha(), first_copy=first)


def enumerate_branches(model):
    """Validate precisely eight known branches, not an arbitrary pattern rewrite."""
    from onnx import helper,numpy_helper
    first_original_gate(model)
    producers={}
    for n in model.graph.node:
        for output in n.output:
            require(output not in producers,'Duplicate producer');producers[output]=n
    constants={c.name:numpy_helper.to_array(c) for c in model.graph.initializer}
    found=[o for o in producers if '/layer_norm' in o and o.endswith('/batchnorm/mul_2')]
    require(found==[p+'batchnorm/mul_2' for p in PREFIXES],'Unexpected branch set/order')
    def node(output,op,inputs=None,attrs=None):
        n=producers.get(output)
        require(n is not None and n.op_type==op and (inputs is None or list(n.input)==inputs),'Changed branch node: '+output)
        if attrs is not None:
            require({a.name:helper.get_attribute_value(a) for a in n.attribute}==attrs,'Changed attributes: '+output)
        return n
    rows=[]
    for i,p in enumerate(PREFIXES):
        block=i//2;ln=i%2+1
        expected_source=(ROOT+'AddExtraTokens/concat' if i==0 else ROOT+'MixerBlock_'+str(block-1)+'/residual_channels/add'
                         if ln==1 else ROOT+'MixerBlock_'+str(block)+'/residual_tokens/add')
        mean=node(p+'moments/mean','ReduceMean',[expected_source],{'axes':[1],'keepdims':1})
        negative=node(p+'batchnorm/Neg','Mul',attrs={})
        require(len(negative.input)==2,'Changed negative operand count')
        literal=constants[negative.input[1]]
        require(literal.dtype==np.float32 and literal.shape==(1,) and literal.tobytes()==np.array([-1],np.float32).tobytes(),'Changed negative literal')
        reshape=node(negative.input[0],'Reshape',[mean.output[0],'new_shape__333'],{})
        shape=constants['new_shape__333']
        require(shape.dtype==np.int64 and shape.tolist()==[1,1,97,1],'Changed reshape shape')
        gamma=node(p+'batchnorm/mul/gamma_conv:0','Conv',attrs={'strides':[1,1],'dilations':[1,1],'kernel_shape':[1,1],'pads':[0,0,0,0],'group':1})
        require(len(gamma.input)==2,'Gamma Conv must not have bias')
        weight=constants[gamma.input[1]]
        require(weight.dtype==np.float32 and weight.shape==(64,1,1,1) and np.isfinite(weight).all(),'Changed gamma shape/type')
        invreshape=node(gamma.input[0],'Reshape',attrs={})
        require(list(invreshape.input)==[p+'batchnorm/Rsqrt__'+str(43+i*8)+':0','new_shape__333'],'Changed inverse reshape source')
        node(p+'batchnorm/mul','Transpose',[gamma.output[0]],{'perm':[0,3,2,1]})
        node(p+'batchnorm/mul_2','Mul',[negative.output[0],p+'batchnorm/mul'],{})
        xmul=node(p+'batchnorm/mul_1','Mul',attrs={})
        require(len(xmul.input)==2 and xmul.input[1]==p+'batchnorm/mul','Changed x Mul order')
        node(xmul.input[0],'Transpose',[expected_source],{'perm':[0,2,3,1]})
        node(p+'batchnorm/add_1','Add',[p+'batchnorm/mul_1',p+'batchnorm/mul_2'],{})
        rows.append(dict(index=i,prefix=p,mean_producer=mean.name,mean_input=expected_source,mean_axes=[1],keepdims=1,
            mean_input_shape=[1,64,1,97],mean_shape=[1,1,1,97],negative_shape=[1,1,97,1],mean_reshape=reshape.name,
            negative_producer=negative.name,scale_input_reshape=invreshape.name,gamma_weight=gamma.input[1],
            gamma_shape=list(weight.shape),gamma_bytes_sha256=hashlib.sha256(weight.tobytes()).hexdigest(),
            gamma_conv=gamma.name,gamma_output_shape=[1,64,97,1],restore_perm=[0,3,2,1],scale_shape=[1,1,97,64],
            target=p+'batchnorm/mul_2',affine_order='x*scale + (-mean)*scale',
            shape_basis='Fixed source SHA and explicit reshape/Conv/transpose semantics; actual compiler table is separately required'))
    return rows


def original_gate(model):
    return enumerate_branches(model)


def replication_nodes(index):
    from onnx import helper,numpy_helper
    require(type(index) is int and 0<=index<8,'Invalid fixed branch index')
    if index==0:return first_replication_nodes()
    base='diagnostic_negmean_copy_b'+str(index//2)+'_ln'+str(index%2+1)
    weight=numpy_helper.from_array(np.ones((64,1,1,1),np.float32),base+'_weights')
    conv=helper.make_node('Conv',[PREFIXES[index]+'batchnorm/Neg',weight.name],[base+'_conv:0'],name=base+'_conv',
        strides=[1,1],dilations=[1,1],kernel_shape=[1,1],pads=[0,0,0,0],group=1)
    transpose=helper.make_node('Transpose',[conv.output[0]],[base+'_restore:0'],name=base+'_restore',perm=[0,3,2,1])
    return [conv,transpose],weight


def restore_original(candidate):
    model=copy.deepcopy(candidate)
    for i in range(7,-1,-1):
        ns,weight=replication_nodes(i)
        found=[j for j,n in enumerate(model.graph.node) if PREFIXES[i]+'batchnorm/mul_2' in n.output]
        require(len(found)==1 and found[0]>=2,'Missing/duplicate target')
        j=found[0];target=model.graph.node[j]
        require(target.op_type=='Mul' and list(target.input)==[ns[1].output[0],PREFIXES[i]+'batchnorm/mul'],'Changed target inputs')
        require([n.SerializeToString() for n in model.graph.node[j-2:j]]==[n.SerializeToString() for n in ns],'Changed replication nodes/order')
        require(len(model.graph.initializer)>0 and model.graph.initializer[-1].SerializeToString()==weight.SerializeToString(),'Changed replication weight/order')
        target.input[0]=PREFIXES[i]+'batchnorm/Neg';del model.graph.node[j-2:j];del model.graph.initializer[-1]
    return model


def verify_all_rewrites(original,candidate):
    original_gate(original)
    require(restore_original(candidate).SerializeToString()==original.SerializeToString(),'Unrelated graph/parameter changes')


def rewrite_all_negative_mean(original):
    import onnx
    original_gate(original)
    model=copy.deepcopy(original)
    occupied={s for n in model.graph.node for s in list(n.input)+list(n.output)+[n.name]}
    occupied.update(c.name for c in model.graph.initializer)
    for i,p in enumerate(PREFIXES):
        ns,weight=replication_nodes(i)
        names=[weight.name]+[s for n in ns for s in [n.name,*n.output]]
        require(not occupied.intersection(names),'Replication name collision');occupied.update(names)
        indices=[j for j,n in enumerate(model.graph.node) if p+'batchnorm/mul_2' in n.output]
        require(len(indices)==1,'Missing/duplicate branch')
        j=indices[0];model.graph.node[j].input[0]=ns[1].output[0]
        for offset,n in enumerate(ns):model.graph.node.insert(j+offset,n)
        model.graph.initializer.append(weight)
    verify_all_rewrites(original,model);onnx.checker.check_model(model)
    return model

def attach_variant_taps(candidate):
    import onnx
    from onnx import helper, TensorProto
    original = restore_original(candidate)
    verify_all_rewrites(original,candidate)
    occupied = {s for n in candidate.graph.node for s in list(n.input)+list(n.output)+[n.name]}
    occupied.update(c.name for c in candidate.graph.initializer)
    model = copy.deepcopy(candidate)
    for (_,source,_),(_,alias,_) in zip(SOURCE_TAPS[:4],TAPS[:4]):
        require(alias not in occupied and alias+'_identity' not in occupied, 'Observation name collision')
        model.graph.node.append(helper.make_node('Identity',[source],[alias],name=alias+'_identity'))
    del model.graph.output[:]
    model.graph.output.extend(helper.make_tensor_value_info(name,TensorProto.FLOAT,dims) for _,name,dims in TAPS)
    onnx.checker.check_model(model)
    return model


def prepare(args, report):
    import onnx
    import onnxruntime as ort
    original_path,inputs,tflite = load_frozen(args.validation,'gamma-conv-mulpow')
    require(sha256(original_path)==SOURCE_SHA,'Original graph SHA changed')
    report['helper_sha256'] = helper_hashes()
    original = onnx.load(str(original_path))
    candidate = rewrite_all_negative_mean(original)
    tapped = attach_variant_taps(candidate)
    candidate_path = args.output/'candidate.onnx'
    model_path = args.output/'scale-taps.onnx'
    onnx.save(candidate,str(candidate_path)); onnx.save(tapped,str(model_path))
    opts = ort.SessionOptions(); opts.intra_op_num_threads=opts.inter_op_num_threads=1
    sessions = [ort.InferenceSession(str(p),opts) for p in [original_path,candidate_path,model_path]]
    values = [[] for _ in TAPS]
    for index,value in enumerate(inputs):
        original_final = sessions[0].run(None,{INPUT:value})[0]
        candidate_final = sessions[1].run(None,{INPUT:value})[0]
        actual = sessions[2].run(None,{INPUT:value})
        require(len(actual)==5,'Unexpected observation count')
        require(candidate_final.tobytes()==original_final.tobytes(), 'Rewrite changed final52 bytes, case '+str(index))
        require(actual[-1].tobytes()==original_final.tobytes(), 'Instrumentation changed final52 bytes, case '+str(index))
        for i,(_,_,shape) in enumerate(TAPS):
            require(actual[i].dtype==np.float32 and actual[i].shape==tuple(shape) and np.isfinite(actual[i]).all(),
                    'Unexpected observation shape/type/finite')
            values[i].append(actual[i].copy())
    final = np.stack(values[-1]); tf_error = errors(final,tflite)
    require(tf_error['nonfinite']==0 and tf_error['max_abs']<=1e-5,'Original TFLite FP32 gate failed')
    inputs.astype('<f4').tofile(str(args.output/'inputs.f32'))
    tflite.astype('<f4').tofile(str(args.output/'original-tflite52.f32'))
    outputs=[]
    for i,(label,name,shape) in enumerate(TAPS):
        data=np.stack(values[i]); filename='reference-'+label+'.f32'; path=args.output/filename
        data.astype('<f4').tofile(str(path))
        outputs.append(dict(index=i,label=label,name=name,shape=shape,elements=int(np.prod(shape)),file=filename,
                            sha256=sha256(path),bytes=path.stat().st_size,min=float(data.min()),max=float(data.max())))
    report.update(status='prepared',kind=KIND,model_variant=VARIANT,source_candidate_sha256=SOURCE_SHA,
        candidate_sha256=sha256(candidate_path),model_sha256=sha256(model_path),
        source_validation=str(args.validation.resolve()),source_validation_sha256=sha256(args.validation),
        case_count=92,input_shape=[1,146,2],input_elements=292,outputs=outputs,output_elements_per_case=TOTAL_ELEMENTS,
        input_sha256=sha256(args.output/'inputs.f32'),reference_tflite52_sha256=sha256(args.output/'original-tflite52.f32'),
        full52_rewrite_changed_cases=0,full52_rewrite_max_abs=0,full52_instrumentation_changed_cases=0,
        full52_instrumentation_max_abs=0,original_tflite_error=tf_error,original_tflite_max_abs=tf_error['max_abs'],
        atol=1e-5,rtol=0,onnxruntime_version=ort.__version__,observation_identity_nodes=4,
        replication_nodes=16,enumerated_branches=enumerate_branches(original),replication_scope='exact fixed eight original negative-mean branches',
        ln_semantics=audit_ln_semantics(original),source_tensors=[name for _,name,_ in SOURCE_TAPS])


def validate_manifest(path):
    import onnx
    m=json.loads(path.read_text(encoding='utf-8'))
    require(m.get('helper_sha256')==helper_hashes(),'Helpers changed')
    require(m.get('script_sha256')==sha256(Path(__file__)),'Experiment script changed after prepare')
    require(m.get('kind')==KIND and m.get('model_variant')==VARIANT and m.get('status')=='prepared'
            and m.get('source_candidate_sha256')==SOURCE_SHA and m.get('case_count')==92
            and m.get('input_shape')==[1,146,2] and m.get('input_elements')==292
            and m.get('output_elements_per_case')==TOTAL_ELEMENTS and m.get('full52_rewrite_changed_cases')==0
            and m.get('full52_instrumentation_changed_cases')==0 and m.get('full52_rewrite_max_abs')==0
            and m.get('full52_instrumentation_max_abs')==0 and m.get('original_tflite_max_abs',float('inf'))<=1e-5
            and m.get('atol')==1e-5 and m.get('rtol')==0,'Unvalidated fixed-eight candidate')
    validation=Path(m['source_validation'])
    require(sha256(validation)==m['source_validation_sha256'],'Original validation changed')
    original_path,inputs,tflite=load_frozen(validation,'gamma-conv-mulpow')
    original=onnx.load(str(original_path));candidate_path=path.parent/'candidate.onnx';model=path.parent/'scale-taps.onnx'
    require(sha256(candidate_path)==m['candidate_sha256'] and sha256(model)==m['model_sha256'],'Candidate model bytes changed')
    candidate=onnx.load(str(candidate_path));verify_all_rewrites(original,candidate)
    require(onnx.load(str(model)).SerializeToString()==attach_variant_taps(candidate).SerializeToString(),
            'Observed model contains other changes')
    require(len(m['outputs'])==5,'Unexpected output count')
    for i,((label,name,dims),entry) in enumerate(zip(TAPS,m['outputs'])):
        filename='reference-'+label+'.f32';p=path.parent/filename
        require(entry['index']==i and entry['label']==label and entry['name']==name and entry['shape']==dims
                and entry['elements']==int(np.prod(dims)) and entry['file']==filename
                and entry['bytes']==p.stat().st_size==92*int(np.prod(dims))*4 and entry['sha256']==sha256(p),
                'Reference contract changed')
    for name,key,expected in [('inputs.f32','input_sha256',inputs),('original-tflite52.f32','reference_tflite52_sha256',tflite)]:
        p=path.parent/name
        require(p.stat().st_size==expected.size*4 and sha256(p)==m[key]
                and p.read_bytes()==expected.astype('<f4').tobytes(),'Original input/reference changed')
    return m,model


def backend(args,report):
    manifest,path=validate_manifest(args.manifest)
    factory,report['toolkit']=_toolkit();runtime=None
    report.update(kind=KIND,model_variant=VARIANT,source_candidate_sha256=SOURCE_SHA,
        candidate_sha256=manifest['candidate_sha256'],manifest_sha256=sha256(args.manifest),model_sha256=sha256(path),steps={},
        config=dict(target_platform='rk3566',float_dtype='float16',optimization_level=3),build_config=dict(do_quantization=False))
    try:
        runtime=factory(verbose=True,verbose_file=str(args.output/'backend.log'))
        actions=[('config',lambda:runtime.config(**report['config'])),('load',lambda:runtime.load_onnx(model=str(path))),
                 ('build',lambda:runtime.build(do_quantization=False))]
        if args.mode=='compile':actions.append(('export',lambda:runtime.export_rknn(str(args.output/'scale-taps.rknn'))))
        else:actions.append(('simulator_init',lambda:runtime.init_runtime(target=None)))
        for name,action in actions:
            report['active_step']=name;save(args.output/'diagnostic.json',report)
            start=time.monotonic();rc=action();report['steps'][name]=dict(rc=rc,wall_s=time.monotonic()-start)
            save(args.output/'diagnostic.json',report);require(rc==0,name+' failed')
        if args.mode=='compile':
            artifact=args.output/'scale-taps.rknn';require(artifact.stat().st_size>0,'Empty RKNN export')
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
                    require(np.isfinite(result[i]).all(),'Nonfinite simulator tensor');values[i].append(result[i].copy())
            outputs=[]
            for i,(label,_,shape) in enumerate(TAPS):
                data=np.stack(values[i]).reshape(92,-1);filename='simulator-'+label+'.f32';p=args.output/filename
                data.astype('<f4').tofile(str(p))
                reference=np.fromfile(str(args.manifest.parent/('reference-'+label+'.f32')),dtype='<f4').reshape(92,-1)
                outputs.append(dict(label=label,name=TAPS[i][1],shape=shape,elements=int(np.prod(shape)),file=filename,
                    bytes=p.stat().st_size,sha256=sha256(p),error_vs_fp32=errors(data,reference)))
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
        scope='Fixed eight-LN negative-mean explicit-copy candidate; same first five observations; not an accepted repair')
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
