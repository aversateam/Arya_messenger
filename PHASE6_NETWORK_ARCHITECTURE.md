# Arya Messenger — Phase 6 Network Architecture

Last updated: 2026-11-10 · Status: **Phase 6.2 code complete, build verification owned by the project owner**

## 1. Design constraints

1. **No local-network mode.** There is no LAN transport, no mDNS/NSD, no device discovery, no subnet
   advertisement and no LAN UI. Every message crosses the internet.
2. **Zero infrastructure cost.** No company server, no paid relay, no bootstrap fleet. The app works
   with nothing configured, and any user can run a node.
3. **No central identity.** No phone number, email, username, directory, or account.
4. **Existing code is the source of truth.** Phase 4 session crypto and Phase 5 group crypto are
   reused, not replaced.
5. **Transport is replaceable.** UI, Room, conversations and group logic never know which transport
   delivered a message.

## 2. Layers

```
UI (Compose)            HomeScreen · ContactsScreen · ConversationScreen · NetworkScreen
                          │  only knows NetworkRuntime + AppRepository + NetworkConfig
Data                    AppRepository (Room) · NetworkConfig (SharedPreferences)
                          │  recordIncoming / recordOutgoing keyed by proven peer identity
Network API             NetworkRuntime · PeerTransport · PeerRef · DeliveryOutcome · NetworkState
Network core            DefaultNetworkRuntime (ordering, backoff, outcome reporting)
Transports              DirectTcpTransport (DIRECT_TCP) · RelayPeerTransport (RELAY)
                          └─ RelayNode: optional user-operated byte-pipe node
Session security        PeerSessionProtocol (signed transcript + ECDH + AES-GCM)
Identity                IdentityKeys (Keystore P-256) · PeerIds · RendezvousToken
```

Routes, in the order the runtime tries them:

1. **DIRECT_TCP** — dial a reachable `host:port`, and accept inbound sessions on this device.
   Needs no third party. Cannot reach carrier-grade NAT.
2. **RELAY** — pair through a node both sides configured. Works behind NAT because both connections
   are outbound.

`DeliveryOutcome.Delivered` carries `authenticatedPeer` — the key the handshake proved. That is the
only identity a message may be stored against; an address is a hint, never an identity.

## 3. ADRs

### ADR-001 — Abstraction now, transport library behind a gate
`jvm-libp2p` lists QUIC, yamux, AutoNAT and Circuit Relay v2 as beta, and has **no** hole punching,
**no** Kademlia DHT, **no** bootstrap and **no** rendezvous. go-libp2p has all of them but needs Go +
NDK + `gomobile` and an out-of-tree `.aar`. The durable asset is the abstraction, so nothing in
`:app` depends on either library yet.

### ADR-002 — No DHT, no bootstrap constants
A public Kademlia DHT is a crawlable index and would become a searchable address book. Discovery is
therefore out of band: QR identity exchange, optional reachability hints, and relay pairing. No
endpoint is compiled in.

### ADR-003 — Rotating rendezvous handles instead of peer ids
A relay must key on *something*. Publishing the permanent identity digest would let any node link all
of a device's traffic to one stable pseudonym and build a social graph, and would let a node follow a
device indefinitely.

`RendezvousToken.inboxHandle(publicKey, window)` instead hashes the recipient's identity digest with a
15-minute window. Any sender who already knows the recipient's key derives the same handle; the node
cannot link it to an identity without already knowing that key; and it is completely different in the
next window. The node sees "someone is attached to handle X now" and nothing more.

Two slots (current and next window) absorb normal clock skew between phones.

### ADR-004 — Two-layer identity, Keystore remains the root
The Keystore P-256 key is non-exportable, so a future libp2p host cannot be handed it.
`TransportIdentity` provisions a separate transport keypair sealed by a Keystore-backed AEAD vault and
signs its public key with the root key over a domain-separated transcript. It is **provisioned but not
yet consumed** — it exists for the ADR-001 gate, and fingerprints, QR cards, contacts and group
membership are unchanged.

### ADR-005 — Transport encryption is not end-to-end encryption
`PeerSessionProtocol` protects one connection. It has no per-message forward secrecy and cannot serve
an offline recipient. It must never be labelled E2EE. That layer is Phase 6.6, and only audited
protocols are acceptable there. **libsignal is AGPL-3.0 and unusable for this project as licensed**;
Olm/Megolm (Apache-2.0) and OpenMLS (MIT) are the realistic candidates.

## 4. Relay model

The relay is a **dumb byte pipe**, not a messaging server:

- `DELIVER(handle)` arrives from a sender and waits up to 8 s.
- `INBOX(handle)` is a persistent outbound connection from a device, one per window slot.
- On a match the node replies `PAIRED_INITIATOR` / `PAIRED_RESPONDER` and splices the two sockets.
- The peers then run the normal signed handshake through the splice.

The node stores nothing, has no user database, and requires no account.

**Metadata a node operator can observe:** that two connections are attached to the same rotating
handle, their source IPs, session start time, duration and rough byte volume. It cannot read message
content, cannot enumerate contacts, and cannot link a handle to an identity or to the previous window.

## 5. What anonymity means here — and does not

| Property | State |
|---|---|
| No phone number / email / username | implemented |
| No central directory or DHT | implemented |
| Private keys stay on device | implemented — P-256 in Keystore, transport key sealed by Keystore AEAD |
| Peer authentication by public key | implemented — signed transcript binds both keys, names, ephemerals, nonces, roles |
| Contact list never published | implemented |
| Relay cannot link traffic to an identity | implemented — rotating window handles (ADR-003) |
| Relay cannot see message content | implemented — session is encrypted end to end across the splice |
| Relay cannot see your IP | **no** — a relay sees the source address of every connection |
| Hidden message size/timing | **no** |
| Rotating network identity on the wire | relay handles rotate; the P-256 identity does not |
| Revocable invitations | **not implemented** |
| Tor / anonymous routing | **not implemented** |

Two honest conclusions: a relay operator can always tell *that* traffic happened from an IP address at
a time, even though it cannot tell who or what. True network-layer anonymity requires Tor, which is
Phase 6.7. Do not describe the current build as anonymous on the network layer.

A direct connection additionally reveals your IP to the peer you are talking to, but only to a
verified contact.

## 6. Cryptographic inventory

| Purpose | Mechanism | Location |
|---|---|---|
| Root identity | P-256 ECDSA, non-exportable, Android Keystore | `security/IdentityKeys.kt` |
| Signature verification | P-256 param-equality enforced before verify | `security/IdentitySignatures.kt` |
| Session agreement | ephemeral P-256 ECDH + HKDF-SHA-256 | `security/EphemeralSessionCrypto.kt` |
| Session record | AES-256-GCM, sequence as AAD, replay rejection | `security/EphemeralSessionCrypto.kt` |
| Secret wrapping | AES-256-GCM under a non-exportable Keystore key | `security/KeystoreAesVault.kt` |
| Group envelope | AES-256-GCM + P-256 signature, epoch-bound | `security/GroupCrypto.kt` |
| Peer identifiers | SHA-256 over SPKI DER | `security/PeerIds.kt` |
| Relay handles | SHA-256 of identity digest + 15-minute window | `security/RendezvousToken.kt` |

No new cryptographic primitive or protocol was invented in Phase 6.

## 7. Android execution strategy

The runtime lives in `AryaApplication`: one repository, one Room instance, one network runtime, one
optional relay node. It survives rotation but **not** process death or Doze. Reliable background
delivery needs a foreground service with `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` — that
is Phase 6.5, and until then no always-on connectivity is claimed.
