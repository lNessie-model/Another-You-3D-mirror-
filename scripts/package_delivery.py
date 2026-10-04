"""Bundle the deliverables without duplicating the large factory APK backups."""
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path
from device_profile import OUT

root=Path(__file__).resolve().parents[1]
delivery=OUT/'delivery'
delivery.mkdir(exist_ok=True)
for filename in ['YS-L6实机测试报告.md','measurements.csv','results-validation.json','cleanup-journal.json']:
    shutil.copy2(OUT/filename,delivery/filename)
shutil.copy2(root/'scripts/Restore-Factory.ps1',delivery/'Restore-Factory.ps1')
for filename in ['MirrorDeviceLab.apk','MirrorLauncher.apk']:
    shutil.copy2(OUT/'apks'/filename,delivery/filename)
files=subprocess.check_output(['git','ls-files'],cwd=root,text=True,encoding='utf-8').splitlines()
archive=OUT/'YS-L6测试工具与结果.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as output:
    for path in delivery.iterdir():
        if path.is_file(): output.write(path,path.name)
    for filename in files:
        path=root/filename
        if path.is_file(): output.write(path,'source/'+filename)
    for path in OUT.glob('*.json'):
        output.write(path,'raw/'+path.name)
    for path in OUT.glob('*.png'):
        output.write(path,'screenshots/'+path.name)
    output.write(OUT/'apks/MirrorDeviceLab-measured.apk','raw/MirrorDeviceLab-measured.apk')
manifest=[]
for path in [archive,*delivery.glob('*.apk')]:
    manifest.append(dict(file=path.name,bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
(OUT/'delivery-sha256.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps(manifest),flush=True)
