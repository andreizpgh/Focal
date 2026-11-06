# Security

## Reporting a problem

Please report security problems privately, not in a public issue. Use
**Report a vulnerability** on the repository's
[Security tab](https://github.com/andreizpgh/Focal/security). If that option is
not shown, open an issue titled "Security contact" with no details, and you will
be given a private way to send them.

Include the Focal version, the Android version and device, and the steps to
reproduce. Do not include real recordings or transcripts; a synthetic example is
enough.

Only the latest release is supported. Focal releases are testing pre-releases.

## What matters most

Focal keeps voice notes on the phone and has no network access, so the most
important problems are ways for another app or person to:

- read notes, audio or the database outside Focal's private storage;
- make Focal record, or keep recording, without the user's action;
- see note text where it should be hidden, such as on the lock screen.

Problems in the build's supply chain also count: the model and runtime are pinned
by size and SHA-256 in `models/`, and a way around that check is a security issue.

## Secrets

The repository contains no credentials, signing keys, model weights or user data,
and must never contain them. GitHub Actions scans the full history with Gitleaks
on every push and pull request. If you find a secret in the repository or a
release, report it privately as above and do not repeat it anywhere public. An
exposed credential is revoked or rotated by its owner; deleting the latest copy
does not remove it from history.
