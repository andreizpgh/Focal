An updated Focal alpha for trying the Sessions interface and everyday voice-note workflow.

**[Download Focal.apk](https://github.com/andreizpgh/Focal/releases/download/ux-alpha-2/Focal.apk)**

Android 8.0+ and an ARM64 phone are required. Open the APK from your downloads;
if prompted, allow that browser or file manager to install apps. The speech model
is included. No account, network permission or separate model download is needed.

Since Alpha 1:

- Sessions layout, Focus wave identity, light/dark/system themes and a compact
  speech-language selector. Choose the recording language before tapping Record.
- More reliable editing, search, Rename, rotation and large-text handling.
- Interrupted recordings offer Continue transcription even when saved audio has
  not yet reached the transcription queue. Unprocessed tails are protected from
  audio removal; completed text and manual edits survive recovery.
- When notifications are disabled, the recording screen explains how to return
  to Focal to pause or finish. Leaving the app does not deliberately stop recording.

This is the exact **development-signed debug APK** verified locally from
[`fe70758`](https://github.com/andreizpgh/Focal/commit/fe70758350a114d5e66775ee114cd48778217594).
All **89 Android tests passed without failures or skips**, including eight
mandatory real-model checks, on a dedicated KVM Android 15 emulator. Debug/release
lint and the unsigned release build passed; 36 unchanged core tests used cached
results. Five focused regressions passed, including two failures reproduced on
the previous APK with the same test APK. Manual notification-denial, Home,
screen-lock, pause/resume and Finish checks used these same application bytes.
The APK was neither rebuilt nor re-signed for publication.

APK SHA-256: `955c7bb599ce4236013545ba50901cee5403146ecedf4f47e527193e5d91b315`.
The attached verification manifest and checksums identify the source, model,
signature and tested artifact. This release uses local QA provenance; it is not
an artifact from a new GitHub Actions build.

**Installing over Alpha 1:** compatibility with its cloud-generated development
signature is not verified. If Android rejects the update, do not uninstall an old
copy containing important recordings: uninstalling deletes its notes and audio.
For a disposable test installation, remove the old alpha and then install this APK.

**Still an alpha:** quiet words can be missed and varying noise can produce
invented text. Review the result against the original recording. Improved quality
and speed across all seven languages, real-phone audio, background reliability,
battery, heat and stable release signing remain open. Emulator checks do not
establish phone performance.

Useful feedback: Record → leave the app → return and Finish → listen/edit → Copy;
also try search, Rename, larger text and the dark theme. Physical-phone validation
is the next stage after this interface preview.
