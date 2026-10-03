"""Fixed host fixtures/JDK-output validation. No model rewriting, RKNN or device calls."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import numpy as np

REPO=Path(__file__).resolve().parents[1]
ROOT=REPO.parent
BASE=ROOT/'output/mirror-program/20261003'
BOUNDARY=BASE/'blendshape-numerical-diagnosis/normalized-boundary-host-v1'
BROADCAST=BASE/'blendshape-broadcast/gamma-conv-mulpow-v1'
PINS={
    'official':(ROOT/'native-tools/mediapipe-source/face_blendshapes_graph.cc','a3826ad2738315f1e046b36fd883825980e151db677e53d21ff35eaff64e4bd3'),
    'quality':(ROOT/'output/npu-face-optimization/20261002/quality-final/quality.json','1a9dfa35206154e195d928f08d715bdb4d94da9a888f588f02b31d117f92497e'),
    'validation':(BROADCAST/'numerical-validation.json','8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746'),
    'archive':(BROADCAST/'reference-fixtures.npz','45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8'),
    'front':(BOUNDARY/'front.onnx','8c724475e7a630b5703e2d77288b2a9453a40cc62ce01e41ba2f62503c97d3e7'),
    'original_suffix':(BOUNDARY/'original_suffix.onnx','b08568eedc9f53cb574ec4bd52ee8a78f945e6ba0aba0e7684f91becb8317d5b'),
    'copied_suffix':(BOUNDARY/'copied_suffix.onnx','61c61dda91356545c9397906b6872bcfc52849b796355b6f44cb03de72c6af9b'),
    'normalized':(BOUNDARY/'normalized_fp32.f32','ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b'),
    'tflite':(BOUNDARY/'original_tflite52.f32','5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602'),
}
SOURCE_FILES=['app/src/main/java/com/mirror/bench/NormalizedBlendshapeInput.java',
              'tests/NormalizedBlendshapeInputTest.java','tests/run_normalized_blendshape_input_tests.ps1',
              'tests/normalized_blendshape_input_reference.py']
def require(value,message):
    if not value: raise ValueError(message)
def digest(data): return hashlib.sha256(data).hexdigest()
def sha(path): return digest(path.read_bytes())
def save(path,value):
    with path.open('x',encoding='utf-8') as stream: json.dump(value,stream,indent=2,allow_nan=False);stream.write('\n')
def source_hashes(): return {name:sha(REPO/name) for name in SOURCE_FILES}
def sources():
    for name,(path,pin) in PINS.items():require(sha(path)==pin,'Changed fixed source: '+name)
    return {name:dict(sha256=pin,bytes=path.stat().st_size) for name,(path,pin) in PINS.items()}
def prepare(output):
    provenance=sources()
    official=PINS['official'][0].read_text(encoding='utf-8')
    match=re.search(r'kLandmarksSubsetIdxs = \{(.*?)\};',official,re.S);require(match is not None,'Missing official subset')
    subset=[int(x) for x in match.group(1).split(',') if x.strip()]
    require(len(subset)==len(set(subset))==146 and min(subset)==0 and max(subset)==477,'Changed official subset')
    validation=json.loads(PINS['validation'][0].read_text(encoding='utf-8'))
    require(validation['status']=='passed' and validation['atol']==1e-5 and validation['rtol']==0
            and validation['source_sha256']=='4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082','Original TF reference gate')
    with np.load(PINS['archive'][0],allow_pickle=False) as archive:raw,tf=archive['inputs'],archive['outputs']
    require(raw.dtype==tf.dtype==np.float32 and raw.shape==(92,1,146,2) and tf.shape==(92,52),'Reference shapes')
    require(tf.tobytes()==PINS['tflite'][0].read_bytes(),'Original TF bytes')
    require(digest(raw.tobytes())=='36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc','Raw92 input bytes')
    quality=json.loads(PINS['quality'][0].read_text(encoding='utf-8'))
    require(quality['status']=='success' and len(quality['samples'])==36,'Recorded fixture shape')
    xyz=[];portrait=[];ids=[]
    for sample in quality['samples']:
        for kind in ('npu','reference'):
            row=np.asarray(sample[kind+'_landmarks'],dtype=np.float32)
            require(row.shape==(1434,) and np.isfinite(row).all(),'Invalid recorded XYZ')
            points=row.reshape(478,3)[subset,:2].copy()
            landscape=points*np.asarray([640,480],dtype=np.float32)
            i=len(xyz);case=validation['cases'][i]
            require(case['fixture']==kind+'-'+str(sample['time_ms']) and landscape.tobytes()==raw[i].tobytes(),'Recorded raw input identity')
            xyz.append(row);portrait.append(points*np.asarray([480,640],dtype=np.float32));ids.append(case['fixture'])
    require(len(validation['cases'])==92,'Missing case records')
    for i,c in enumerate(validation['cases']):
        require(c['input_sha256']==digest(raw[i].tobytes()) and c['reference_sha256']==digest(tf[i].tobytes()),'Original per-case hashes')
    files={'official-subset.i32':np.asarray(subset,dtype='<i4').tobytes(),'landmarks72.f32':np.stack(xyz).astype('<f4').tobytes(),
           'raw92.f32':raw.astype('<f4').tobytes(),'portrait72.f32':np.stack(portrait).astype('<f4').tobytes(),
           'normalized92-reference.f32':PINS['normalized'][0].read_bytes()}
    output.mkdir(parents=True,exist_ok=False)
    for name,data in files.items():(output/name).write_bytes(data)
    report=dict(kind='normalized-blendshape-input-java-fixtures-v1',sources=provenance,source_files=source_hashes(),
                files={name:dict(sha256=digest(data),bytes=len(data)) for name,data in files.items()},
                cases=[dict(index=i,fixture=c['fixture'],group='real' if i<72 else 'synthetic',
                            input_sha256=c['input_sha256'],reference_sha256=c['reference_sha256']) for i,c in enumerate(validation['cases'])],
                normalized_abs_tolerance=1e-5,suffix_original_tflite_atol=1e-5,suffix_original_tflite_rtol=0,
                application_integrated=False,device_executed=False)
    save(output/'fixtures.json',report);print(json.dumps(dict(status='prepared',files=len(files),real_cases=len(xyz))))
def metrics(actual,expected):
    require(actual.shape==expected.shape and np.isfinite(actual).all() and np.isfinite(expected).all(),'Metric finite/shape')
    d=np.abs(actual.astype(np.float64)-expected.astype(np.float64));at=np.unravel_index(np.argmax(d),d.shape)
    return dict(max_abs=float(d.max()),mean_abs=float(d.mean()),bit_differences=int((actual.view('u4')!=expected.view('u4')).sum()),
                worst_index=[int(v) for v in at],actual=float(actual[at]),reference=float(expected[at]))
def verify(fixtures,java_output,report_path):
    import onnxruntime as ort
    import onnx
    from onnx import numpy_helper
    manifest=json.loads((fixtures/'fixtures.json').read_text(encoding='utf-8'))
    require(manifest['sources']==sources() and manifest['source_files']==source_hashes(),'Fixture/source identity changed')
    require(manifest['normalized_abs_tolerance']==manifest['suffix_original_tflite_atol']==1e-5
            and manifest['suffix_original_tflite_rtol']==0,'No tolerance changes allowed')
    for name,e in manifest['files'].items():
        p=fixtures/name;require(sha(p)==e['sha256'] and p.stat().st_size==e['bytes'],'Fixture changed: '+name)
    raw=np.fromfile(fixtures/'raw92.f32',dtype='<f4').reshape(92,1,146,2)
    normalized=np.fromfile(java_output/'java-normalized92.f32',dtype='<f4').reshape(92,1,146,2)
    expected=np.fromfile(PINS['normalized'][0],dtype='<f4').reshape(92,1,146,2)
    tf=np.fromfile(PINS['tflite'][0],dtype='<f4').reshape(92,52)
    require((java_output/'java-pixels72.f32').read_bytes()==raw[:72].tobytes(),'JDK selected pixel bits differ')
    require((java_output/'java-portrait72.f32').read_bytes()==(fixtures/'portrait72.f32').read_bytes(),'JDK portrait pixel bits differ')
    front=onnx.load(str(PINS['front'][0]));nodes=list(front.graph.node)
    require([n.op_type for n in nodes]==['ReduceMean','Sub','Mul','ReduceSum','Pow','ReduceMean','Pow','Mul'],'Eight-node formula changed')
    for index,axes in [(0,[1]),(3,[2]),(5,[1])]:
        attrs={a.name:onnx.helper.get_attribute_value(a) for a in nodes[index].attribute}
        require(attrs.get('axes')==axes and attrs.get('keepdims',1)==1,'Reduction axes/keepdims changed')
    constants={x.name:numpy_helper.to_array(x) for x in front.graph.initializer}
    for index,power in [(4,.5),(6,-1.)]:
        value=constants[nodes[index].input[1]];require(value.dtype==np.float32 and value.size==1 and float(value.reshape(-1)[0])==power,'Literal exponent changed')
    options=ort.SessionOptions();options.intra_op_num_threads=options.inter_op_num_threads=1;options.log_severity_level=3
    def run(key,rows,name,shape):
        runtime=ort.InferenceSession(str(PINS[key][0]),options);ins=runtime.get_inputs();outs=runtime.get_outputs()
        require(len(ins)==len(outs)==1 and ins[0].name==name and ins[0].shape==[1,146,2],'Unchanged ORT IO contract')
        result=np.stack([runtime.run(None,{name:row})[0] for row in rows]);require(result.shape==shape and result.dtype==np.float32,'Unchanged ORT output shape')
        return result
    actual_front=run('front',raw,'serving_default_input_points:0',(92,1,146,2))
    require(actual_front.tobytes()==expected.tobytes(),'Frozen original front differs from actual ORT replay')
    original=run('original_suffix',normalized,'model_1/tf.math.truediv_1/truediv',(92,52))
    copied=run('copied_suffix',normalized,'model_1/tf.math.truediv_1/truediv',(92,52))
    require(original.tobytes()==copied.tobytes(),'Copied/original suffix differ on Java input')
    groups={}
    for name,start,end in [('all',0,92),('real',0,72),('synthetic',72,92)]:
        groups[name]=dict(java_normalized_vs_original_front=metrics(normalized[start:end],expected[start:end]),
                          java_then_fp32_suffix_vs_original_tflite=metrics(original[start:end],tf[start:end]))
    norm_ok=groups['all']['java_normalized_vs_original_front']['max_abs']<=1e-5
    final_ok=groups['all']['java_then_fp32_suffix_vs_original_tflite']['max_abs']<=1e-5
    range_ok=bool((original>=-1e-5).all() and (original<=1+1e-5).all())
    report=dict(status='passed' if norm_ok and final_ok and range_ok else 'failed',kind='java-normalized-front-reference-v1',
        normalized_atol=1e-5,original_tflite_atol=1e-5,original_tflite_rtol=0,groups=groups,
        normalized_gate=norm_ok,original_tflite_gate=final_ok,range_valid=range_ok,finite=True,
        original_vs_copied_suffix_byte_identity=True,recorded72_raw_xy_bit_identity=True,recorded72_portrait_xy_bit_identity=True,
        sources=sources(),source_files=source_hashes(),fixture_manifest_sha256=sha(fixtures/'fixtures.json'),
        java_output_files={p.name:dict(sha256=sha(p),bytes=p.stat().st_size) for p in java_output.iterdir() if p.is_file()},
        runtime_versions=dict(numpy=np.__version__,onnx=onnx.__version__,onnxruntime=ort.__version__),
        application_integrated=False,device_executed=False,rknn_executed=False,performance_validated=False)
    save(report_path,report);print(json.dumps(report,indent=2));return 0 if report['status']=='passed' else 1
def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='mode',required=True)
    a=sub.add_parser('prepare');a.add_argument('--output',type=Path,required=True)
    a=sub.add_parser('verify');a.add_argument('--fixtures',type=Path,required=True);a.add_argument('--java-output',type=Path,required=True);a.add_argument('--report',type=Path,required=True)
    args=p.parse_args()
    if args.mode=='prepare':prepare(args.output);return 0
    return verify(args.fixtures,args.java_output,args.report)
if __name__=='__main__':sys.exit(main())
