# Oval-enclosure background revision

The user accepted the four existing styles and requested about 30% more decoration/coverage, shifted inward because a physical oval frame hides the display corners. The built-in image generation tool edited each existing PNG independently; the density percentage is qualitative art direction, not an exact measured pixel count. No oval frame or mask is baked into the rectangular assets.

The originals remain at `E:/tripo/assets/mirror-backgrounds-20261004`. Versioned outputs, exact edit prompts and source paths are in `oval-v2/edit-prompts-and-files.json`; previews are in `oval-v2/椭圆背景预览.md`. The application copies are byte-identical to these outputs. Three are 992×1586 RGB; the ancient pattern is 992×1585 RGB. The renderer supports both sizes without resampling the source files.

| Asset | SHA256 |
|---|---|
| cold-mist.png | a0dc5d01625a7a6d54105e13337cfa8fbc27121a43ea4c87ded0788cf4a141a8 |
| crimson-mist.png | 3b725a6e70ac29f24672d305063746287759cf44cbf699e0fe5e49d08ca0d12b |
| ancient-dark-pattern.png | 38a7a1bf872401af884d287edb515faf03d566183748d09c0e179664be88bb44 |
| violet-stardust.png | d03c09785180532066d8bb4b3e0187b7d8e75433f63d11faa8a9e51641996de4 |

Offline Gradle build and byte-exact APK asset verification passed. The in-place v24 APK update has SHA256 `2ff4dd4638f33ba947e14a85b923591c5502ee98ee86ca3acd7726f393a2c250`. Actual device `6L32552009566714` resumed INTERACTIVE face replay with NPU, 16 views and cold-mist background. Optical/runtime/scene preferences and saved avatar-selection bytes match their pre-update backups. Scale 2.34 was retained from the device. Evidence is in `E:/tripo/output/mirror-assets/20261004/oval-background-v24-check`.

This asset-only change retains the application code/JNI and existing private head. It adds no meshes, rendering passes or inference work. The selected image still costs about 6 MiB RGBA texture storage. No new FPS benchmark, live USB capture, or optical alignment qualification is claimed. Historical 16/20-view v23 benchmarks do not measure this new APK at scale 2.34.
