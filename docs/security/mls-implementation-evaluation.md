# MLS implementation evaluation (Android)

Status: updated 2026-11-13. No MLS library is integrated yet.

## Protocol fit

RFC 9420 MLS is a good architectural fit for both two-person conversations (a group of two) and invitation-based communities. It is transport-agnostic and defines group epochs, KeyPackages, Welcome messages, membership changes, forward secrecy and post-compromise security.

Official standard: https://www.rfc-editor.org/rfc/rfc9420.html

## Primary proof-of-concept candidate: mls-kmp

- Project: https://github.com/vitorpamplona/mls-kmp
- Published artifact documented by its README: `com.vitorpamplona.mls:mls:1.2.0`
- Language: Kotlin Multiplatform; the project declares JVM and Android targets.
- License: MIT.
- Ciphersuite currently documented: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (MLS mandatory-to-implement suite).
- Its `MlsGroup.encrypt` API accepts `authenticatedData`; the library has a test proving that the receiver gets the value and that altering it causes decryption to fail. This is a direct fit for RelayPacket's immutable routing header. The AAD remains visible to relays, so it must contain only non-secret routing metadata.
- Its repository documents RFC 9420 test vectors and cross-implementation interoperability work with OpenMLS, mls-rs and ts-mls.
- Its `MlsGroup` is mutable and explicitly not thread-safe: all calls and reads for one group must be serialized.
- `MlsGroup.saveState()` contains secret key material and sender-ratchet positions. The upstream persistence guide requires encrypted local storage and saving after state changes.
- `MlsGroupPolicy` is permissive by default. MeshChat must implement a strict policy for credential validation, join authorization, commits, standalone proposals and external joins; restoring a group without the same policy silently removes those application rules.
- No independent formal security audit was identified in the reviewed repository material. Interoperability tests are valuable evidence, but are not a security audit.

**Assessment:** best first candidate for a Kotlin-native MLS proof of concept because it exposes authenticated data directly and has a documented Android target plus cross-implementation tests. It is not approved for production or advertised as secure yet.

### Compatibility blocker before adding the dependency

The reviewed mls-kmp main branch uses Kotlin 2.4.21 and AGP 9.4.1 in its build. MeshChat currently uses Kotlin 2.0.21, AGP 8.7.3 and Gradle 8.9. Do not add the dependency to the app blindly: first create a compatibility smoke test and determine whether the published Android/JVM variant can be consumed with the existing toolchain. If not, upgrade the Android build stack in a separate change and verify API 29 builds before merging.

## Alternative: OpenMLS

- Project: https://github.com/openmls/openmls
- Documentation: https://openmls.tech/
- Language: Rust.
- It implements RFC 9420 and documents the X25519/AES-128-GCM/SHA-256/Ed25519 suite.
- Its upstream platform matrix lists `aarch64-linux-android` as built in CI but not tested. This is not equivalent to a device-tested Android integration.
- No official first-party Kotlin/Android binding was confirmed in this review.

**Assessment:** credible Rust alternative with established maintainers, but it requires a maintained JNI/UniFFI boundary, Android ABI builds, state persistence and a separate interop harness. Keep as fallback if mls-kmp's compatibility or security review fails.

## Other alternatives and cautions

- `mls-rs` (AWS Labs): another Rust RFC 9420 implementation with conformance-focused features. Android packaging and Kotlin API still need a separate proof of concept. https://github.com/awslabs/mls-rs
- `kotlin-mls`: a third-party Kotlin wrapper around OpenMLS. Its own README warns that it has not had a formal security audit; do not adopt it as a production dependency. https://github.com/zeroxv6/kotlin-mls

A library claiming RFC support, Android compilation or interoperability is not, by itself, evidence of an independent security audit or production readiness.

## Required proof-of-concept design

1. Pin an exact library version and dependency graph; record the licence, release source and update policy.
2. Build and run the Android target on API 29 and a current Android release. Confirm the dependency works with MeshChat's actual Gradle/Kotlin/AGP versions.
3. Expose a small application boundary: create identity, export public credential/fingerprint, create KeyPackage, create group, add/remove member, process Welcome/Commit, encrypt/decrypt application message, export/import encrypted state and close the group.
4. Serialize every operation on one group through a single-thread actor or mutex. Do not call a mutable MLS group concurrently.
5. Implement a non-permissive `MlsGroupPolicy`. Bind the verified device/account identity to the MLS credential; never treat an unverified QR token as proof of identity.
6. Pass `RelayPacket.authenticatedHeader()` as MLS authenticated data and test that a relay changing packet ID, destination token or expiry causes receiver authentication to fail. Keep the hop counter outside authenticated data.
7. Encrypt `MlsGroupState` and KeyPackage private bundles at rest with an Android Keystore-backed wrapping key. Save state atomically after each message/commit that advances ratchets; never restore an older state in a way that can reuse a sender key/nonce generation.
8. Run the library's upstream test suite and cross-implementation vectors. Then test two physical Android devices, member removal, delayed/reordered messages, process death, restore and corrupted state.
9. Obtain an independent security review before any production E2EE claim.

## Decision gate

Do not merge a crypto dependency until the proof of concept demonstrates:
- Android API 29+ compatibility on physical devices;
- correct MLS state persistence without nonce/generation reuse after process death or restore;
- authenticated identity binding and QR fingerprint verification;
- correct add/remove epoch transitions and removed-member exclusion;
- rejection of tampered, replayed and invalid/out-of-order messages;
- relay-only operation with no private keys, MLS state or plaintext available to the relay.
