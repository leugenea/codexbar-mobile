## Issue and scope

Related issue: #<!-- issue number; use closing keywords only for complete acceptance -->

Describe the change and exclusions. Link bounded sub-issues if applicable.

## Verification

- Quick checks run and results:
- Behavior tests added/updated (or why not applicable):
- Exact-head hosted CI links; pending gates explicitly listed:
- Dependency changes: reviewed XML checksum delta and integrity digest update, or N/A:
- Attribution/documentation updates, or N/A:

## Delivery checklist

- [ ] English text; scoped to the linked issue.
- [ ] Coverage remains at least 90% JaCoCo INSTRUCTION over the JVM + instrumented union; no weakened denominator.
- [ ] No secrets in tests or PR workflows; no local emulator execution.
- [ ] Evidence is sanitized: no tokens, passwords, device codes or raw account data in text, logs, screenshots or attachments.
- [ ] Independent full-diff review and exact-head green hosted gates required before squash merge.

Do not report vulnerabilities here; follow [SECURITY](../SECURITY.md).
Keep receipts/history in this PR or its issue, not committed report files.
