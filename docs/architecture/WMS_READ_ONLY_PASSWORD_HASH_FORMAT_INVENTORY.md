# WMS Read-Only Password Hash Format Inventory

**Status:** Prepared for controlled execution; not yet executed against any user database.  
**Date:** 2026-11-16  
**Scope:** Metadata-only aggregate inspection of `users.password_hash`.  
**Safety:** SELECT-only. No email, username, user ID, or raw password-hash value is returned.

## 1. Purpose and release gate

The repository audit established that WMS stores password material in `users.password_hash VARCHAR(255)`, but it did not establish the algorithm used for existing account values.

This procedure is designed to identify the **format-family distribution** of existing values without exposing the values themselves. It is a first-pass inventory, not proof of who created the hashes or which application version produced them.

Do not enable WMS credential login for existing accounts based only on this inventory. Review the results and provenance evidence before choosing a verifier or migration strategy.

## 2. Strict execution rules

1. Obtain explicit authorization from the database owner/operator before inspecting the intended WMS database.
2. Use a read-only database account with SELECT permission only. Do not use an application owner account or a schema migration account.
3. Connect only to the intended WMS database. Never point this procedure at the legacy database.
4. Confirm the selected database name before running the inventory.
5. Do not enable SQL general logging, query-result export, or terminal/session recording that could capture unrelated sensitive data.
6. Do not run `SELECT *`, return individual rows, print prefixes, export hashes, or query email/user identifiers.
7. Do not run UPDATE, INSERT, DELETE, ALTER, CREATE, DROP, or any migration.
8. Share only the aggregate result: format family, row count, and total count. Do not share connection strings or credentials.

If the account cannot be restricted to SELECT-only, stop and arrange an appropriately restricted account first.

## 3. Preflight — verify database identity

Run this read-only statement first:

```sql
SELECT DATABASE() AS connected_database;
```

Confirm the returned database is the intended WMS database for this inspection. The expected logical schema in the current project is `zirowms_v1`, but use the actual approved target name for the environment being inspected.

If the result is NULL, is the legacy database, or is not the intended WMS database, stop.

## 4. Aggregate-only format inventory

Run this statement only after preflight succeeds:

```sql
START TRANSACTION READ ONLY;

WITH classified_hashes AS (
    SELECT
        CASE
            WHEN password_hash IS NULL THEN 'NULL'
            WHEN password_hash = '' THEN 'EMPTY'
            WHEN password_hash LIKE '$argon2id$%' THEN 'ARGON2ID_PHC'
            WHEN password_hash LIKE '$argon2i$%' THEN 'ARGON2I_PHC'
            WHEN password_hash LIKE '$argon2d$%' THEN 'ARGON2D_PHC'
            WHEN password_hash LIKE '$2a$%' THEN 'BCRYPT_2A'
            WHEN password_hash LIKE '$2b$%' THEN 'BCRYPT_2B'
            WHEN password_hash LIKE '$2y$%' THEN 'BCRYPT_2Y'
            WHEN password_hash LIKE '{argon2}%' THEN 'SPRING_ARGON2_PREFIX'
            WHEN password_hash LIKE '{bcrypt}%' THEN 'SPRING_BCRYPT_PREFIX'
            WHEN password_hash LIKE '{pbkdf2}%' THEN 'SPRING_PBKDF2_PREFIX'
            WHEN password_hash LIKE '{scrypt}%' THEN 'SPRING_SCRYPT_PREFIX'
            WHEN password_hash LIKE '$pbkdf2$%' THEN 'PBKDF2_DOLLAR_PREFIX'
            WHEN password_hash LIKE '$scrypt$%' THEN 'SCRYPT_DOLLAR_PREFIX'
            WHEN password_hash LIKE '$5$%' THEN 'SHA256_CRYPT_PREFIX'
            WHEN password_hash LIKE '$6$%' THEN 'SHA512_CRYPT_PREFIX'
            ELSE 'UNKNOWN_OR_UNRECOGNIZED'
        END AS format_family
    FROM users
)
SELECT
    format_family,
    COUNT(*) AS account_count,
    SUM(COUNT(*)) OVER () AS total_account_count
FROM classified_hashes
GROUP BY format_family
ORDER BY format_family;

COMMIT;
```

The query reads only `password_hash` and returns aggregate labels and counts. It does not return the stored hash, email, username, public ID, or primary key.

**Important correction for Spring prefixes:** the `{argon2}` marker is a prefix family only; Spring's DelegatingPasswordEncoder commonly uses an identifier followed by a closing brace and then the encoded value. This inventory intentionally reports the family, not the implementation parameters. The actual stored value remains private.

## 5. Interpretation limits

- `ARGON2ID_PHC` identifies a PHC-format Argon2id prefix. It does not prove the hash was produced by this repository's current encoder parameters.
- `BCRYPT_*` identifies a bcrypt-style prefix. It does not prove whether the WMS application, a legacy application, or an external provisioning process created it.
- Spring-prefixed values identify a DelegatingPasswordEncoder-style marker, but the marker alone does not establish the exact encoder configuration or whether the remainder is valid.
- `UNKNOWN_OR_UNRECOGNIZED` requires controlled follow-up. Do not display the value to diagnose it.
- Counts do not establish account ownership, password quality, whether a user can authenticate, or hash provenance.
- The current WMS Argon2id parameters are m=19 MiB, t=2, p=1, with 16-byte salt and 32-byte output. A prefix match does not prove those parameters were used.

If the result contains more than one family, preserve the distribution as evidence. Do not automatically add fallback verifiers or rewrite hashes.

## 6. Safe result format to report

Share only a table like this:

| Format family | Account count |
|---|---:|
| ARGON2ID_PHC | (count) |
| BCRYPT_2B | (count) |
| UNKNOWN_OR_UNRECOGNIZED | (count) |
| **Total** | **(count)** |

Do not attach SQL result exports, screenshots containing other columns, raw query logs, or copied account rows.

## 7. Follow-up decision

After the aggregate counts are reviewed:

- If all hashes are Argon2id, verify a small, approved set of records through a controlled application-level verification procedure that never prints the hash or credentials; confirm parameters/provisioning provenance before release.
- If bcrypt or another supported family appears, establish its origin and approve a narrowly scoped legacy-verification and rehash-on-success policy before implementation.
- If unknown or malformed values appear, fail closed. Prefer a controlled password reset/re-enrollment workflow rather than speculative algorithm fallback.
- Design WMS-only password provisioning/reset so all newly stored values use the qualified WMS Argon2id encoder.
- Keep legacy `users.password` and WMS `users.password_hash` paths isolated.

## 8. What has not happened

- No connection to `zirowms_v1`, `zirologistics_db`, or any other user-owned database was made.
- No account data, email, username, or hash value was read by this audit.
- No query in this document has been executed against a user database.
- No password hash has been modified.
- No migration or schema change has been created.
