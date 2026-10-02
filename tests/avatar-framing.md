# Fixed avatar framing evidence

`AvatarFraming` solves a pose-independent scale from a rotating head sphere and an
optional static AABB. Its default projection matches the avatar camera: distance
3, near 0.1, far 10, vertical `tan(FOV / 2) = 0.52`, parallel off-axis eye positions
between -0.2 and +0.2, and a maximum absolute NDC coordinate of 0.92. Pass the final
display aspect (1200 / 1920 = 0.625), not the offscreen view resolution's aspect.

For centered unfitted position `q`, scale `s`, camera distance `D`, eye offset `e`,
horizontal tangent `kx` and margin `L`, each horizontal projection bound is
`s * (sign*q.x + (L*kx - sign*e/D)*q.z) <= D*L*kx`.
Sphere support is `dot(normal, pivot-center) + radius*length(normal)`; static AABB
support selects the extreme corner. Both extreme eye offsets cover the whole
continuous view interval. Vertical and near/far clip planes use the same support
calculation. A small inward float rounding reserve is included.

For frozen `builtin-guide/character.glb`, SHA-256
`4e49c6ffb9af774b03d2490660df92601707f722c50fae0eff08fed1503edac3`:

- Neutral bounds center: `[0, 0.19683212, 0.00736560]`.
- Head pivot: `[0, 0.1716, 0]`.
- Head vertex radius: `0.256630092`; independently bounded combinations of all
  morph weights in `[0,1]` have the same maximum radius, on the hair.
- Safe sphere fit at aspect 0.625: `3.3153923` including numeric reserve.
- 693 combinations of pitch `[-45,45]` at 15-degree steps, yaw `[-65,65]` at
  13-degree steps, and roll `[-40,40]` at 10-degree steps, each at three eye
  positions, give worst absolute NDC `0.919418`.
- A separate direct support scan of those poses permits at most `3.317307`.
  Thus the continuous sphere guarantee is within 0.06% of this pose grid's bound.
  The previous neutral AABB fit (`6.184653`) cannot preserve the full angle range.

Run the real-loader fixture and pure math checks with:

```powershell
./tests/run_avatar_framing_tests.ps1 -AssetPath 'E:\tripo\device-lab\app\src\main\assets\avatars\builtin-guide\character.glb'
```

The test first exercises analytic/random sphere projections, static corners,
near/far limits and malformed inputs (45 assertions), then checks actual GLB
vertices at the pose grid. The sphere proof covers continuous head rotations;
the grid independently validates the integration assumptions.

Production uses `AvatarGeometryBounds.fromAsset(asset, rig)` to obtain the
`AvatarFraming` instance without a hard-coded radius. The factory evaluates each
vertex's morph interval for all independent weights in `[0,1]`, also including
node/mesh defaults outside that range. Eye/jaw descendants are bounded around
their control origin before applying the control's base transform. A complete
rotation sphere covers every continuous gaze/jaw angle; the jaw translation
interval includes both lateral directions and forward movement. The jaw's
near-unit, potentially unnormalized axis is included in its Rodrigues matrix
norm bound. Static nonuniform scale and shear formed by ancestor composition
use `||A||2 <= sqrt(||A^T A||infinity)`. Head rotation is bounded in Head's local
coordinates and then passed through the same world linear norm bound.

`AvatarGeometryBoundsTest` compares actual rig/deformer results for 216 combined
head/morph/gaze/jaw states at three views, including head angles through +/-90
degrees. It also exercises nonuniform rotated ancestors, an unbound default
morph weight of -2, factory preservation of live rig state, and uniform
`rootScale` changes. The final frozen asset's automatically computed scale is
`3.3153255`; the difference from the simple radius is an explicit float numeric
reserve. The simultaneous-control test's maximum NDC is `0.879718`.

These are mathematical/host geometry checks, not evidence of the device's
rasterization, appearance or frame rate.

The fit is fixed while pose changes. Do not multiply the result by an additional
zoom greater than one and still claim this margin guarantee. Uniform asset unit
conversion must transform the center, radius and static bounds before fitting.
