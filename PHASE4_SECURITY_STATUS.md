# Arya Messenger — Phase 4 Security Status

Last updated: 2026-11-09

## Scope

Phase 4 secures one-to-one messaging sessions. It does not implement nationwide Internet connectivity, NAT traversal, relays, or a decentralized node overlay; those belong to Phase 6. Group encryption remains out of scope for this phase.

## Implemented in this working snapshot

- Device-bound P-256 identity signing key remains in Android Keystore (`IdentityKeys`).
- Public identity signatures are verified against P-256 identity cards (`IdentitySignatures`).
- A versioned initiator/responder handshake now binds both identity public keys, names, ephemeral P-256 ECDH keys, fresh nonces, roles, and protocol version into one signed transcript.
- Both sides must find the other public key in their locally verified contacts before application data is accepted. Unknown or unverified peers fail closed.
- Fresh ECDH session secrets are expanded with HKDF-SHA-256 into direction-separated AES-256 keys.
- TCP application records use AES-GCM with authenticated sequence numbers; replay, out-of-order records, and tampering are rejected.
- The protocol includes encrypted session-finished, ready, and delivery-confirmation records. There is no plaintext fallback.
- The transport is wired into the app repository and LAN screen. The LAN UI now explains that both devices need a mutually verified contact and that this is not yet Internet-wide connectivity.
- Unit tests were updated for authenticated encrypted loopback delivery and rejection of untrusted peers.

## Validation performed in this environment

- The transport, identity-signature verifier, and session-crypto Kotlin sources compiled with the installed Kotlin compiler using lightweight data/Android-keystore stubs.
- A local JVM loopback smoke test completed the signed handshake, ECDH key agreement, encrypted delivery, and acknowledgement successfully (`AUTHENTICATED_E2E_LOOPBACK_OK`). The smoke test injected software test keys; it did not exercise Android Keystore.
- Full Android Gradle tests/build could not be run here because the configured Gradle distribution host could not be resolved (`UnknownHostException`). The user reports that the previous snapshot built and installed successfully; that does not validate this newer working snapshot.

## Still required before Phase 4 is complete

1. Encrypt message bodies in Room and attachment files at rest using Android Keystore-protected key material, with a safe migration plan for existing plaintext rows/files.
2. Run the Android Gradle unit-test suite and build/install this exact working snapshot.
3. Perform a two-device LAN integration test on Android to validate Keystore signatures, contact trust lookup, actual socket behavior, and lifecycle handling. This can be deferred until the user is ready to test devices, but it remains a release gate.
4. Add negative Android integration tests for modified handshake data, invalid signatures, replay/tampering, disconnects, and identity-key changes.

## Security boundary

This transport provides authenticated, encrypted one-message TCP sessions only between previously verified contacts on a reachable network. It does not provide persistent asynchronous E2EE sessions, Double Ratchet/forward secrecy between messages, encrypted local storage, groups, Internet NAT traversal, or relay routing. Do not market the current app as a finished secure messenger yet.
