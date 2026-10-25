# Agent development rules

- Work from a GitHub issue; split bounded units into sub-issues, not untracked scope.
- Read [CONTRIBUTING](CONTRIBUTING.md), [ARCHITECTURE](ARCHITECTURE.md), relevant
  source/tests and the full issue/comments before editing. Keep repository text,
  comments and GitHub delivery text in English.
- Preserve the independent unofficial application boundary. Do not introduce
  provider/auth/storage/history/graph behavior under a documentation task.
- Preserve the exact source permission set: INTERNET, ACCESS_NETWORK_STATE,
  RECEIVE_BOOT_COMPLETED and WAKE_LOCK (plus the existing AndroidX signature-only
  receiver permission in merged/APK/installed sets). Keep cleartext off and the pinned
  WorkManager component protections/removal markers narrow. Ordinary restoration is dormant;
  only previously enrolled work may activate its exact continuing credential lifetime.
- Add tests for behavior changes. No mandatory TDD, RED commit or test-first order.
- Coverage minimum: **90%** JaCoCo **INSTRUCTION**, compatible JVM + instrumented
  union; never weaken the threshold or handwritten denominator/exclusions.
- CI-first: run quick `bash tools/check.sh` with the external hash-pinned Python
  environment documented in CONTRIBUTING. Owner host policy keeps Android/JVM
  tests, lint, coverage, SDK probes and emulators hosted-only, never on the NAS.
- New/updated Gradle dependencies require a reviewed checksum delta in
  `gradle/verification-metadata.xml` plus the dependency-integrity digest update
  in `tools/build/test_dependency_integrity.py`, in the same PR. This includes
  Dependabot. Follow CONTRIBUTING; never disable strict dependency verification.
- Full-SHA Action pins, accurate version comments and hash-pinned Python requirements
  are mandatory. No secrets in tests or PR workflows; use synthetic/sanitized inputs.
- Keep evidence, receipts and delivery history in GitHub issues/PRs, not repository
  files. Generated logs/reports belong in external scratch or CI artifacts.
- One writer per worktree. Independent full-diff review and green exact-head hosted
  checks precede squash merge; verify applicable exact-merge main workflows and
  artifacts afterward. Read [current protections and recommendations](docs/repository-protection.md);
  do not change repository settings without explicit owner approval.
- Report executed checks separately from source inspection and pending hosted gates.
