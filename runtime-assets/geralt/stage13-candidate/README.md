# Stage13 GPU-verified candidate

Status: **GPU_VERIFIED_CANDIDATE / LIVE_CAMERA_PENDING**.

`character.glb` SHA-256: `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`.

`avatar.json` SHA-256: `b4d324f64ea3655fe01615ce97081c275d60d50312fd0d2b066f8a78e668032e`.

The files are exact copies of `geralt-rig-stage13-dentition-appearance-v3`.
The model has 19,907 instance vertices, 19,157 triangles, 7 draws and 29,141,924 GLB bytes.
Production worker and 253-state Blender controls checks passed. Actual Mali driver
and multiview gates passed together under run_id `568cbdb8-ba22-42f3-98bc-a7353cee66a6`.

The live camera had already failed before deployment. The failed live-preview
postcondition caused verified rollback to stage11 on the device, with v30 unchanged.
This folder is a candidate asset package and is not labeled device current.
Personal expression calibration, live camera re-test, optical acceptance and
the 16-view/30-FPS target remain outstanding.

The editable intermediate Blender files, failed versions, personal replay and
raw device evidence remain local. The source scripts and scope-limited findings
are included in the repository docs.
