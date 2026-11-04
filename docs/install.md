# Install Focal

**[Download Focal-0.1.0-alpha.6.apk](https://github.com/andreizpgh/Focal/releases/download/ux-alpha-6/Focal-0.1.0-alpha.6.apk)**

Android 8.0 or newer and an ARM64 phone. The speech model is included; no account
or extra download is needed. This is UX Alpha 6, version `0.1.0-alpha.6`, for testing.

1. Open the download link on your phone.
2. Open **Focal-0.1.0-alpha.6.apk** from your browser’s downloads or the Files app.
3. If Android asks, allow **Install unknown apps** for that browser or file manager,
   then return to the APK and tap **Install**. You can turn that permission off afterward.

Open Focal, allow microphone access and notifications, and tap **Record**.
For Russian speech, choose **Русский** in the speech-language selector first.
The interface and default speech language are English.

The APK is also available under **Assets** on the
[release page](https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-6).
You don’t need a GitHub account, Android Studio, or a ZIP archive.
The versioned filename identifies this download. Earlier release assets remain available
on their own release pages.

## If you tried the previous download

UX Alpha 6 is a new locally verified, development-signed build. It passed 108 Android
tests on the dedicated emulator; see the [release verification](../releases/ux-alpha-6.json).
Its signing certificate matches Alpha 2–5, so installing over those versions should update it in place.
Compatibility with Alpha 1's cloud-generated certificate remains unverified.

If Android refuses the update, keep the old installation if it contains important
recordings. Uninstalling deletes Focal's private notes and audio; copying text does
not preserve the original recording. For a disposable test installation, remove
the old alpha and install the new APK. Keep important transcripts exported while testing.

## While testing

Recording continues when you leave the app or dismiss its task. Force-stopping
Focal or restarting the phone ends recording; manufacturer restrictions can also
stop background apps.

Review cues can miss errors. Quiet speech may be omitted and noise may produce
invented text. Seven-language quality, battery use and long recordings still
need measurements on real phones. See the
[device checklist](device-validation.md) for useful test scenarios.
