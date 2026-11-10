# Arya Messenger — Phase 6 Network Architecture

Last updated: 2026-11-10 · Status: **Phase 6.2 code complete, build verification owned by the user**

This document describes the intended architecture and the decisions behind it. It deliberately
separates what is implemented from what is planned; see `PHASE6_IMPLEMENTATION_STATUS.md` for the
honest current state.

---

## 1. Design constraints

1. **Zero infrastructure cost.** No company server, no paid relay, no bootstrap fleet. The app must
   work with nothing configured.
2. **No central identity.** No phone number, email, username, or user directory.
3. **Existing code is the source of truth.** Phase 4 transport crypto and Phase 5 group crypto are
   reused, not replaced.
4. **Transport is replaceable.** UI, Room, conversations and group logic must not know which
   transport delivered a message.
5. **Metadata honesty.** Encryption of content does not mean anonymity. Operators see metadata.

## 2. Layers

```
UI (Compose)            HomeScreen · ContactsScreen · ConversationScreen · LanScreen
                          │  only knows NetworkRuntime + AppRepository
Data                    AppRepository (Room) · NetworkConfig (SharedPreferences)
                          │  recordIncoming / recordOutgoing keyed by peer identity
Network API             NetworkRuntime · PeerTransport · PeerRef · DeliveryOutcome · NetworkState
                          │  route selection, observable state, no UI or DB knowledge
Network core            DefaultNetworkRuntime (ordering, backoff, outcome reporting)
Transports              LanTransportAdapter (LAN_TCP) · DirectTcpTransport (DIRECT_TCP)
                        RelayPeerTransport (RELAY) · RelayNode (optional, user-operated)
Session security        PeerSessionProtocol (Phase 4: signed transcript + ECDH + AES-GCM)
Identity                IdentityKeys (Keystore P-256) · PeerIds · TransportIdentity (L2 binding)
```

`PeerTransport` is the replaceable seam:

```kotlin
interface PeerTransport {
    val kind: RouteKind
    val available: StateFlow<Boolean>
    fun start(displayName: String)
    fun stop()
    suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome
}
```

`DeliveryOutcome.Delivered` carries `authenticatedPeer` — the key the handshake proved. That is the
only identity a message may be stored against; a dialled address is a hint, never an identity.

## 3. ADRs

### ADR-001 — Abstraction now, transport library behind a gate
`jvm-libp2p` is actively released and has an Android sample, but its own support matrix lists QUIC,
yamux, AutoNAT and Circuit Relay v2 as beta and has **no** hole punching, **no** Kademlia DHT, **no**
bootstrap, **no** rendezvous. go-libp2p has all of those but requires Go + NDK + `gomobile` and an
out-of-tree `.aar`.
The durable asset is the abstraction; the dependency is not. Nothing in `:app` depends on either
library. The gate criteria and procedure are in the status document.

### ADR-002 — Rendezvous over DHT, and no bootstrap constants
A public Kademlia DHT is a crawlable index and would become a searchable address book, which
violates the privacy requirement. Discovery therefore uses address hints carried out of band (QR)
plus relay pairing by peer identity. No bootstrap or relay endpoint is hardcoded anywhere.

### ADR-003 — Two-layer identity, Keystore remains the root
The Keystore P-256 key is non-exportable, so a libp2p host cannot be handed it. `TransportIdentity`
provisions a separate transport keypair, sealed with a Keystore-backed AES-GCM vault, whose public
key is signed by the root P-256 key over a domain-separated, length-prefixed transcript
(`arya/phase6/transport-key-binding/v1`). This mirrors libp2p's own "Noise static key authenticated
by the identity key" rule. Fingerprints, QR cards, contacts and group membership are unchanged.

### ADR-004 — Transport encryption is not end-to-end encryption
`PeerSessionProtocol` protects one connection. It is not a Double Ratchet session, has no
per-message forward secrecy, and cannot serve an offline recipient. The app must never label it E2EE.
An independent payload-sealing layer is Phase 6.6, and the only acceptable implementations there are
audited protocols. **libsignal is AGPL-3.0 and therefore unusable for this project as licensed**;
Olm/Megolm (Apache-2.0) and OpenMLS (MIT, Rust/JNI) are the realistic candidates.

### ADR-005 — User-owned endpoints, no operator dependency
Relay and direct endpoints live in `NetworkConfig`, entered by the user or imported from a scanned
QR invitation. There is nothing to pay for, and nothing a third party can shut down.

## 4. Connection strategy

Order, cheapest first:

1. **LAN TCP** — same subnet, no dependency, already proven.
2. **Direct TCP** — the peer's advertised `host:port`. Needs an actually reachable peer (public
   address, forwarded port, shared VPN). Costs nothing.
3. **Relay** — both sides dial out to a node they both configured. Works behind carrier-grade NAT
   because neither side accepts an inbound connection.

Escalation stops at the first `Delivered`. A route that just failed for a peer is skipped for 15 s,
except the cheapest available route, so a user retry is never silently swallowed while expensive
escalation is not repeated on every attempt.

Nothing is stored server-side, so an offline recipient is reported as `UNREACHABLE`, not queued.
Store-and-forward is Phase 6.5 and requires sealed envelopes from 6.6.

## 5. Relay model

The relay is a **dumb byte pipe**, not a messaging server:

- `DELIVER(selfId → targetId)` arrives from a sender and waits up to 15 s.
- `INBOX(selfId)` is a persistent outbound connection from some device.
- On a match the node replies `PAIRED_INITIATOR` / `PAIRED_RESPONDER` and splices the two sockets.
- The two peers then run the normal signed handshake through the splice.

The node therefore sees only ciphertext after the handshake. It stores nothing, has no user
database, and requires no account.

**Metadata a node operator can observe:** which peer IDs connect, when, from which source IP, how
long each session lasts, and roughly how much data each session carries. It cannot read message
content, and it cannot enumerate a user's contact list.

**Direct-endpoint and QR-hint metadata:** an advertised endpoint reveals that a device is reachable
at an address and is willing to accept connections there. That is the trade-off for not needing a
server, and it is why the hint is optional and user-entered.

## 6. Privacy boundaries

| Property | State |
|---|---|
| No phone number / email / username | implemented — identity is a local keypair |
| Private keys on device | implemented — P-256 in Keystore; transport key sealed by Keystore AEAD |
| Public-key peer authentication | implemented — signed transcript binds both keys, names, ephemerals, nonces, roles |
| Contact list never published | implemented — no directory, no DHT, contacts stay in local Room |
| QR invitations | implemented (v1 identity, v2 identity + optional reachability hints) |
| Revocable invitations | **not implemented** |
| Rotating network identifiers | **not implemented** |
| Anonymous routing | **not implemented** — relays see source IPs |
| Message content readable by relays | no — the session is encrypted end to end across the splice |
| Message size/timing hidden | no — not even after 6.6 |

Do not describe the current build as anonymous. It is not.

## 7. Cryptographic inventory

| Purpose | Mechanism | Location |
|---|---|---|
| Root identity | P-256 ECDSA, non-exportable, Android Keystore | `security/IdentityKeys.kt` |
| Signature verification | P-256 param-equality enforced before verify | `security/IdentitySignatures.kt` |
| Session agreement | ephemeral P-256 ECDH + HKDF-SHA-256 | `security/EphemeralSessionCrypto.kt` |
| Session record | AES-256-GCM, sequence as AAD, replay rejection | `security/EphemeralSessionCrypto.kt` |
| Secret wrapping | AES-256-GCM under a non-exportable Keystore key | `security/KeystoreAesVault.kt` |
| Group envelope | AES-256-GCM + P-256 signature, epoch-bound | `security/GroupCrypto.kt` |
| Peer identifiers | SHA-256 over SPKI DER, reusing the fingerprint | `security/PeerIds.kt` |

No new cryptographic primitive or protocol was invented in Phase 6.1/6.2.

## 8. Android execution strategy

The runtime currently lives in `AryaApplication`: one repository, one Room instance, one network
runtime, one optional relay node. It survives rotation, but it does **not** survive process death or
Doze. Reliable background delivery needs a foreground service with
`FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS`, a notification channel, and a reconnect
policy — that is Phase 6.5, and until then no always-on connectivity is claimed.
