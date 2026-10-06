# M1 dependency integrity import

The October 6, 2026 independent audit approved **only the exact observed graph**
from hosted run **37461079663 / attempt 1**, source head
`3bb5133a9ffe99bd62377d51a3813df46d793520`, actual PR checkout
`d7de7bf7d34e63aab9db5ba1fd9998a6fe966116`. It is not approval of a future graph,
source behavior, compatibility or native execution.

[`m1-integrity.json`](m1-integrity.json) is the compact, machine-readable record:
it binds the original discovered XML, hosted receipt/provenance, independent audit
manifest and imported XML to SHA-256; each exact artifact identity/hash maps to its
trust tier, original official source and published checksum source(s). Repository
aliases expand by concatenating the declared prefix and relative path. Strong proof
rows use the artifact's `sha256`; weaker proof rows carry the published SHA-1.

## Immutable inputs and semantic preservation

- Audit manifest SHA-256:
  `f2f1e8ee295614be97737ea88fbe2639d6d2055135b75a544af4bc6c4946e4d7`.
- Original discovery XML SHA-256:
  `a083a574908401043606876d80a00e64b2ef4c1f1441945be30e03ebec6c6998`.
- Original resolved receipt SHA-256:
  `d0390bf6c3d3229b315cb84c3e8660644e9e70997d94b615621f5c662498cae5`.
- Exact sorted identity/hash-set SHA-256:
  `f692e8c2fd402671343ed88c35324d6b95cbe4fe8239e801a19b48fa6c9ab594`.

The import preserves all **364 components / 602 unique artifact identities and
SHA-256 pairs**, with no additional hashes, ignored artifacts, alternate trust,
broad keys or repository bypass. XML origin annotations now distinguish the two
actual evidence tiers, so its file hash differs from the untrusted discovery file.
The semantic digest is the SHA-256 of compact UTF-8 JSON (`ensure_ascii=true`,
separators `,`/`:`) containing lexicographically sorted
`[group,module,version,file,sha256]` rows. Repository tests enforce both the exact
semantic set and annotated-XML file hash.

## What was verified — and what was not

- **373** identities match independently retrieved official-repository published
  SHA-256 sidecars/module declarations.
- **229** identities have the weaker published SHA-1 plus fresh independent
  original-byte SHA-256 comparison. SHA-1 is not collision-resistant proof.
- **824** resolved receipt rows / **210** unique identities match the audited
  graph. Loaded KGP `2.4.20-gradle96` matches its exact approved binary hash.
- **0** checksum mismatches or source failures were reported; the separate audit
  evidence checker passed **10/10** tests.
- **554** artifact signatures were available but **not authenticated**; **48**
  were absent at the official signature endpoint. **0 authenticated signatures**
  are claimed. No independently trusted signer fingerprints were established.

All comparisons rely on TLS and the official repository's publication channel;
they are independent retrievals, not an independent administrative publisher
channel or blanket cryptographic publisher-identity proof. Keep
`verify-metadata=true` and, honestly, `verify-signatures=false`.

The detailed audit manifest, retrieval bodies, HTTP records, source mappings,
receipt-row comparisons and evidence-checker output remain under
`/opt/data/tmp/codexbar-m1-integrity-audit` in the parent's retained evidence.
They are not copied into the repository. The compact record preserves their exact
manifest hash for correlation; the original diagnostic files remain in the
`m1-toolchain-37461079663-1` hosted artifact (subject to its retention).

## Follow-up graph deltas

The bootstrap marker and all discovery routes are retired. The October 6, 2026
[owner-approved cache amendment](m1.md#owner-approved-setup-actions-and-dependency-cache-amendment)
supersedes the final fresh-home/no-cache policy, not historical cold receipts.
Every normal PR/push/manual run uses committed strict verification and inherits
its separate job-level Gradle home with setup-gradle's basic dependency/wrapper
cache; no metadata generation is allowed. Every actual gate retains
`--no-build-cache --no-configuration-cache --rerun-tasks` to force real task
execution, not reuse of cached outputs or reports. A missing checksum is a real
failure: retain exact candidate/run/attempt/logs, isolate the bounded new
identity/hash delta, independently compare primary published evidence/original
bytes and obtain review before amending metadata and this record. Never add an
alternate checksum just to make resolution succeed or use discovery as acceptance.
Replay the unchanged full strict build/lint/JVM/native graph on the amended candidate.

This import is not a passing replay. Hosted strict execution, real Activity smoke
and an independent review of the complete base-to-head candidate remain required.
Kotlin 2.4.20's official fully supported maxima (Gradle 9.7.0 / AGP 9.3.1) still do
not include the owner-approved 9.8.0 / 9.4.1 pair; observed CI is required and does
not rewrite that caveat. M0 historical documents/research/workflow are unchanged.
