# WMS Login Throttle Key Foundation

**Status:** Implementation foundation; not a production login throttle.  
**Scope:** Privacy-preserving account/source bucket identity only.  
**Schema:** No database migration in this slice. Frozen WMS V1 remains unchanged.

## Implemented behavior

- Account identifiers reuse the existing `WmsEmailNormalizer` policy.
- Source identifiers accept numeric IPv4/IPv6 addresses only. Trusted-proxy interpretation is a separate responsibility and is not implemented by this component.
- Both identifiers use HMAC-SHA-256 with domain separation between ACCOUNT and SOURCE buckets.
- The HMAC key must be at least 32 bytes and is supplied to the component by its caller; no secret is committed to the repository.
- Each bucket identity carries a key version. The digest is a lowercase 64-character hex string suitable for a fixed 32-byte database column after decoding.
- Raw email and IP values are not returned from the hashing API.

## Security boundaries and limitations

- This component does not resolve trusted proxies, read servlet headers, choose thresholds, count attempts, persist state, or make allow/deny decisions.
- Do not pass arbitrary `X-Forwarded-For` text to `hashSourceAddress`. Resolve the client address only under an explicit trusted-proxy allow-list, then pass the resulting numeric address.
- The current component represents one active key version per instance. A production key-rotation/overlap procedure is still required before enabling login throttling.
- The HMAC secret must be generated randomly, stored in deployment secret management, and kept separate from JWT signing material and password-hashing configuration.
- Digests must not be logged or used as metrics labels.

## Verification

Unit tests cover normalized email equivalence, IPv6 canonicalization, address validation, minimum key length, bucket domain separation, and key-version identity. No database or user data is accessed.
