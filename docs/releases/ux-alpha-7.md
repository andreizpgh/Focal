Focal Alpha 7 adds an optional way to check background sound while keeping your original recording.

- From a finished recording's options, choose **Check background sound**. Listen to a suggested range, then choose **Keep text** or **Exclude text**. Nothing is removed automatically.
- Excluded text stays in the timeline with **View or restore text**. **Restore text** returns it to Copy and Share. Original audio and saved edits remain on the device.
- The first library date no longer repeats, Settings groups related controls, and page transitions use a smaller, smoother motion. New recordings remain visible without disrupting manual reading farther down the list.

Download **Focal-0.1.0-alpha.7.apk** below (version `0.1.0-alpha.7`, code 7). This development-signed testing APK uses the same certificate as Alpha 2–6. Both local models are included; no account or runtime download is needed.

Verified locally: **129 Android tests, no failures or skips**, including 10 required real-model checks; 36 core and six app JVM tests, both lints, and debug/test/unsigned R8 builds. Independent source, actual UI-frame and final artifact reviews completed. [Exact build identity](https://github.com/andreizpgh/Focal/blob/ux-alpha-7/releases/ux-alpha-7.json).

Background-sound checking covers finished recordings up to 15 minutes. It can miss noise or propose a quiet spoken range: listen before deciding. The existing Whisper recognizer is unchanged. This release does **not** automatically correct grammar or technical terms, and does not establish an accuracy/speed improvement in all seven languages. Alternative ASR and LLM candidates failed their acceptance screens. Physical Pixel 10 quality, speed, heat, battery and long background-recording checks remain open.
