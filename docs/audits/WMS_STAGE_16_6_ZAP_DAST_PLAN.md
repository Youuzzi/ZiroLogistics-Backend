# Stage 16.6 — ZAP DAST Baseline (initial perimeter scope)

**Status:** PROPOSED / workflow prepared; scan not yet executed  
**Workflow:** `.github/workflows/wms-dast-baseline.yml`  
**Target:** Disposable local WMS Compose runtime in GitHub Actions  
**Scanner:** ZAP Baseline Action `v0.15.0`

## 1. Why this is a baseline only

ZAP Baseline runs spidering and passive analysis; it does not perform active attack requests. This is suitable as a cautious first DAST step, but it does not replace an authenticated API scan or a full active scan. See the [official ZAP Baseline documentation](https://www.zaproxy.org/docs/docker/baseline-scan/) and [official GitHub Action README](https://github.com/zaproxy/action-baseline).

## 2. Disposable environment safety

The workflow:

- creates unique random database credentials for each workflow attempt;
- starts MySQL and the WMS backend with a unique Compose project name;
- uses the Compose project's isolated named volume, never a developer's local DB volume;
- explicitly keeps JWT and anonymous auth endpoints disabled;
- verifies the operational warehouse route returns HTTP 403 before scanning;
- tears down containers and volumes in an `if: always()` cleanup step;
- removes the generated `.env.dast` file.

It does not connect to `zirowms_v1`, `zirologistics_db`, or any external production system.

## 3. Initial scan boundary

The first run scans the protected warehouse route while JWT is disabled. The expected HTTP 403 is an explicit fail-closed smoke check. The ZAP scan therefore measures the unauthenticated perimeter response and passive HTTP metadata only.

**It does not prove that authenticated warehouse, inventory, picking, shipping, or other business routes are secure.** The route returns deny-by-default before business services execute. A later authenticated DAST phase would require disposable tenant/user fixtures, ephemeral RSA key material, a controlled token-issuance flow, and an OpenAPI/context-aware scan plan. That is not included in this first pass.

## 4. Alert handling

The initial workflow preserves the ZAP report artifact and does not automatically fail the workflow for every warning. No alert is silently ignored or allowlisted at this stage. Findings must be reviewed, classified, and recorded before converting any specific alert into a quality gate. Scanner warnings are not automatically confirmed vulnerabilities.

## 5. Execution

Run the workflow manually from GitHub Actions after this workflow is merged to `main`:

- Workflow: **WMS DAST Baseline**
- Input: none
- Expected pre-scan boundary: HTTP 403 for the protected route with JWT disabled
- Expected artifact: `wms-zap-baseline`

If startup fails, inspect the captured Compose logs before interpreting the result as a DAST finding.

## 6. WP16.6 disposition

**NOT VERIFIED until the workflow has actually completed and its report artifact has been reviewed.** The presence of workflow code is not DAST evidence.
