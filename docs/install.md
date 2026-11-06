# Install Focal

**[Download Focal-0.2.0.apk](https://github.com/andreizpgh/Focal/releases/download/v0.2.0/Focal-0.2.0.apk)**

Focal is distributed as an APK on its
[release page](https://github.com/andreizpgh/Focal/releases/tag/v0.2.0), under
**Assets**. You don’t need a GitHub account, Android Studio or a ZIP archive.
Earlier versions stay on their own release pages.

## Requirements

- Android 8.0 or newer on an ARM64 phone.
- About 0.7 GB for the download. The speech model is inside the APK; nothing else
  is downloaded, ever.
- About 0.7 GB more of free storage for the model’s working copy, which Focal makes
  once, in the background, on first launch. The copy starts only when there is
  also 200 MB to spare. Installing and updating need temporary space too.
- Room for your recordings: audio takes about 115 MB per hour until you remove it
  from a note.

## Install

1. Open the download link on your phone.
2. Open **Focal-0.2.0.apk** from your browser’s downloads or the Files app.
3. If Android asks, allow **Install unknown apps** for that browser or file
   manager, go back and tap **Install**. You can turn the permission off
   afterwards.

Open Focal and tap **Record**. Android asks for the microphone. On Android 13 and
later it then asks once whether Focal may show notifications, which carry Pause
and Finish. Talk; text appears after each pause. Tap **Finish**, wait for
the last part to be transcribed, then **Copy**.

On first launch the recording screen may say **Preparing speech model** while the
model is copied. Recording works during that time, and the text catches up when
the copy is done.

## Updating from an earlier alpha

Android updates an app in place only when the new APK is signed with the same
key. Alpha 2 to Alpha 9 share one development key, and each release, 0.2.0
included, is checked to carry the same certificate before it is published.

- **Over Alpha 9:** tested on Android emulators. Every note, edit, custom title
  and audio file was kept.
- **Over Alpha 2 to Alpha 8:** same key, and migration tests cover their older
  database formats, but installing over them has not been tried.

What changes on the first launch:

- Your notes are converted to the new format. Text, edits, custom titles and
  audio are kept. Older notes have no word timings, so playback starts at the
  beginning of the paragraph you tap.
- **Restore original** works where the earlier version saved the original text.
  Where it did not, your edits stay but there is no original to restore.
- A note that was still waiting for transcription is finished with the new speech
  model, for the parts that were missing.
- Model files that earlier versions copied to the phone, such as the Whisper
  model, are deleted, which frees space.
- The speech-language setting is gone: the model detects the language itself.

Alpha 1 was built in the cloud with its own development key, and installing
0.2.0 over it has not been checked. If Android refuses the update, copy out any
text you want to keep before you uninstall Alpha 1: uninstalling deletes Focal’s
notes and audio.

## While testing

- Recording continues when you leave the app, lock the phone or dismiss Focal from
  recent apps. Force-stopping Focal or restarting the phone ends the recording;
  everything saved until then is kept, and the note says where it stopped. Some
  manufacturers stop background apps more aggressively.
- A call, another app taking the microphone or nearly full storage pauses the
  recording and says why; tap **Resume** when ready.
- On Android 15 and later, Android may pause a long transcription in the
  background. Open Focal and it continues.
- Quiet speech can be missed, and a very short reply can come out in the wrong
  language. Check important text against the audio.

Phone performance, battery use and long recordings have not been measured yet;
see [testing](testing.md#phone-validation).
