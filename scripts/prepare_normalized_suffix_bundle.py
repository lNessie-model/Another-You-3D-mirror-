"""One fixed normalized-suffix device bundle and strict wrapper. Never invokes ADB.

This intentionally does not call the legacy raw-pixel prepare function. The old
single-output comparator is retained, with stronger source/semantic/exit checks.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import numpy as np
import blendshape_device_check as core

REPO=Path(__file__).resolve().parents[1]
BASE=REPO.parent/'output/mirror-program/20261003/blendshape-numerical-diagnosis'
BROADCAST=BASE.parent/'blendshape-broadcast/gamma-conv-mulpow-v1'
HELPER=REPO/'app/build/normalized-suffix-device-helper-v1'
INPUT='model_1/tf.math.truediv_1/truediv'
FINAL='StatefulPartitionedCall:0'
KIND='fixed_normalized_suffix_original_front_v1'
VARIANT='all8_copy_normalized_fp32_front_suffix_v1'
OLD_SCOPE='isolated mixed CPU/NPU expression model; not full face pipeline or render FPS'
SCOPE='CPU FP32 fixed-front normalized [1,146,2] input; isolated mixed CPU/NPU suffix; not full face pipeline or render FPS'
OLD_ORDER='original C-order [1,146,2], adjacent pixel x/y; no preprocessing or transpose'
INPUT_ORDER='CPU FP32 fixed-front normalized C-order [1,146,2], adjacent x/y; no preprocessing or transpose in helper'
ACCURACY_SCOPE='Fixed original FP32 front computed offline + isolated mixed CPU/NPU suffix; original TF92 numeric screen, not live full pipeline'
CORE_SHA='e92b014b7889ae0cb43435cc08ae9803cfae289251c0109ccc205a644cea457b'
# Closed, byte-pinned source list for this reviewed experiment, not arbitrary import metadata.
SOURCES={
 'offline_freeze':(BASE/'normalized-suffix-v1-freeze/freeze.json','d8bb81cc55b6b1bee9043b69452d3b00fe80c02bdd7a97e2173fe6ba889fb589',9524),
 'boundary_freeze':(BASE/'normalized-boundary-host-v1/freeze.json','b9c93eece793b1a35a1018c23d001fc24b285a98eb3d228e34b260f5b651851e',4636),
 'boundary_report':(BASE/'normalized-boundary-host-v1/diagnostic.json','dcde869aebbfd5fd3c160dfe906e5ef75bec2c0d97c775cccaba69847000cbc4',583677),
 'prepare':(BASE/'normalized-suffix-v1/diagnostic.json','6993184bdcfbfcdbb15bf4f17e3989b1bb53d91b14723e8f006b65a58420e4b2',2264),
 'compile':(BASE/'normalized-suffix-v1-compile/diagnostic.json','3292a9039d6c86e3803b9897cc80e47a57626fe09a55dc2b62ebfe16592910e2',2246),
 'simulator':(BASE/'normalized-suffix-v1-simulator-existing-libs/diagnostic.json','b7561b2cc0e381db5a8b8398c768aa3c7a66fb0527a743f45ba75ba500043999',148215),
 'original_validation':(BROADCAST/'numerical-validation.json','8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746',188685),
 'fixtures':(BROADCAST/'reference-fixtures.npz','45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8',95742),
 'original_onnx':(BROADCAST/'gamma-conv-mulpow.onnx','fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287',1897131),
 'all8_onnx':(BASE/'all-ln-negmean-copy-v1/candidate.onnx','5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200',1902891),
 'front_onnx':(BASE/'normalized-boundary-host-v1/front.onnx','8c724475e7a630b5703e2d77288b2a9453a40cc62ce01e41ba2f62503c97d3e7',1810132),
 'suffix_onnx':(BASE/'normalized-suffix-v1/candidate.onnx','61c61dda91356545c9397906b6872bcfc52849b796355b6f44cb03de72c6af9b',1901627),
 'case_map':(BASE/'normalized-suffix-v1/cases.json','35dc7b4a65016162e67404dea0d6c4a0719ce37d9c36c3a18e11643276b6f814',34465),
 'build':(HELPER/'build.json','bc91ecbca188ca88725d227b5bef22cde7047663c043ea1c31ef8064f2a5b423',1729),
 'original_native':(REPO/'native/rknn_blendshape_probe.c','fbd596fca5678e3820cf153e994c9902e69a2407013c0f6bf1baa8ed9593d361',9525),
 'normalized_native':(REPO/'native/rknn_normalized_blendshape_probe.c','5dce648fff959ab3242230551aca5f823ce5c2423210aec05acb05b25a04b07a',9591),
 'native_contract':(REPO/'native/rknn_blendshape_contract.h','a4ea04445a061a9a8814a6467c28f9bdb6c61f0d3b5457a1790e7b3097245d6f',1189),
 'api_header':(REPO/'native/vendor/rknn/rknn_api.h','f280732314c2d9dae871faa84946efaa8477499579236474fe0c9ea8b018571e',26771),
 'build_script':(REPO/'scripts/build_normalized_blendshape_probe.ps1','397d1861ee4b986abf9a2a3cbf2b3044aef6937df79bc96ed24a9ae590882469',3106),
 'core_checker':(REPO/'scripts/blendshape_device_check.py',CORE_SHA,17733),
}
PAYLOADS={
 'face_blendshapes.rknn':(BASE/'normalized-suffix-v1-compile/normalized-suffix.rknn','17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c',1209569),
 'inputs.f32':(BASE/'normalized-suffix-v1/inputs.f32','ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b',107456),
 'references.f32':(BASE/'normalized-suffix-v1/original-tflite52.f32','5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602',19136),
 'rknn_blendshape_probe':(HELPER/'rknn_blendshape_probe','42d00cee0e8d5bf2a2dd4827da71837fa7bf7ad0883dd233875866f49b339d71',15616),
}
require=core.require


def pinned_bytes(path,expected_hash,expected_size):
    require(path.stat().st_size==expected_size,'Unexpected byte count: '+str(path))
    data=path.read_bytes();require(core.sha(data)==expected_hash,'Changed bytes: '+str(path));return data


def verify_native_diff(original,normalized):
    for old,new in [(OLD_SCOPE,SCOPE),(OLD_ORDER,INPUT_ORDER)]:
        require(original.count(old.encode())==1 and normalized.count(new.encode())==1,'Native literal multiplicity changed')
        original=original.replace(old.encode(),new.encode())
    require(original==normalized,'Native helper changes exceed two text literals')


def source_contract(sources,payloads):
    j=lambda name:json.loads(sources[name].decode('utf-8-sig'))
    prepared,compiled,simulator,boundary=j('prepare'),j('compile'),j('simulator'),j('boundary_report')
    require(prepared['status']=='prepared' and prepared['model_variant']==VARIANT and prepared['full_chain_byte_identity'] is True
            and prepared['original_tflite_max_abs']<=1e-5 and prepared['atol']==1e-5 and prepared['rtol']==0,'Missing FP32 chain gate')
    require(boundary['status']=='diagnostic_complete' and boundary['equivalence_gate'] is True and boundary['original_reference_gate'] is True,
            'Missing original front/unchanged suffix proof')
    require(prepared['boundary_freeze_sha256']==SOURCES['boundary_freeze'][1] and prepared['boundary_report_sha256']==SOURCES['boundary_report'][1]
            and prepared['model_sha256']==SOURCES['suffix_onnx'][1] and prepared['source_candidate_sha256']==SOURCES['original_onnx'][1]
            and prepared['copied_candidate_sha256']==SOURCES['all8_onnx'][1] and prepared['input_sha256']==PAYLOADS['inputs.f32'][1]
            and prepared['reference_tflite52_sha256']==PAYLOADS['references.f32'][1],'Preprocessing/model chain mismatch')
    require(compiled['status']=='compiled' and compiled['manifest_sha256']==SOURCES['prepare'][1]
            and compiled['artifact_sha256']==PAYLOADS['face_blendshapes.rknn'][1] and compiled['artifact_bytes']==1209569,
            'Compilation chain mismatch')
    require(simulator['status']=='completed' and simulator['completed_cases']==92 and simulator['target'] is None
            and simulator['manifest_sha256']==SOURCES['prepare'][1] and simulator['simulator_numeric_gate'] is True,
            'Missing reviewed simulator gate')
    for report in (compiled,simulator):
        require(report['model_sha256']==SOURCES['suffix_onnx'][1] and report['input_sha256']==PAYLOADS['inputs.f32'][1]
                and report['original_tflite52_sha256']==PAYLOADS['references.f32'][1]
                and all(row['rc']==0 for row in report['steps'].values()),'Backend evidence mismatch')
    # Match source rows inside the fixed frozen manifests, not just descriptive labels.
    frozen_entries=j('offline_freeze')['artifacts']
    frozen_hashes={v['sha256'] for v in frozen_entries.values()}
    require(all(SOURCES[k][1] in frozen_hashes for k in ('prepare','compile','simulator','boundary_report','boundary_freeze'))
            and all(PAYLOADS[k][1] in frozen_hashes for k in ('face_blendshapes.rknn','inputs.f32','references.f32')),
            'Offline freeze does not bind supplied artifacts')
    boundary_hashes={v['sha256'] for v in j('boundary_freeze')['files']}
    require(all(SOURCES[k][1] in boundary_hashes for k in ('front_onnx','suffix_onnx','boundary_report'))
            and PAYLOADS['inputs.f32'][1] in boundary_hashes,'Boundary freeze does not bind tensors')
    verify_native_diff(sources['original_native'],sources['normalized_native'])
    build=j('build');require(build['source_sha256']==SOURCES['normalized_native'][1]
        and build['baseline_source_sha256']==SOURCES['original_native'][1] and build['contract_sha256']==SOURCES['native_contract'][1]
        and build['api_header_sha256']==SOURCES['api_header'][1] and build['build_script_sha256']==SOURCES['build_script'][1]
        and build['helper_sha256']==PAYLOADS['rknn_blendshape_probe'][1] and build['bytes']==15616
        and build['input_order']==INPUT_ORDER and build['diagnostic_scope']==SCOPE
        and build['target']=='aarch64-linux-android30'
        and build['command'][1:8]==['--target=aarch64-linux-android30','-O2','-Wall','-Wextra','-Werror','-fPIE','-pie'],
        'Native build contract changed')
    elf=payloads['rknn_blendshape_probe']
    require(elf[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',elf,18)[0]==183,'Expected AArch64 little-endian ELF64')
    validation=j('original_validation');require(validation['status']=='passed' and validation['source_sha256']==core.TFLITE_SHA
        and validation['case_count']==92 and validation['fixture_archive_sha256']==SOURCES['fixtures'][1], 'Original reference chain mismatch')
    with np.load(io.BytesIO(sources['fixtures']),allow_pickle=False) as archive:
        raw,tf=archive['inputs'],archive['outputs']
    require(raw.dtype==tf.dtype==np.float32 and raw.shape==(92,1,146,2) and tf.shape==(92,52)
        and tf.tobytes()==payloads['references.f32'],'Original reference was replaced')
    normalized=np.frombuffer(payloads['inputs.f32'],dtype='<f4').reshape(92,292)
    mapping=j('case_map');require(len(mapping)==len(validation['cases'])==92,'Missing original cases')
    cases=[]
    for i,(record,old) in enumerate(zip(mapping,validation['cases'])):
        raw_sha=core.sha(raw[i].tobytes());norm_sha=core.sha(normalized[i].tobytes());ref_sha=core.sha(tf[i].tobytes())
        group='real' if i<72 else 'synthetic'
        require(old['input_sha256']==raw_sha and old['reference_sha256']==ref_sha and record==dict(index=i,
            fixture=old['fixture'],group=group,original_input_sha256=raw_sha,original_reference_sha256=ref_sha,normalized_input_sha256=norm_sha),
            'Original/normalized row mapping mismatch')
        cases.append(dict(index=i,fixture=old['fixture'],group=group,input_sha256=norm_sha,reference_sha256=ref_sha,
                          raw_input_sha256=raw_sha,normalized_input_sha256=norm_sha))
    return cases


def prepare_bundle(bundle):
    bundle=Path(bundle)
    if bundle.exists():raise FileExistsError(bundle)
    require(core.file_sha(Path(core.__file__))==CORE_SHA,'Legacy core checker changed')
    sources={k:pinned_bytes(*spec) for k,spec in SOURCES.items()}
    payloads={k:pinned_bytes(*spec) for k,spec in PAYLOADS.items()}
    cases=source_contract(sources,payloads)
    manifest=dict(schema_version=1,kind=KIND,model_variant=VARIANT,case_count=92,cases=cases,input_shape=[1,146,2],output_shape=[52],
        input_contract=INPUT_ORDER,thresholds=core.THRESHOLDS,runtime_sha256=core.RUNTIME_SHA,reference_tflite_sha256=core.TFLITE_SHA,
        accuracy_scope=ACCURACY_SCOPE,
        cpu_front_deployed=False,application_eligible=False,core_checker_sha256=CORE_SHA,wrapper_sha256=core.file_sha(Path(__file__)),
        files={k:dict(sha256=v[1],bytes=v[2]) for k,v in PAYLOADS.items()},
        provenance={k:dict(file=k+'.bin',sha256=v[1],bytes=v[2]) for k,v in SOURCES.items()})
    bundle.mkdir(parents=True,exist_ok=False);(bundle/'provenance').mkdir()
    for name,data in payloads.items():(bundle/name).write_bytes(data)
    for name,data in sources.items():(bundle/'provenance'/(name+'.bin')).write_bytes(data)
    core.write_json(bundle/'manifest.json',manifest)
    validate_bundle(bundle)
    return manifest


def validate_bundle(bundle):
    bundle=Path(bundle);m=core.read_json(bundle/'manifest.json')
    require(core.file_sha(Path(core.__file__))==CORE_SHA and m['core_checker_sha256']==CORE_SHA
        and m['wrapper_sha256']==core.file_sha(Path(__file__)),'Checker source changed')
    require(m['schema_version']==1 and m['kind']==KIND and m['model_variant']==VARIANT and m['case_count']==92
        and m['input_shape']==[1,146,2] and m['output_shape']==[52] and m['input_contract']==INPUT_ORDER
        and m['thresholds']==core.THRESHOLDS and m['runtime_sha256']==core.RUNTIME_SHA
        and m['reference_tflite_sha256']==core.TFLITE_SHA and m['accuracy_scope']==ACCURACY_SCOPE
        and m['cpu_front_deployed'] is False and m['application_eligible'] is False,
        'Normalized bundle contract changed')
    require(m['files']=={k:dict(sha256=v[1],bytes=v[2]) for k,v in PAYLOADS.items()},'Fixed payload pins changed')
    require(m['provenance']=={k:dict(file=k+'.bin',sha256=v[1],bytes=v[2]) for k,v in SOURCES.items()},'Fixed provenance pins changed')
    payloads={k:pinned_bytes(bundle/k,v[1],v[2]) for k,v in PAYLOADS.items()}
    sources={k:pinned_bytes(bundle/'provenance'/(k+'.bin'),v[1],v[2]) for k,v in SOURCES.items()}
    require(m['cases']==source_contract(sources,payloads),'Normalized row identities changed')
    return m


def check_bundle(bundle,evidence):
    bundle,evidence=Path(bundle),Path(evidence);validate_bundle(bundle)
    exit_path=evidence/'native-exit.json';external=core.read_json(exit_path)
    require(type(external.get('exit_code')) is int and external['exit_code']==0,'External native exit must be exactly integer zero')
    log=evidence/'report.jsonl';rows=[json.loads(s) for s in log.read_text(encoding='utf-8-sig').splitlines() if s.strip()]
    require([r.get('event') for r in rows[:7]]==['start','init','sdk','io_count','tensor_attr','tensor_attr','feed_contract'],
            'Native event prefix/order mismatch')
    require(rows[0].get('scope')==SCOPE and rows[6].get('input_order')==INPUT_ORDER,'Wrong helper input semantics')
    for row,kind,name,dims,size in [(rows[4],'input',INPUT,[1,146,2],584),(rows[5],'output',FINAL,[52],104)]:
        require(row.get('kind')==kind and row.get('name')==name and row.get('index')==0 and row.get('n_dims')==len(dims)
            and row.get('dims')==dims and row.get('n_elems')==size//2 and row.get('type')==1 and row.get('size')==size
            and row.get('fmt')==3 and row.get('qnt_type')==0 and row.get('w_stride')==0 and row.get('size_with_stride')==size,
            'Unrecognized exact normalized tensor attrs: '+kind)
    result=core.compare_bundle(bundle,log,evidence/'outputs.f32',evidence/'before.sha256',evidence/'after.sha256')
    result.update(core_status=result['status'],status='diagnostic_complete',model_variant=VARIANT,
        application_eligible=False,cpu_front_deployed=False,input_semantics=INPUT_ORDER,
        native_process_exit_code_requires_external_zero_check=False,external_native_exit=external,
        external_native_exit_sha256=core.file_sha(exit_path),wrapper_sha256=core.file_sha(Path(__file__)),core_checker_sha256=CORE_SHA,
        provenance_verified=True)
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='mode',required=True)
    prepare=sub.add_parser('prepare');prepare.add_argument('--bundle',type=Path,required=True)
    check=sub.add_parser('check')
    for name in ['bundle','evidence','output']:check.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args()
    try:
        if args.mode=='prepare':
            m=prepare_bundle(args.bundle);print(json.dumps(dict(status='prepared',cases=m['case_count'],application_eligible=False)));return 0
        report=check_bundle(args.bundle,args.evidence);core.write_json(args.output,report)
        print(json.dumps({k:report[k] for k in ['status','passed','max_absolute_error','mean_absolute_error','application_eligible']}))
        return 0 if report['passed'] else 1
    except (ValueError,KeyError,TypeError,OSError) as error:
        result=dict(status='error',passed=False,application_eligible=False,error=str(error))
        if args.mode=='check' and not args.output.exists():core.write_json(args.output,result)
        print(json.dumps(result),file=sys.stderr);return 2


if __name__=='__main__':sys.exit(main())
