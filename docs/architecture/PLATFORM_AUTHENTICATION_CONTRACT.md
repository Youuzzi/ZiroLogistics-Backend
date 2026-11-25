# Platform Authentication Contract

**Status:** Design contract for Stage 16 platform-authentication implementation. This document does not claim that platform authentication, MFA, or platform HTTP endpoints are implemented.

## Purpose and boundaries

The platform control plane authenticates SaaS operators independently of tenant users. A platform operator must never authenticate through tenant credential endpoints, tenant membership selection, or tenant permissions.

- Platform credentials resolve only against `platform_operators`.
- Operator status must be `ACTIVE`; suspended, disabled, invited, or unknown operators cannot authenticate.
- Platform authentication must use a separate token profile, audience, purpose, decoder, and security filter chain from WMS tenant access.
- Platform permissions are resolved from persisted platform role/permission assignments. Tokens must not carry a stale, authoritative permission list.
- A tenant token must be rejected on platform routes and a platform token must be rejected on tenant routes.
- Platform tokens cannot imply tenant membership or warehouse transaction access.
- Credential-version changes invalidate outstanding tokens/challenges according to the revocation contract.
- Privileged platform permissions require completed MFA. Authentication without MFA may only reach a narrowly scoped enrollment/challenge flow, not privileged APIs.

## Credential and token requirements

Use the existing supported password hashing implementation and configured strength policy; never store or log raw passwords. Normalize email consistently before unique lookup. Return generic authentication failures for unknown email, wrong password, inactive operator, and invalid credential state to reduce account enumeration. Apply the existing login-throttle abstraction or a platform-specific policy without sharing tenant identity keys or bypassing throttling.

The platform access token profile must define and validate:
- issuer and signing key identifier;
- a dedicated platform audience and explicit token purpose (for example `platform_access`);
- subject equal to the persisted platform operator public ID;
- issued-at, not-before, expiry, and unique token ID;
- credential version, so credential/lifecycle changes can invalidate tokens;
- MFA authentication state/time, with authorization requiring recent/valid MFA for privileged permissions.

No tenant membership ID (`mid`) is present in platform tokens. No tenant access audience or tenant token purpose is accepted.

## HTTP boundary

Use an explicitly ordered `/api/platform/v1/**` security chain before the existing tenant catch-all. Platform endpoints must be deny-by-default, stateless, bearer-token-only, and protected against accidental CSRF/session auth. Public routes, if any, are enumerated explicitly and limited to login/MFA challenges. Existing `/api/wms/v1/**` tenant security behavior must remain unchanged.

## MFA readiness and enrollment

MFA secret material must be encrypted at rest using the approved key-management abstraction, with key version stored. Enrollment must require an authenticated platform operator, proof of possession of the pending secret, and an auditable activation event. Recovery codes must be generated once, stored as hashes, single-use, and invalidated/replaced under a controlled reset. Do not permit privileged access merely because a secret row exists; require verified enrollment and a successful MFA authentication marker.

## Tests required before enabling endpoints

1. Platform token validator accepts only the platform audience, purpose, signing key ID, and valid temporal claims.
2. Tenant selection/access tokens are rejected by the platform decoder; platform tokens are rejected by tenant decoders.
3. Inactive operator states reject authentication without distinguishable public responses.
4. Password verification uses dummy verification for unknown users to reduce timing differences.
5. Credential version change invalidates the prior platform token.
6. Throttling applies to repeated failed platform credential attempts and does not reveal whether an operator exists.
7. Platform route chain is selected before tenant catch-all; all unlisted platform routes deny.
8. Privileged platform permissions require verified MFA; enrollment and recovery cannot grant privilege on their own.
9. No authentication response, log, or error leaks password, MFA secret, recovery code, or bearer token.

## Implementation sequence

- **Slice 2A:** platform operator persistence repository and safe credential lookup; migration V29 is the schema foundation.
- **Slice 2B:** password authentication, throttling, generic error behavior, credential-version lifecycle.
- **Slice 2C:** platform JWT profile/decoder/issuer and cross-audience rejection tests.
- **Slice 3:** ordered platform security chain and deny-by-default routes.
- **Slice 4:** authenticated platform identity replaces caller-supplied operator labels in tenant lifecycle actions.
- **Slice 5:** scoped, time-limited support grants; separately designed and audited.
- **Slice 6:** seed only role/permission grants for implemented endpoints, after permission review.

## Safety gate

Do not seed or create a first platform operator, provision a tenant, or generate bootstrap credentials until the user explicitly approves the bootstrap procedure. Schema and runtime implementation can proceed without those actions.


## Implementation status — credential foundation

The first credential-authentication application slice is implemented without enabling HTTP routes or issuing tokens:

- Platform operator email normalization is isolated from tenant/WMS identity normalization.
- Credential lookup is restricted to `platform_operators`.
- Only `ACTIVE` operators with verified email and a matching password can proceed.
- Unknown email and malformed stored password hashes perform dummy password verification and return the same generic authentication failure.
- A platform-specific throttling port is required by the use case; no tenant throttle key or storage namespace is reused.
- Successful authentication metadata is written only through a conditional database update that rechecks operator status, email verification, and credential version.
- The use case is deliberately not auto-wired as an HTTP service. A platform-specific throttle adapter, token issuer/decoder, ordered security chain, and MFA enforcement remain prerequisites before any route is exposed.
- Unit tests cover successful authentication, unknown operators, inactive operators, and credential-version races; the MySQL integration test verifies the conditional timestamp update.

This slice does not create operators, grant roles, issue platform tokens, or expose login endpoints.


## Implementation status — isolated platform JWT foundation

The platform JWT issuer and decoder are implemented as a separate, disabled-by-default profile:

- Platform signing uses its own RSA key pair, key identifier, issuer, and audience configuration. It never reads or reuses the tenant WMS JWT signing material.
- The platform decoder validates issuer/time, exact platform audience, `platform_access` purpose, key identifier, operator subject, non-negative integral credential version, and MFA claims.
- Issued access tokens are short-lived (10 minutes), carry the operator public ID and credential version, and contain no tenant membership, tenant ID, role, or permission claims.
- The issuer refuses to sign unless a successful MFA timestamp is present, not in the future, and no older than five minutes.
- Cross-profile tests prove a tenant access token is rejected by the platform decoder; missing MFA, wrong key ID, and invalid credential-version claims are rejected.
- Platform JWT remains disabled unless explicitly enabled with distinct deployment secrets. No platform HTTP route or security chain is enabled by this slice, and no operator/bootstrap credential is created.

The platform decoder is not yet the final revocation enforcement point: the upcoming platform authentication converter/security chain must re-check the operator's current status and credential version against persistence on authenticated requests.


## Implementation status — request identity and route isolation

The platform request identity and route-boundary slices are implemented on the current Stage 16 mainline PR sequence:

- The platform JWT authentication converter resolves the token subject against `platform_operators` for every authenticated request.
- Authentication is rejected if the operator is missing, not ACTIVE, not email-verified, or the current credential version differs from the token's `cv` claim.
- The converter requires a non-negative integral credential version and a valid, sufficiently recent MFA timestamp encoded as JWT NumericDate seconds.
- The resulting principal contains only the platform operator public ID. No tenant identifier or membership is attached.
- No platform authorities are granted by default. Platform permission loading must be explicitly added with reviewed grants and endpoint policy.
- A dedicated `/api/platform/v1/**` security filter chain is ordered before the tenant WMS catch-all and uses only the platform decoder and converter.
- Every platform route is authenticated by default; there are no public platform routes in this slice. The chain is disabled unless platform JWT is explicitly enabled with separately provisioned key material.
- MockMvc coverage checks that platform routes require bearer authentication and that tenant/unmatched routes do not become public through the platform matcher.

These slices do not yet add login/MFA endpoints, role grants, operator lifecycle endpoints, or bootstrap credentials.
