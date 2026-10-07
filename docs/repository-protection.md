# Repository protections and recommendations

## Current settings

As of October 7, 2026, the active repository ruleset
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
below are configured required status checks in `protect-main`.

| Workflow file | Exact check context | Enforcement |
| --- | --- | --- |
| `android.yml` | `Build, lint and unit tests (strict dependency verification)` | Covered by required aggregate |
| `android.yml` | `Instrumented tests and coverage (API 36 emulator)` | Covered by required aggregate |
| `android.yml` | `Android CI result` | Required |
| `research-contract.yml` | `Validate research fixtures and schemas` | Required |
| `repository-policy.yml` | `Check repository scripts and supply-chain policy` | Covered by required aggregate |
| `repository-policy.yml` | `Repository policy result` | Required |
| `code-metrics.yml` | `Code erosion (Kotlin complexity)` | Informational |
| `code-metrics.yml` | `Code duplication (jscpd)` | Informational |
| `code-metrics.yml` | `Publish code metric history` | Main-only; not required for PRs |
| `code-metrics.yml` | `Deploy code metric chart` | Main-only; not required for PRs |

Sources: [Android](../.github/workflows/android.yml),
[research](../.github/workflows/research-contract.yml),
[repository policy](../.github/workflows/repository-policy.yml) and
[metrics](../.github/workflows/code-metrics.yml).

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
