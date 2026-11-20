# WMS QA Workspace

This directory contains QA artifacts that are separate from executable Java tests.

- `strategy/`: quality strategy, scope, risk model, and release gates.
- `test-plans/`: focused test plans and traceability to requirements/findings.
- `security/`: security verification plans, threat scenarios, and assessment evidence.
- `performance/`: k6 scenarios, workload assumptions, and performance results.
- `evidence/`: dated, reproducible CI/tool outputs and audit summaries.

Automated Java tests remain under `src/test/java`. Do not move executable tests into this directory.

## Evidence rules

1. Label every finding as VERIFIED, PARTIALLY VERIFIED, UNVERIFIED, INFERRED, or PROPOSED.
2. Record commit SHA, tool/version, command or workflow, environment, result, and timestamp for reproducibility.
3. Use synthetic data and disposable infrastructure only. Never include credentials, private keys, personal data, or production database exports.
4. A skipped test is not a pass. Record why it was skipped and whether the skipped coverage is required for the gate.
5. Do not claim a security or performance control is verified solely because a tool ran without errors.
6. Keep raw outputs only when they are safe to publish and useful; summarize large or sensitive reports with sanitized evidence.

See [Stage 16 QA & Security Baseline](../docs/audits/WMS_STAGE_16_QA_SECURITY_BASELINE.md) for the initial inventory and staged plan.
