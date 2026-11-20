# BLE fragment framing v1

Status: implemented as transport-independent Kotlin framing/reassembly and JVM unit tests. It is not connected to Android Bluetooth APIs.

## Why

A RelayPacket v1 can be up to 65,586 bytes. It cannot be sent as one BLE GATT characteristic write. This layer divides the encoded RelayPacket into bounded frames and reassembles them before the receiver parses the packet or runs E2EE authentication.

## Frame layout (big-endian)

| Field | Bytes | Meaning |
|---|---:|---|
| Magic | 2 | ASCII `MF` |
| Version | 1 | `1` |
| Transfer ID | 4 | Non-zero random uint32; local transfer correlation only |
| Fragment index | 2 | Zero-based index |
| Fragment count | 2 | Total fragments |
| Total length | 4 | Complete encoded packet byte length |
| Chunk size | 2 | Payload bytes in every non-final fragment |
| Payload | 1–65,518 | Fragment data |

The header is 17 bytes. The transfer ID is not an identity or authentication credential. It must be unique among active transfers; a collision with a transfer that has different metadata is rejected and clears that partial assembly.

## MTU handling

`BleFragmenter.fragment` takes `maxFrameBytes`, the actual maximum bytes available for one GATT characteristic payload. For a negotiated ATT MTU, normally use `mtu - 3` (ATT protocol overhead); do not pass the raw MTU.

The minimum supported GATT payload budget is 20 bytes (ATT MTU 23), leaving a 3-byte fragment payload. This works at the BLE minimum but is intentionally inefficient. Prefer a larger negotiated MTU when both devices support it. The sender must still honor the actual characteristic write budget and write/notification limits.

## Reassembly limits

- Maximum encoded RelayPacket size: 65,586 bytes (must stay aligned with RelayPacket v1).
- Maximum fragment count: 21,862, derived from 3-byte chunks at the minimum frame budget.
- Maximum simultaneous incomplete transfers: 4 by default.
- Maximum reserved reassembly memory: 262,344 bytes by default (4 × maximum packet size).
- Incomplete transfer timeout: 60 seconds, based on monotonic elapsed time.
- Maximum completed-transfer IDs remembered: 4,096, for 60 seconds, to suppress duplicate fragments after completion.
- Supports out-of-order delivery and serializes state operations for concurrent BLE callbacks.
- Identical duplicate fragments are ignored.
- A duplicate with different bytes, or metadata that conflicts with an active transfer, drops that assembly and frees its reserved memory.
- Caller can explicitly cancel an active transfer.

Call `expire(SystemClock.elapsedRealtime())` periodically while BLE transport is active, and also `accept` expires stale entries. This code has no timer or Android API dependencies.

## Security boundary

This framing layer is not cryptographic. It does not encrypt, authenticate, sign, or validate a route. The transfer ID and fragment metadata are untrusted. The reassembled bytes must be parsed with `RelayPacket.decode`, checked against relay admission policy, and authenticated/decrypted by the selected E2EE engine on the destination device. A relay must not inspect or alter the sealed payload.

## Implemented JVM tests

- Reassembly of minimum-frame-size fragments in reverse order.
- Frame size bound and duplicate after completion.
- Identical duplicate handling and conflicting duplicate rejection.
- Metadata mismatch rejection and memory release.
- Active transfer and byte-budget enforcement.
- Timeout cleanup.
- Concurrent callbacks complete a transfer at most once and release reserved memory.
- Malformed/truncated frame and invalid transfer ID rejection.

## Not implemented

No Android BLE scanner, advertiser, GATT client/server, MTU negotiation, permission flow, retransmission/ACK protocol, delivery confirmation, battery policy, or physical-device BLE test exists yet. BLE reliability (missing fragments, retries, connection loss and resumption) remains a separate transport task.
