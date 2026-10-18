# Repository protections and recommendations

## Current settings

As of October 10, 2026, the active repository ruleset
[`protect-main`](https://github.com/leugenea/codexbar-mobile/rules/24633110)
targets the default branch (`main`), with no excluded refs or bypass actors.

| Setting | Configured state |
| --- | --- |
| Destructive changes | Branch deletion and non-fast-forward updates blocked |
| History | Linear history required |
| Pull requests | Required; zero required approving reviews |
| Review conversations | Resolution required |
| Unattributed Copilot pull requests | Additional-approval flag enabled; no effect while the ruleset requires zero approvals |
| Additional review requirements | Stale approvals are not dismissed; code-owner review and approval of the latest push are not required; no required reviewers configured |
| Allowed merge methods | `merge`, `squash`, `rebase` in the PR rule; the separate linear-history rule still prevents merge commits on `main` |
| Required status checks | `Android CI result`, `Repository policy result` and `Validate research fixtures and schemas`, bound to the GitHub Actions integration; strict/up-to-date policy enabled |
| Code scanning | `code_scanning` rule: CodeQL; alerts threshold `errors`, security alerts threshold `high_or_higher`; configured October 10, 2026 |
| New branch creation | The status-check rule is not exempted on creation |

The strict status-check policy requires PR branches to be up to date with `main`
before merge. The delivery procedure in [CONTRIBUTING](../CONTRIBUTING.md) also
requires all applicable exact-head hosted gates to pass before independent read-only
full-diff review; independent approval remains procedural, not a configured approval
requirement. This document does not authorize a settings change; the owner must
approve any configuration update.

## Check contexts and enforcement

Names below are job display names, not workflow titles. All declared PR gates run
even for documentation-only changes; metrics are informational. The aggregate
results reject failed or skipped required producer jobs. The **Required** entries
below are configured required status checks in `protect-main`; CodeQL is enforced
separately by its `code_scanning` rule.

| Workflow | Exact check context | Enforcement |
| --- | --- | --- |
| `android.yml` | `Build, lint and unit tests (strict dependency verification)` | Covered by required aggregate |
| `android.yml` | `Instrumented tests and coverage (API 36 emulator)` | Covered by required aggregate |
| `android.yml` | `Android CI result` | Required |
| `research-contract.yml` | `Validate research fixtures and schemas` | Required |
| `repository-policy.yml` | `Check repository scripts and supply-chain policy` | Covered by required aggregate |
| `repository-policy.yml` | `Repository policy result` | Required |
| `codeql.yml` | `Analyze (actions)` | Code-scanning rule; not a required status check |
| `codeql.yml` | `Analyze (java-kotlin)` | Code-scanning rule; not a required status check |
| `codeql.yml` | `Analyze (python)` | Code-scanning rule; not a required status check |
| `code-metrics.yml` | `Code erosion (Kotlin complexity)` | Informational |
| `code-metrics.yml` | `Code duplication (jscpd)` | Informational |
| `code-metrics.yml` | `Publish code metric history` | Main-only; not required for PRs |
| `code-metrics.yml` | `Deploy code metric chart` | Main-only; not required for PRs |
| `release.yml` | `Admit exact release authority` | Manual default-off; not required for PRs |
| `release.yml` | `Inspect complete official release history` | Manual default-off; not required for PRs |
| `release.yml` | `Build accepted unsigned release` | Manual default-off; not required for PRs |
| `release.yml` | `Sign accepted release candidate` | Manual default-off; not required for PRs |
| `release.yml` | `Publish accepted release candidate` | Manual default-off; not required for PRs |

Sources: [Android](../.github/workflows/android.yml),
[research](../.github/workflows/research-contract.yml),
[repository policy](../.github/workflows/repository-policy.yml) and
[metrics](../.github/workflows/code-metrics.yml).

The checked-in [advanced CodeQL workflow](../.github/workflows/codeql.yml) preserves
`actions`, `java-kotlin` and `python`, default queries and the remote threat model,
with PR/main/manual/weekly events. Its Java/Kotlin manual build compiles debug and
release application sources plus debug JVM/native test sources; it rejects skipped
or reused compiler tasks. Successful analysis and extracted-file coverage require
actual hosted evidence, not a source claim. Fork PRs use credential-free checkout,
no secrets and GitHub's unprivileged pull-request code-scanning upload behavior.
Only analysis jobs request `security-events: write`; all checkouts use contents read.

On October 14, 2026, the owner explicitly approved parent disabling default setup
before independent review, once the executable replacement was published in
[PR #151](https://github.com/leugenea/codexbar-mobile/pull/151). The parent disabled
default setup and read back `not-configured` with unchanged branch rules. Source
authorship performed no settings writes. This migration permits advanced CodeQL CI;
it does not establish successful analysis or independent source approval.

The delivery sequence is quick checks, publication, all applicable exact-head hosted
checks green, independent read-only complete-source review, squash merge and
exact-merge main workflow/artifact verification. All three CodeQL analyses must pass
with the other applicable checks before review. Avoid indefinite competing Java
analyses. Retain the separate CodeQL `errors` / `high_or_higher` rule and every
required status context. This approval does not authorize other repository changes.
The historical default-setup [first run on October 10, 2026](https://github.com/leugenea/codexbar-mobile/actions/runs/38085283380)
scanned 69 of 150 Kotlin files without test extraction; it does not establish coverage
for the replacement workflow.

## Inactive release trust proposal

The [guarded release workflow](../.github/workflows/release.yml) adds no required PR
context or repository-setting change. Its exact main-only manual admission and
channel-specific activation must succeed before protected environment jobs can be
scheduled. Signing and publication environments, reviewers/ref restrictions,
certificate/key custody, exact source/run authorization and initial history bootstrap
remain owner decisions in issue 148. None are configured here.

The `remote-state` job deliberately requests Contents write for push-capable draft
visibility, although its controller helper performs GETs only. This is a real token
capability, not a platform-enforced read-only token. The owner must explicitly accept
or reject it before activation. `publish` is a distinct protected-environment writer;
`sign` receives only Contents/Actions read and four step-scoped signing secrets.
All other workflows retain existing permission/PR-no-secrets policy. Missing or
uninspectable environments fail public preflight rather than being auto-created.
See the [runbook](releasing.md) for revocation/cancellation limits, owner-local custody,
workflow-tree drift denial and immutable/partial-state recovery.

## Recommended changes and retained safeguards

| Setting | Recommendation, pending owner approval where a change is needed |
| --- | --- |
| Pull requests | Retain required PRs; no direct delivery to main |
| Review | Require at least one independent approval; dismiss stale approvals and require approval of the latest head |
| Discussions | Retain required review-conversation resolution |
| Metrics | Keep code-metrics jobs informational, not required status checks |
| Merge | Restrict allowed methods to squash, consistent with the delivery procedure and required linear history |
| Destructive changes | Retain blocked non-fast-forward updates and branch deletion |
| Bypass | Retain no bypass actors; exceptions need explicit owner decision |

Protection does not replace independent complete-candidate review or exact-merge
main workflow/artifact verification. Renaming a job requires updating both this
mapping and any owner-configured required context. [CONTRIBUTING](../CONTRIBUTING.md)
contains the delivery and dependency-review procedure.
