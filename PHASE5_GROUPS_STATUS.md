# Arya Messenger — Phase 5 Group Security Status

## Scope delivered in this snapshot

Phase 5 adds local group membership management and authenticated, epoch-bound group text encryption. It does **not** add remote group delivery, group-key distribution to other devices, invitations, or Internet connectivity. Those require the Phase 6 transport and protocol work.

## Implemented

- Group creation is restricted to the local identity and contacts that have been explicitly verified by fingerprint.
- The local identity is recorded as the immutable group owner; only the owner can add or remove members.
- Active group roster is bounded to 100 identities, and duplicate public keys are rejected.
- Membership history is stored as immutable membership intervals keyed by `joinedEpoch`; removal closes an interval at the next epoch. Re-joining creates a new interval rather than rewriting old history.
- Every membership change creates a fresh 256-bit group key and increments the group epoch inside a Room transaction.
- Epoch keys are wrapped with AES-256-GCM using a non-exportable Android Keystore AES key. Group ID and epoch are authenticated as AAD.
- Group text envelopes use AES-256-GCM with a fresh 96-bit nonce. The authenticated data binds group ID, epoch, message ID, and sender identity key.
- The sender signs the complete envelope header, nonce, and ciphertext with the existing P-256 Android Keystore identity key.
- Decoding enforces version, field-size bounds, strict UTF-8, and no trailing bytes. Decryption checks group, epoch, message ID, sender membership for that historical epoch, signature, and GCM tag.
- Newly sent group text is stored as an encrypted envelope in Room and decrypted for display. Messages are decrypted using their own epoch, not the group's latest epoch. Pre-existing legacy group messages from before this phase remain in their original local plaintext form; migration does not silently claim to encrypt old content.
- Group attachments are deliberately disabled; their group encryption and safe key distribution are not implemented.
- Room schema version 3 migration adds group ownership/epoch metadata, active roster fields, membership history, and sealed epoch-key storage.

## Important limitations

- This is local group cryptography only. The same group key is not yet securely delivered to other members' devices; therefore group messages cannot yet be sent or received remotely.
- There is no group invitation/acceptance protocol, member leave flow, admin role, sender-key distribution, or Double Ratchet.
- New group text envelopes are encrypted in the local database, and group epoch keys are Keystore-wrapped. Legacy group messages created before this phase, existing one-to-one message bodies, and attachments from the base snapshot are not encrypted at rest by this phase.
- Group attachments are blocked rather than stored as plaintext under a misleading secure-group UI.
- Reinstalling or clearing app data can destroy the Android Keystore wrapping key; locally stored group epoch keys may then be unrecoverable. Android backup is disabled in the manifest.
- Android Gradle build and instrumentation tests still need to run on a machine with the configured Gradle distribution and Android SDK.

## Validation in this environment

- `GroupCrypto.kt` and `IdentitySignatures.kt` compile with the installed Kotlin compiler.
- Group crypto smoke/negative validation passed 7 cases: signed round-trip, wrong epoch, wrong message ID, unlisted sender, trailing data, ciphertext/signature tampering, and wrong key.
- `Models.kt` and `Repositories.kt` compile against lightweight Android/Room/Flow API stubs; this checks Kotlin type/syntax integration but is not a substitute for Android/KSP compilation.
- SQLite v2→v3 migration SQL and legacy group-owner backfill were exercised against an in-memory SQLite database.
- Full Gradle test/build could not start: the wrapper distribution host `mirrors.cloud.tencent.com` could not be resolved (`UnknownHostException`).
- No Android Keystore instrumentation test, Room migration instrumentation test, or multi-device test has been performed here.

## Phase boundary

Phase 5 completes local group state and local authenticated group text encryption only. Phase 6 must design and implement secure epoch-key distribution, remote group delivery, reconnect/offline behavior, NAT traversal, and relay/node routing before groups can communicate between distant devices.
