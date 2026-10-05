"""Verify packaged historical binary assets and selected-role references without rendering."""
import argparse,hashlib,json,struct
from pathlib import Path

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(65536),b''):h.update(chunk)
    return h.hexdigest()
def verify(assets):
    assets=Path(assets).resolve();library=assets/'library'
    catalog=json.loads((library/'catalog.json').read_text('utf-8'))
    live=json.loads((assets/'avatars/catalog/catalog.json').read_text('utf-8'))
    roles={e['id']:e for e in live['entries']};rows=[];seen=set();files={'catalog.json'}
    for entry in catalog['entries']:
        id=entry['id'];assert id not in seen;seen.add(id)
        record={'id':id,'type':entry['type'],'status':entry['status']}
        for field in ('source','preview'):
            relative=entry[field+'Path'];path=(assets/relative).resolve()
            assert (library/id).resolve() in path.parents,'Cross-entry file reference'
            assert digest(path)==entry[field+'Sha256'],'Asset bytes do not match metadata'
            files.add(path.relative_to(library).as_posix());record[field+'Bytes']=path.stat().st_size
            if field=='preview' or entry['type']=='image':
                with path.open('rb') as stream:header=stream.read(33)
                assert header[:8]==b'\x89PNG\r\n\x1a\n' and header[12:16]==b'IHDR'
                w,h=struct.unpack_from('>II',header,16);assert w>0 and h>0
                if field=='preview':assert max(w,h)<=1024
                record[field+'ImageSize']=[w,h]
            else:
                with path.open('rb') as stream:
                    header=stream.read(20);magic,version,total,n,kind=struct.unpack('<IIIII',header)
                    assert magic==0x46546c67 and version==2 and total==path.stat().st_size and kind==0x4e4f534a
                    assert n<=1024*1024
                    doc=json.loads(stream.read(n))
                assert not any('uri' in b for b in doc['buffers'])
                assert not any('uri' in image for image in doc.get('images',[]))
                record['selfContainedGlb']=True
        if entry['status']=='runtime_ready':
            role=roles[entry['liveRoleId']];assert role['modelSha256']==entry['liveModelSha256']
            assert digest(assets/role['directory']/'character.glb')==role['modelSha256']
            assert digest(assets/role['directory']/'avatar.json')==role['manifestSha256']
            record['runtimeReferenceVerified']=True
        rows.append(record)
    actual={p.relative_to(library).as_posix() for p in library.rglob('*') if p.is_file()}
    assert actual==files,'Unindexed files must not silently enter the release'
    return {'passed':True,'entries':len(rows),'binaryAndPreviewShaVerified':True,
        'bundledBytes':sum((library/file).stat().st_size for file in actual),'entriesVerified':rows,
        'scope':'Historical binary/preview integrity and current runtime references; not a facial-rig/art/camera/performance gate'}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--assets',type=Path,required=True);p.add_argument('--report',type=Path)
    a=p.parse_args();r=verify(a.assets)
    if a.report:
        assert not a.report.exists();a.report.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps({k:v for k,v in r.items() if k!='entriesVerified'},ensure_ascii=False))
