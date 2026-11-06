# MLS implementation evaluation (Android)

Status: preliminary research, 2026-11-06. No MLS library is integrated.

## Protocol fit

RFC 9420 MLS is a strong architectural fit for both two-person conversations (a group of two) and invitation-based communities. It is transport-agnostic and designed for asynchronous groups; the standard defines group epochs, KeyPackages, Welcome messages, member additions/removals, and forward secrecy/post-compromise security.

Official standard: https://www.rfc-editor.org/rfc/rfc9420.html

## Candidate: OpenMLS

- Project: https://github.com/openmls/openmls
- Documentation: https://openmls.tech/
- Language: Rust.
- It implements RFC 9420 and documents X25519/AES-128-GCM/SHA-256/Ed25519 as its mandatory-to-implement suite.
- Its current upstream platform matrix lists \`aarch64-linux-android\` as built in CI but not tested. This is not equivalent to a supported, device-tested Android Kotlin integration.
- The project is maintained by Phoenix R&D and Cryspen.
- No official first-party Kotlin/Android binding was confirmed in this review.

**Assessment:** leading candidate for a thin in-house Rust-to-Kotlin bridge (UniFFI or JNI), but not approved for production use until Android ABI builds, state persistence, interop vectors and real-device tests pass.

## Alternatives and cautions

- \`mls-rs\` (AWS Labs): another Rust RFC 9420 implementation with conformance-focused features. Android packaging and Kotlin API still need a separate proof of concept.
  - https://github.com/awslabs/mls-rs
- \`mls-kmp\`: a Kotlin Multiplatform MLS implementation that advertises JVM/Android support. It may reduce FFI work, but its cryptographic implementation and interoperability must be independently reviewed before adoption.
  - https://github.com/vitorpamplona/mls-kmp
- \`kotlin-mls\`: a third-party Kotlin wrapper around OpenMLS. Its own README explicitly warns that it has not had a formal security audit; it is not adopted as a production dependency.
  - https://github.com/zeroxv6/kotlin-mls

A library claiming RFC support or Android compilation is not, by itself, evidence of a security audit, production readiness, or interoperability with other implementations.

## Recommended proof of concept

1. Pin an OpenMLS release and its Rust dependency graph; record license and update policy.
2. Build native libraries for \`arm64-v8a\` and \`x86_64\`; keep the Android app's minSdk 29.
3. Expose a deliberately small Kotlin API: create identity, export public credential/fingerprint, create KeyPackage, create group, add member, process Welcome/Commit, encrypt application message, decrypt application message, export/import encrypted state, and close/zeroize transient secrets.
4. Store MLS state encrypted at rest; protect the wrapping key with Android Keystore. Never log serialized key material or plaintext.
5. Pass RelayPacket's immutable authenticated header as MLS \`FramedContent.authenticated_data\`. RFC 9420 includes that field in the authenticated/encrypted message construction. If the selected binding cannot set and return this field, do not silently omit header binding: place the same routing values inside the authenticated application payload and verify them after decryption.
6. Run upstream MLS test vectors and cross-implementation interop tests. Then test two physical Android devices, member removal, delayed/reordered messages, process death, restore, and corrupted state.
7. Obtain an independent security review before claiming production-grade E2EE.

## Decision gate

Do not add a dependency to the app until the proof of concept demonstrates:
- API 29+ compatibility on actual devices;
- correct MLS state persistence without nonce/generation reuse after process death or restore;
- authenticated identity binding and QR fingerprint verification;
- correct add/remove epoch transitions;
- rejection of tampered, replayed and out-of-order messages according to the selected library's documented behavior;
- relay-only operation with no private keys or plaintext available to the relay.
