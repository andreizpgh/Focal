# Focal — approved Focus wave

These are the user-approved assets supplied in `focal-logo-wave`. `source/`
preserves the seven SVGs for this wave and its original render PNG unchanged.
No rejected concept sheet is included.

`source/focal-icon-render.svg` is the geometry/material source of truth. Run the
generator instead of editing Android files or `generated/` independently.

- The adaptive launcher uses a transparent 432 px foreground and opaque 432 px
  background in `drawable-xxxhdpi`, each representing the complete 108 dp layer.
  The foreground retains the original nine bars, opacity falloff, Gaussian blur,
  and central white-to-grey gradient. The background retains the original
  gradient, highlight, and seeded grain. Neither layer contains the source's
  rounded-square clip or outer rim: the launcher supplies the mask.
- The themed vector retains the same nine-bar geometry and source opacity
  falloff, with no blur. The notification uses an opaque white, sharp version for
  status-bar contrast, uniformly mapped to a 20 px-wide mark in a 24 px canvas.
  Its core strokes remain nine separate components at 24 px.
- `generated/focal-lockup-*-outlined.svg` replaces the supplied text with paths
  from Inter 4.001 (`Inter.woff2`), weight 600. Native kerning, source placement,
  120-unit size, and −4.2-unit tracking are retained. No system-font fallback is
  used. The supplied lockups in `source/` remain unmodified references; use the
  outlined versions for distribution. Inter is covered by `Inter-LICENSE.txt`.
- `docs/mark.svg` and `docs/mark-preview.png` reproduce the supplied icon render
  for documentation, including its presentation mask/rim. They are not adaptive
  launcher layers. `generated/launcher-preview.png` shows the layer composition
  without that mask/rim.

## Rebuild

Requirements: Python with FontTools, Brotli and Pillow; Node with Playwright
available to `require('playwright')`; Chromium on `PATH` (or `FOCAL_CHROMIUM`).
If Playwright is installed outside the repository, expose it with `NODE_PATH`.
From the repository root:

```sh
python3 design/brand/build.py
python3 design/brand/build.py --check
```

Chromium renders the SVG directly; no generative redraw or image approximation
is used. The helper validates layer size/transparency and nine notification
stroke cores, and records source/font/output hashes and Chromium version in
`generated/manifest.json`. Pixel-identical checks require the recorded Chromium
version; other versions may rasterize blur or grain slightly differently.
Actual launcher masks and system notification tinting still depend on Android.
