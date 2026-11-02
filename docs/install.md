# Install Focal

**[Download Focal.apk](https://github.com/andreizpgh/Focal/releases/download/v0.1.0-alpha.1/Focal.apk)**

Android 8.0 or newer and an ARM64 phone. The speech model is included; no account
or extra download is needed. This is an early alpha for testing.

1. Open the download link on your phone.
2. Open **Focal.apk** from your browser’s downloads or the Files app.
3. If Android asks, allow **Install unknown apps** for that browser or file manager,
   then return to the APK and tap **Install**. You can turn that permission off afterward.

Open Focal, allow microphone access and notifications, and tap **Record a note**.
For Russian speech, choose **Settings → Speech language → Русский** first.
The interface and default speech language are English.

The APK is also available under **Assets** on the
[release page](https://github.com/andreizpgh/Focal/releases/tag/v0.1.0-alpha.1).
You don’t need a GitHub account, Android Studio, or a ZIP archive.

## If you tried the previous download

Alpha 1 contains the exact APK from the successful
[Android build](https://github.com/andreizpgh/Focal/actions/runs/41421916015).
If you installed that file, you already have this version.

Keep important transcripts exported while testing. If Android refuses a future
update because its signing certificate differs, export your text before removing
the old app. Uninstalling deletes Focal’s private notes and recordings.

## While testing

Recording continues when you leave the app or dismiss its task. Force-stopping
Focal or restarting the phone ends recording; manufacturer restrictions can also
stop background apps.

Review cues can miss errors. Russian recognition quality, battery use, and long
recordings still need measurements on real phones. See the
[device checklist](device-validation.md) for useful test scenarios.
