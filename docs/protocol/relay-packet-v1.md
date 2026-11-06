# Relay packet v1

Status: protocol framing draft, implemented as a Kotlin codec; not yet connected to a transport or an encryption engine.

## Purpose

Carry an already end-to-end-encrypted message across BLE, Wi-Fi Direct, or an Internet relay without exposing message text or sender identity to intermediate nodes. The codec is deliberately transport-neutral.

## Binary wire format

All integers use network byte order (big-endian).

| Field | Size | Meaning |
|---|---:|---|
| Magic | 4 bytes | ASCII \`MCHT\` |
| Version | 1 byte | \`1\` |
| Packet ID | 16 bytes | Random unique identifier for deduplication |
| Destination token | 16 bytes | Opaque routing token; never a username or public identity key |
| Expiry | 8 bytes | Unix time in seconds |
| Remaining hops | 1 byte | Mutable forwarding budget, 0–8 |
| Payload length | 4 bytes | Unsigned-by-validation length, 16–65,536 |
| Sealed payload | variable | E2EE ciphertext produced by a future crypto module |

The fixed header is 50 bytes. The maximum encoded packet is 65,586 bytes. BLE transports will need bounded fragmentation/reassembly; this codec does not implement that layer.

## End-to-end authenticated data

Before encryption, the E2EE engine must authenticate this exact associated-data byte sequence. With MLS (RFC 9420), supply these bytes as the `FramedContent.authenticated_data` input; RFC 9420 includes that field in the authenticated/encrypted message construction. Do not wrap MLS in a second, custom AEAD layer. If the selected MLS binding cannot set this field, put the same immutable values inside the authenticated application payload and verify them after decryption:

1. ASCII \`MESHCHAT-RELAY-AAD\`
2. Version byte \`0x01\`
3. Packet ID (16 bytes)
4. Destination token (16 bytes)
5. Expiry (8-byte big-endian Unix timestamp)

The remaining-hop counter is intentionally excluded so a relay can decrement it without the recipient's key. A receiver must reject a packet if AEAD verification fails.

## Relay rules

- A relay may read the routing token, packet ID, expiry and hop budget. These fields are metadata, not secret.
- A relay must never receive plaintext, private identity keys, group secrets or session state.
- A relay forwards the exact sealed payload unchanged and decrements the hop budget.
- A relay drops expired packets, malformed packets, duplicates and packets with no remaining hops.
- The hop budget is a best-effort propagation limit, not a cryptographic proof: a malicious relay can rewrite this unauthenticated field, but it cannot alter the authenticated routing fields or ciphertext undetectably.
- The destination token is pseudonymous, not anonymous. Reuse can permit correlation; a later discovery/mailbox design should rotate tokens and avoid publishing stable user identifiers.
- No sender ID, display name, community name, plaintext preview or encryption key is present in this outer frame.

## Current implementation boundary

\`RelayPacket\` validates and serializes the frame only. It does not encrypt, decrypt, sign, authenticate, deduplicate, persist, route, fragment or connect to BLE/Wi-Fi/Internet. Do not pass plaintext to \`sealedPayload\`. The minimum 16-byte payload size reserves room for an AEAD authentication tag; it is not proof that the bytes are encrypted.

## Required next work

1. Select and audit an Android-compatible E2EE implementation; do not invent a ratchet or group key schedule.
2. Bind the immutable header above as AEAD associated data.
3. Add persistent replay/deduplication with expiry and bounded storage.
4. Add transport-specific framing and tests, especially BLE MTU fragmentation.
5. Run tests and interoperability checks on at least two physical Android devices.
