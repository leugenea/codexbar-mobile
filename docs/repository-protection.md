# Repository protection recommendations

**Unconfigured / owner decision.** No rulesets or main branch protection are
currently configured. This document recommends settings; it neither enforces them
nor authorizes an automated settings change. The owner must approve/configure them.

## Exact check contexts

Names below are job display names, not workflow titles. All declared PR gates run
even for documentation-only changes; metrics are informational. The aggregate
results reject failed or skipped required producer jobs.

| Workflow file | Exact check context | Recommendation |
| --- | --- | --- |
| `android.yml` | `Build, lint and unit tests (strict dependency verification)` | Required |
| `android.yml` | `Instrumented tests and coverage (API 36 emulator)` | Required |
| `android.yml` | `Android CI result` | Required |
| `research-contract.yml` | `Validate research fixtures and schemas` | Required |
| `repository-policy.yml` | `Check repository scripts and supply-chain policy` | Required |
| `repository-policy.yml` | `Repository policy result` | Required |
| `code-metrics.yml` | `Code erosion (Kotlin complexity)` | Informational |
| `code-metrics.yml` | `Code duplication (jscpd)` | Informational |
| `code-metrics.yml` | `Publish code metric history` | Main-only; not required for PRs |
| `code-metrics.yml` | `Deploy code metric chart` | Main-only; not required for PRs |

Sources: [Android](../.github/workflows/android.yml),
[research](../.github/workflows/research-contract.yml),
[repository policy](../.github/workflows/repository-policy.yml) and
[metrics](../.github/workflows/code-metrics.yml).

## Suggested main rules

| Setting | Recommendation, pending owner approval |
| --- | --- |
| Pull requests | Require PRs; no direct delivery to main |
| Review | At least one independent approval; dismiss stale approvals and require approval of the latest head |
| Discussions | Resolve review conversations before merge |
| Required checks | Require the six Required contexts above, on the exact reviewed head; require up-to-date branches |
| Merge | Squash merge and linear history |
| Destructive changes | Block force pushes and branch deletion |
| Bypass | No routine administrator/bot bypass; exceptions need explicit owner decision |

Protection does not replace independent complete-candidate review or exact-merge
main workflow/artifact verification. Renaming a job requires updating both this
mapping and any owner-configured required context. [CONTRIBUTING](../CONTRIBUTING.md)
contains the delivery and dependency-review procedure.
