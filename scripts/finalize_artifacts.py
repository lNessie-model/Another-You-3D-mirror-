"""Record which binaries produced the matrix and passed the final reboot check."""
import hashlib
import json
import subprocess
from pathlib import Path
from device_profile import OUT

root=Path(__file__).resolve().parents[1]
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
measured=digest(OUT/'apks/MirrorDeviceLab-measured.apk')
final=digest(OUT/'apks/MirrorDeviceLab.apk')
post=json.loads((OUT/'post-reboot-joint.json').read_text(encoding='utf-8'))
assert post['apk_sha256']==final, 'Installed and delivered APK differ'
metadata=dict(measurement_apk_sha256=measured,delivery_apk_sha256=final,
              launcher_apk_sha256=digest(OUT/'apks/MirrorLauncher.apk'),
              post_reboot_installed_apk_sha256=post['apk_sha256'],
              source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
              final_change='GL error state is volatile; model, shaders and workload unchanged',
              post_reboot_case='post-reboot-joint.json')
(OUT/'build-provenance.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
print(json.dumps(metadata),flush=True)
