# Focal 0.1.0 · Alpha 9

Ultra now transcribes new recordings automatically into the note. Tap Record,
then Finish, review the result and copy. Language detection is automatic;
recordings can exceed three minutes. Original audio and manual edits are kept.

During recording, the first block waits for 35 seconds of saved audio. Pause or
Finish makes the remaining audio eligible immediately; model preparation and
recognition still take time. This is local block-based transcription, not
instant streaming. Existing Whisper notes retain their engine and saved language.
Automatic LLM correction remains off.

The primary service retained earlier Ultra scores on 14 short DEV speech cases:
normal word errors 25 → 12 / 210 and quiet 31 → 15 / 210 against historical
same-PCM Whisper Base. Quiet Portuguese remains worse, 5 → 8 / 21. Four controls
emitted no words; a repeated Russian fixture completed at 276.8 seconds.
These are integration checks on previously used data, not new held-out or phone
performance results. The earlier 42-clip model comparison remains separate.

Verified locally: all 146 Android tests, 36 core and 18 app JVM tests, both lints,
debug/test and minified builds. Independent Sol, Terra and Astra reviews covered
code, quality, packaging and actual QA. Manual debug checks and a separate
non-debuggable R8 primary-speech smoke passed; original data survived updates.
Heavy checks ran sequentially with CPU150%, RAM4GiB and no swap or model downloads.

Download **Focal-0.1.0-alpha.9.apk** below. Android 8.0+, ARM64, approximately
816 MB including offline models. The development signing certificate matches
Alpha 2–8. First Ultra use needs about 705 MB of additional private storage;
installation and updates need temporary space too. Earlier assets are unchanged.

This is a testing release. Pixel 10 microphone quality, natural mixed-language
terms, latency, memory, battery, thermals and background reliability still need
owner-present phone validation. Review important text. There is no Internet
permission, account or cloud service. Exact source, APK hashes and measured
scope are in the attached build provenance.
