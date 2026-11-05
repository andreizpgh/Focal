# Install Focal

**[Download Focal-0.1.0-alpha.8.apk](https://github.com/andreizpgh/Focal/releases/download/stt-alpha-8/Focal-0.1.0-alpha.8.apk)**

Android 8.0 or newer and an ARM64 phone. Alpha 8 is an approximately **815 MB**
testing APK, version `0.1.0-alpha.8`, with its speech models included. It works
offline, without an account or extra downloads. The first Ultra request needs
about **705 MB of free private storage** in addition to the installed app;
installation and updates also need temporary space. Later Ultra requests reuse
the verified private model copy.

1. Open the download link on your phone.
2. Open **Focal-0.1.0-alpha.8.apk** from your browser’s downloads or the Files app.
3. If Android asks, allow **Install unknown apps** for that browser or file manager,
   then return to the APK and tap **Install**. You can turn that permission off afterward.

Open Focal, allow microphone access and notifications, and tap **Record**.
For Russian speech, choose **Русский** in the speech-language selector first.
The interface and default speech language are English.

The APK is also available under **Assets** on the
[release page](https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-8).
You don’t need a GitHub account, Android Studio, or a ZIP archive.
The versioned filename identifies this download. Earlier release assets remain available
on their own release pages.

## If you tried the previous download

Alpha 8 is a locally verified, development-signed build. The exact APK passed
137 Android tests on the dedicated emulator; see the [release verification](../releases/stt-alpha-8.json).
Its signing certificate matches Alpha 2–7, so installing over those versions should update it in place.
Compatibility with Alpha 1's cloud-generated certificate remains unverified.

If Android refuses the update, keep the old installation if it contains important
recordings. Uninstalling deletes Focal's private notes and audio; copying text does
not preserve the original recording. For a disposable test installation, remove
the old alpha and install the new APK. Keep important transcripts exported while testing.

## While testing

After finishing a recording of up to three minutes, choose **Recording options →
Transcribe with Ultra**. Ultra selects a language automatically and shows a
separate result for copying. It reads the full original audio, including audio
from excluded passages. Your saved transcript, edits and exclusions remain
intact. Live transcription still uses Whisper and your selected speech language.
Automatic LLM correction is off.

Recording continues when you leave the app or dismiss its task. Force-stopping
Focal or restarting the phone ends recording; manufacturer restrictions can also
stop background apps.

Review cues can miss errors. Quiet speech may be omitted and noise may produce
invented text. Seven-language quality, battery use and long recordings still
need measurements on real phones. See the
[device checklist](device-validation.md) for useful test scenarios.
