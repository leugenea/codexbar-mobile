# Guarded Android release runbook

## One-time setup

Use [Guarded release](../.github/workflows/release.yml) on `main`. No tag/push/PR
trigger signs or publishes. This change writes no keys, settings, tags or Releases.

Keep the existing `release-signing` and `release-publication` environments: required
reviewer `leugenea`, self-review allowed, administrator bypass off, custom branch
policy exactly `main` (type `branch`). The controller checks reviewer presence and
branch policy before scheduling environment jobs; audit reviewer identity and the
UI-only bypass setting separately. Signing has four **step-scoped** secrets:
`ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEY_ALIAS`,
`ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`. Publication has no
signing secrets. Retain an encrypted offline key backup; never send private material
through chat, Git, logs, caches or artifacts.

Set the single non-secret repository variable `RELEASE_CONFIG` to:

```json
{
  "schema": 2,
  "certificateSha256": "c63d395d8ce30ba04bf6dd2f78d1da2badcfa8768b65079a9a508862eb775bbb",
  "signingEnvironment": "release-signing",
  "publicationEnvironment": "release-publication",
  "rcEnabled": true,
  "stableEnabled": false
}
```

The certificate pin is SHA-256 of the owner's public DER certificate, independent
of the supplied key/APK. [The schema](../tools/build/schemas/release-config.schema.json)
rejects missing/unknown fields, wrong types and noncanonical values. Unset/invalid
configuration or a disabled channel denies access before environment jobs. Stable
stays disabled until the owner explicitly flips `stableEnabled`; RC never enables it.

The owner accepts that `remote-state` has a technically **Contents-write** job token
for complete draft-aware Release visibility. Its helper permits GETs only; this is
not a platform-enforced read-only token if compromised. Signing has Contents/Actions
read; only the remote-state job and publisher have Contents write. All operations
share one noncanceling mutex, not a durable/FIFO queue. Out-of-band admin changes
and deleted/distributed history require owner reconciliation.

## Each release

1. Create an immutable tag at the intended green `main` commit:
   `v<VERSION>-rc.<positive N>` for RC or `v<VERSION>` for stable. The source must be
   in main history with successful exact-source main-push Android, repository-policy,
   research-contract and CodeQL runs. Its workflow subtree must match current main.
2. Dispatch Guarded release on **main** with `operation=prepare`, `channel=rc|stable`
   and `tag`; leave `prepare_run_id` empty. No dummy dispatch or JSON update is needed.
3. Check the admission job's **Owner confirmation** summary/log: channel, tag, peeled
   source SHA and versionCode. Then **Approve** the signing environment.
4. Verify the successful prepare run's retained `signed-<run_id>-<attempt>` artifact:
   signed APK, `runtime.cdx.json`, `THIRD_PARTY_NOTICES.txt`, complete legal bundle ZIP,
   `release-manifest.json` and `SHA256SUMS`. Review identities/digests and applicable
   hosted/phone/live evidence; fixture signatures are not production acceptance.
5. Dispatch on **main** with `operation=publish`, the same channel/tag and
   `prepare_run_id=<successful prepare run ID>`. Check its confirmation summary and
   **Approve** publication. It locates that run's retained signed artifact, verifies
   source/channel/counter/certificate/bundle again and publishes those exact six
   assets. There is no rebuild, resign or second versionCode.
   Supply `notes` for publish (required for stable, optional for RC; empty for prepare); review them before approval.
   Notes precede the final integrity marker; publish reruns must supply the same notes (outer whitespace is stripped).
6. Verify the final Release ID, flags and downloaded assets. RC is prerelease and
   not latest; stable is non-prerelease and latest.

Prepare's versionCode is this dedicated workflow's API-verified `run_number`
(1..2100000000); reruns keep it and gaps are legal. Before signing/publication the
controller reads complete draft-aware history and actually downloads/reverifies
accepted RC/stable releases. A new code must exceed every accepted code; empty
history is derived from the complete listing. Retained signed candidates reserve
their tag. Unknown, conflicting, expired unresolved or partial state stops progress.
An exact already-published allocation is a read-only no-op even after Actions
artifact expiry; a different prepare run cannot replace it.

Publication creates a draft, uploads only missing matching assets, downloads each
for readback, then publishes and reads back the final state. Only an owned matching
partial draft can resume. On failure, inspect exact run/attempt/Release/asset IDs
and reconcile explicitly; never blindly retry writes, clobber bytes, move a tag or
rebuild an accepted candidate. Controller/main drift requires a fresh dispatch.

## Revocation and limits

Disable the affected channel or delete `RELEASE_CONFIG` **and cancel in-flight runs /
deny pending approvals**. Variables are evaluated workflow context, not a live REST
revocation read; deletion alone does not revoke already-evaluated runs or undo a
signature/publication.

Approval is the single owner's **confirmation pause, not independent review**.
Authorization is no longer pinned to a specific commit/run in a variable. The owner
checks the tag/commit shown in the run summary at **Approve**. Repository-admin,
GitHub-hosted runner and retained-history trust remain; this is not deletion-proof
archival. Signing still independently verifies the unsigned capsule, certificate,
APK security and unchanged payload through the final binder; secrets stay confined
to signing and private files are removed before upload. The unchanged hosted-only
disposable-key fixture exercises real SDK signing, mock publication/download and
same-emulator install/pull/launch. Pure local tests do not prove those hosted results.
