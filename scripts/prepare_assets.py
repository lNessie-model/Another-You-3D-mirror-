"""Fetch pinned official dependencies and test assets; never uploads device data."""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
records = []

def fetch(url, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        with urllib.request.urlopen(url, timeout=60) as response:
            destination.write_bytes(response.read())
    data = destination.read_bytes()
    records.append(dict(url=url, file=str(destination.relative_to(ROOT)),
                        bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    print(destination.name, len(data), flush=True)

for artifact in ('tasks-vision', 'tasks-core'):
    relative = f'com/google/mediapipe/{artifact}/1.0.0/{artifact}-1.0.0'
    for extension in ('pom', 'aar'):
        fetch(f'https://dl.google.com/dl/android/maven2/{relative}.{extension}',
              ROOT / 'maven' / f'{relative}.{extension}')
    with zipfile.ZipFile(ROOT / 'maven' / f'{relative}.aar') as archive:
        target = ROOT / 'tools' / f'{artifact}-classes.jar'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.read('classes.jar'))

fetch('https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task',
      ROOT / 'app/src/main/assets/face_landmarker.task')
fetch('https://storage.googleapis.com/mediapipe-assets/portrait.jpg',
      ROOT / 'app/src/main/assets/portrait.jpg')
(ROOT / 'asset-manifest.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
