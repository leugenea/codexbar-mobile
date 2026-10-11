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
requires independent review and green exact-head hosted gates; independent
approval remains procedural, not a configured approval requirement. This document
does not authorize a settings change; the owner must approve any configuration
update.

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
| CodeQL default setup (GitHub-managed dynamic workflow) | `Analyze (actions)` | Code-scanning rule; not a required status check |
| CodeQL default setup (GitHub-managed dynamic workflow) | `Analyze (java-kotlin)` | Code-scanning rule; not a required status check |
| CodeQL default setup (GitHub-managed dynamic workflow) | `Analyze (python)` | Code-scanning rule; not a required status check |
| `code-metrics.yml` | `Code erosion (Kotlin complexity)` | Informational |
| `code-metrics.yml` | `Code duplication (jscpd)` | Informational |
| `code-metrics.yml` | `Publish code metric history` | Main-only; not required for PRs |
| `code-metrics.yml` | `Deploy code metric chart` | Main-only; not required for PRs |

Sources: [Android](../.github/workflows/android.yml),
[research](../.github/workflows/research-contract.yml),
[repository policy](../.github/workflows/repository-policy.yml) and
[metrics](../.github/workflows/code-metrics.yml).

Owner-configured CodeQL default setup analyzes `actions`, `java-kotlin` and
`python`, with the `default` query suite, `remote` threat model, weekly schedule
and standard GitHub-hosted runners. GitHub manages the dynamic workflow; no
CodeQL workflow file is checked into this repository. Java/Kotlin autobuild runs
`assemble`, so test sources are not extracted. The
[first run on October 10, 2026](https://github.com/leugenea/codexbar-mobile/actions/runs/38085283380)
scanned 69 of 150 Kotlin files; this is not whole-source or test-source coverage.

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
