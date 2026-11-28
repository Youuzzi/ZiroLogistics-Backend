# Tenant MFA reauthentication grant storage

## Purpose

The WMS tenant MFA reauthentication grant is a short-lived, single-use proof that a user recently re-entered their global-account password for a specific tenant membership and security purpose.

It is not an access token, an MFA step-up proof, or a replacement for TOTP. A grant may authorize only the operation named by its purpose and the exact user, tenant, membership, and credential version recorded when it was issued.

## Persistence contract

WMS V32 provides \`wms_tenant_mfa_reauthentication_grants\`. The application stores only a 32-byte cryptographic digest of a high-entropy opaque grant. The raw grant is returned once to the caller and must never be logged or persisted.

The only permitted purposes are:

- \`MFA_FACTOR_ENROLLMENT\`
- \`MFA_FACTOR_REVOCATION\`
- \`MFA_FACTOR_RECOVERY\`

The grant lifetime is at most five minutes. Consumption is a single conditional InnoDB update requiring all identity bindings, purpose, expiry, and unused/unrevoked state to match. Concurrent consumers must have exactly one winner.

Outstanding grants must be revoked after a credential or account security change. Consumption and the protected operation must participate in the same surrounding transaction when used to authorize a state change.

## Security boundaries

- Tenant MFA is bound to the global user identity, but issuance and consumption are additionally bound to the selected active tenant membership.
- The current persisted user credential version must match the authenticated principal.
- The selected tenant and membership must still be active at issuance and consumption.
- A tenant reauthentication grant must never be accepted by Platform Administrator endpoints.
- A reauthentication grant does not satisfy a TOTP step-up requirement.
- MFA remains disabled by default. This storage adapter is not an HTTP endpoint and does not enable MFA.

## Verification

The MySQL 8.4 integration test verifies exact purpose and identity binding, one-time consumption, expiry, revocation, malformed digest rejection, maximum TTL, and concurrent single-winner consumption.
