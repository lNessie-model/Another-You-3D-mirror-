# Current head binding preview

Authoritative model: `E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/character.glb`, SHA256 `250941f86847ef22e599279fc6d00b62d80cf43ba9174d6ae01c1cb1e3b37d27`.

This head uses scene-node rotation plus mesh shape keys, not a dense skinned face skeleton. Head controls overall rotation; LeftEye/RightEye control gaze. Eyelid and lip annuli deform through named shape keys; jawOpen moves the front mandible. JawAttachments is empty. There are 43 direct facial bindings, eight gaze channels handled by the two eyes, and five product correctives for jaw/mouth-close and blink/squint/wide combinations. The actual GLB has 56 shape targets, including eight zeroed legacy gaze shapes.

![Actual neutral control nodes and mesh](E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/binding-preview-v3/binding-front.png)

Cyan: head pivot; yellow: eye nodes; green: actual blinking/eyelid edges; orange: actual lip edges. The display markers/links are projected in front of the mesh so they remain visible; their true coordinates and source hierarchy are in binding-report.json. Names Left/Right refer to the model's anatomical sides.

Neutral textured model:

![Neutral](E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/rig-views/neutral-front.png)

Half blink and full blink:

![Half blink](E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/rig-views/blink-half-front.png)

![Full blink](E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/rig-views/blink-both-front.png)

Jaw opening and smile with partial jaw opening:

![Jaw open](E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/rig-views/jaw-open-front.png)

![Smile and jaw](E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage5-oral-matte-v3/rig-views/smile-jaw-front.png)

These are actual production-rig offline poses, not live camera frames or optical display evidence. The textured editable workfile is `geralt-editable.blend`; the annotated diagram scene is `binding-preview-v3/binding-visualization.blend`. v26 on the device keeps mirroring, excludes eye amplification and uses a bounded mouth/brow response. Fresh face-present review of that response and a satisfactory final art result remain pending.
