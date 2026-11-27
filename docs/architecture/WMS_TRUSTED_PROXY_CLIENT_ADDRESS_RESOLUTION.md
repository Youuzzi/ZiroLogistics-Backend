# WMS Trusted Proxy Client Address Resolution

**Status:** Implemented and wired into the WMS credential-authentication throttle flow. `WmsCredentialAuthenticationConfiguration` composes the resolver, and the authentication controller supplies the socket peer and forwarded-for metadata.  
**Scope:** Resolve a numeric source address safely before HMAC bucket hashing.  
**Schema:** No database migration. Frozen WMS V1 remains unchanged.

## Trust model

- The TCP socket peer is the starting point and is always parsed as a numeric IPv4 or IPv6 literal.
- Forwarding metadata is ignored unless the socket peer belongs to an explicitly configured trusted proxy CIDR.
- When the peer is trusted, the resolver parses the comma-separated `X-Forwarded-For` chain and walks it from right to left.
- The walk stops at the first untrusted hop. This prevents an arbitrary leftmost value supplied by a client from overriding the address observed by a trusted proxy.
- Invalid, empty, oversized, or overlong forwarding chains fall back to the socket peer address.
- Trusted proxy CIDRs must be numeric CIDR literals. Hostnames and implicit trust-all defaults are rejected.

## Operational requirements

- Configure only reverse proxies controlled by the deployment. Do not trust broad private ranges merely because they are private.
- The edge proxy must overwrite or safely append forwarding metadata according to a documented deployment policy.
- The resolver accepts the raw header only as input; callers must pass the resolved numeric address to `WmsThrottleKeyHasher.hashSourceAddress`.
- The resolver is called by `WmsLoginThrottleService` before source-address HMAC hashing; the credential-authentication use case supplies the socket peer and forwarded-for header.
- Proxy CIDRs, header policy, and maximum hop count must be configured and reviewed against the actual ingress topology before production enablement.

## Defensive bounds

- Maximum header length: 2,048 characters.
- Maximum forwarded hops: 16.
- Invalid forwarding metadata falls back to the socket peer, rather than trusting malformed client-supplied values.
- Both IPv4 and IPv6 CIDR matching are supported.

## Verification scope

Unit tests cover untrusted direct peers, trusted proxy resolution, right-to-left chain traversal, spoofed values beyond an untrusted hop, malformed and oversized chains, IPv6 CIDRs, invalid configuration, and invalid socket peers.

This document covers the resolver boundary only. Shared throttle persistence and credential-authentication integration are documented separately; this resolver does not own threshold policy, persistence, or allow/deny decisions.
