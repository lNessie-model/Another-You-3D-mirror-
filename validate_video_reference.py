"""Verify pixels, timing, decoding and sampled complete-face coverage of a recording."""
import csv
import hashlib
import json
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
name = "face-reference-20261001-01"
stable = "face-reference-stable-20261001-01"
ffmpeg = r"C:\Program Files\FFmpeg\bin\ffmpeg.exe"
ffprobe = r"C:\Program Files\FFmpeg\bin\ffprobe.exe"
meta = json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
assert meta["status"] == "success" and meta["camera"]["capture_failures"] == 0
with (root / f"{name}-timestamps.csv").open(encoding="utf-8", newline="") as source:
    times = list(csv.DictReader(source))
assert len(times) == meta["frames"]
assert [int(row["frame_index"]) for row in times] == list(range(meta["frames"]))
stamps = [int(row["camera_timestamp_ns"]) for row in times]
assert all(b > a for a, b in zip(stamps, stamps[1:]))
assert stamps[0] == meta["first_camera_timestamp_ns"] and stamps[-1] == meta["last_camera_timestamp_ns"]

def digest(path, algorithm):
    value = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

raw = root / f"{name}.nv21"
assert raw.stat().st_size == meta["frames"] * meta["width"] * meta["height"] * 3 // 2
raw_md5 = digest(raw, "md5")
decoded_md5 = subprocess.check_output([ffmpeg, "-v", "error", "-i", str(root / f"{name}-lossless.mkv"),
                                      "-map", "0:v:0", "-pix_fmt", "nv21", "-f", "md5", "-"], text=True).strip().split("=")[1]
assert raw_md5 == decoded_md5
videos = {}
for filename in (f"{name}.mp4", f"{name}-lossless.mkv", f"{stable}.mp4"):
    path = root / filename
    data = json.loads(subprocess.check_output([ffprobe, "-v", "error", "-count_frames", "-show_streams",
                                              "-show_format", "-of", "json", str(path)], text=True))
    assert len(data["streams"]) == 1 and data["streams"][0]["codec_type"] == "video"
    stream = data["streams"][0]
    assert (stream["width"], stream["height"]) == (meta["width"], meta["height"])
    if filename != f"{stable}.mp4":
        assert int(stream["nb_read_frames"]) == meta["frames"]
    subprocess.run([ffmpeg, "-v", "error", "-xerror", "-i", str(path), "-enc_time_base", "demux",
                    "-fps_mode", "passthrough", "-f", "null", "-"], check=True)
    videos[filename] = {"frames": int(stream["nb_read_frames"]), "duration_s": float(data["format"]["duration"]),
                        "bytes": path.stat().st_size, "sha256": digest(path, "sha256")}
coverage = {}
for clip in (name, stable):
    face = json.loads((root / f"{clip}-face-check.json").read_text(encoding="utf-8"))
    assert face["status"] == "success"
    coverage[clip] = {key: face[key] for key in ("decoded_samples", "face_samples", "complete_face_samples", "sample_step_ms")}
    if clip == stable:
        assert face["complete_face_samples"] == face["decoded_samples"]
manifest = {"status": "passed", "recording": meta, "videos": videos, "sampled_face_coverage": coverage,
            "lossless_nv21_roundtrip_md5": raw_md5, "audio": False, "stable_source_interval_s": [2, 37],
            "timing": "CFR playback at camera average rate; original camera timestamps preserved in CSV",
            "scope": "Offline inference/render input; does not reproduce USB capture load; face coverage is sampled"}
(root / "reference-validation.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"status": "passed", "frames": meta["frames"], "sampled_face_coverage": coverage}, ensure_ascii=False))
