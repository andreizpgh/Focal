# Focal UI explorations

Four independent, interactive directions for Focal's record → review → copy flow:

- **A · Capture:** a recording instrument with a secondary archive.
- **B · Workspace:** a searchable working library with audio and text together.
- **C · Document:** one document from dictation through review.
- **D · Sessions:** a chronological library with timed transcript passages.

## Browser access

The owner-private [hosted gallery](https://focal-design-review.andreizakharov.chatgpt.site)
opens each prototype as a top-level page, avoiding the embedded-frame restriction
in the local preview. Its deployment source is maintained in the separate Sites repository.
The published version was checked for direct navigation, recording → pause →
resume → review, responsive layout, and JavaScript errors.

The [native Android gallery](https://focal-design-review.andreizakharov.chatgpt.site/native/)
shows the implemented Sessions interface with light/dark captures and the approved
Focus wave identity. It uses synthetic notes and does not establish STT quality.
The original prototype pack predates the approved identity; production brand
sources and rebuild instructions are in [`../brand/`](../brand/).

`Focal-interactive-prototypes.zip` is an offline alternative. Extract it and open
`index.html` in a regular browser, or open `A.html` through `D.html` directly.
The `browser-pack/` directory contains those same standalone files. All fonts and
preview images are embedded; browser validation found no external requests.

## Original comparison

From the repository root, serve the gallery locally:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory design/explorations
```

Open [the gallery](http://127.0.0.1:8765/). Each prototype also accepts
`?screen=library`, `?screen=recording`, or `?screen=review`. The gallery accepts
the same screen parameter and `export=1` for comparison captures. Its UI choice
persists in browser storage; copying uses the real clipboard API and reports
failure with text to copy manually.

Capture, speech recognition, and audio playback are simulated with synthetic
notes. These prototypes do not record microphone input or establish Android
behavior. Fonts and other required assets are local. Font licenses are in
`assets/`.

Browser validation used Chromium at a 390×844 prototype viewport. The gallery
was checked at widths of 390, 900, 1280, and 1800 pixels, including screen
switching, UI selection persistence, tabs, real clipboard write/read, and
clipboard failure. The three
`comparison-*.png` files show the library, recording, and review states at
1800 pixels wide.
