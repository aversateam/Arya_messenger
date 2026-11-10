# Arya Messenger — Phase 6.1 / 6.2 Implementation Status

Last updated: 2026-11-10

## Verification status — read this first

Per the project owner's instruction, **no Gradle build, unit test, or device test was executed while
writing this code**. Nothing below is reported as "passing". The compile state is unverified and the
owner runs builds, tests and commits.

Commands the owner should run:

```bat
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleDebugAndroidTest
```

Known first-run expectation: the Room KSP step must regenerate the schema and produce
`app/schemas/dev.arya.messenger.data.MessengerDatabase/4.json`, which should be committed.

## Implemented in Phase 6.1

### Network abstraction — `dev.arya.messenger.network`
- `NetworkTypes.kt` — `RouteKind`, `PeerAddress` (`Tcp` with LAN/INTERNET scope, `Multi`,
  `RelayHint`), `PeerRef`, `FailureReason`, `DeliveryOutcome`, `NetworkState`, `Reachability`.
- `PeerTransport.kt` — the replaceable transport contract.
- `NetworkRuntime.kt` + `DefaultNetworkRuntime` — ordered route escalation, 15 s per-(peer, route)
  backoff that never blocks the cheapest route, observable state.
- `TransportSupport.kt` — strict UTF-8 payload decoding and failure classification.

### LAN re-expressed behind the interface
- `PeerSessionProtocol.kt` — the Phase 4 handshake and record layer extracted verbatim so it can
  run over any byte stream. Wire format, transcript, limits and fail-closed behaviour unchanged.
- `LanPeerTransport.kt` — now a listener/dialer over that protocol, with a concurrent-connection cap.
- `LanTransportAdapter.kt` — implements `PeerTransport` with `kind = LAN_TCP`.

### Conversation identity fix
- `PeerIds.kt` — direction-symmetric peer id (`pk:`) and conversation id (`dm-`), derived from the
  existing fingerprint.
- `AppRepository.recordIncoming` / `recordOutgoing` — one code path for every transport, keyed by the
  identity the handshake proved. Fixes the previous behaviour where inbound used
  `lan-<publicKey.hashCode()>` and outbound used `lan-host-<ip>`, producing two threads per peer.
- Removed the unused Phase 1 stubs `PeerTransport`, `PeerDiscovery`, `RelayTransport`,
  `LocalOnlyTransport`, `LocalPeerDiscovery`.

### Room v3 → v4 (additive only)
- New `peers` table (+ index on `publicKey`).
- `identity.transportPublicKey`, `identity.transportKeySealed`.
- No `DROP`, no `DELETE`, no destructive fallback. Legacy `lan-*` conversations remain readable but
  orphaned: the peer key was never stored for them, so they cannot be re-keyed honestly.

### Lifecycle and identity
- `AryaApplication` — process-scoped repository (single Room instance) and network runtime; NSD
  registration and the listening socket no longer follow Activity/composition lifecycle.
- `SecretVault` / `KeystoreAesVault` — Keystore AES-GCM vault extracted from `GroupKeyVault`, which
  now delegates. No behaviour change to Phase 5.
- `network/identity/TransportIdentity.kt` — Layer-2 transport keypair sealed by Keystore and bound to
  the root P-256 key by a domain-separated signature. **Provisioned but not yet consumed:** it is the
  input to the libp2p layer and is intentionally unused until the ADR-001 gate resolves.

## Implemented in Phase 6.2

### Direct internet TCP — `DirectTcpTransport` (`DIRECT_TCP`)
Dials a peer reachable at an advertised `host:port` and runs the same authenticated session. Requires
no third party and no operating cost. Cannot reach a peer behind carrier-grade NAT.

### Serverless relay — `network/relay/`
- `RelayProtocol.kt` — `ARYR`/v1 pairing handshake, fixed 32-byte peer ids, explicit status codes.
- `RelayNode.kt` — optional node any user can run on their own device. Pure byte splice: no storage,
  no user database, no account, no plaintext. Concurrent-connection cap and pairing timeout.
- `RelayPeerTransport.kt` (`RELAY`) — sender pairs with the recipient's persistent inbox connection
  through a user-configured node, then runs the normal session across the splice. Reports
  `UNREACHABLE` when the recipient is not attached, because nothing is queued.

### Reachability hints
- `IdentityQr` v2 — identity plus optional `endpoint` and `relay` hints. v1 cards still decode. Hints
  are strictly shaped (`host:port`, no whitespace, port range checked) and dropped if invalid.
- `NetworkConfig` — user-owned relay list, public endpoint, node-mode toggle/port, and per-peer hints.
  No endpoint is hardcoded.
- `ContactsScreen` stores hints on scan; `LanScreen` gains recipient selection, direct/internet
  address entry, relay configuration and node-mode switch.

## What is not implemented

| Area | State |
|---|---|
| libp2p / QUIC / Noise via a library | **not started** — gate not executed |
| AutoNAT, hole punching, Circuit Relay v2 | not started (Phase 6.3) |
| Kademlia DHT / rendezvous service | deliberately none (ADR-002) |
| Store-and-forward, offline queue, retries, dedupe, expiry | not started (Phase 6.5) |
| App-layer E2EE (PQXDH / Double Ratchet) | not started (Phase 6.6) |
| MLS for groups; group key distribution | not started (Phase 6.6) |
| Encrypted file transfer | not started (Phase 6.6) |
| Message bodies encrypted at rest | still plaintext for 1:1 (carried from Phase 4) |
| Foreground service / background delivery | not started (Phase 6.5) |
| Tor, rotating identifiers, revocation | not started (Phase 6.7) |
| Migration test (3→4) | **written target, not written** — `androidTest` source set still empty |

## Honest limitations of the current code

1. **No internet transport has been executed on a device.** The code path exists; its runtime
   behaviour is unverified.
2. **Relay pairing requires a reachable node.** A node behind carrier-grade NAT helps nobody. Node
   mode is useful for public hosts, port-forwarded devices, or peers sharing a VPN.
3. **Both peers must be online simultaneously.** Nothing is stored, so an offline recipient is a
   failure, not a queue.
4. **Relay metadata is exposed** to whoever runs the node: peer ids, timing, source IPs, session
   sizes. Content is not exposed.
5. **Node-mode inbox burns battery and upload** while the app process is alive; it dies with the
   process.
6. **Transport is text-only.** `LanTransportAdapter` and the relay path reject non-UTF-8 payloads
   rather than mangling them. Binary file transfer needs the framed transport in 6.6.
7. **One message per connection.** No multiplexing, no keep-alive, no streaming.
8. **The direct route has no inbound listener.** This device can dial out but cannot accept a direct
   internet connection; it can accept LAN connections and relay pairings.
9. **A QR relay hint is added to the relay list on scan.** The user consented by scanning, but the
   node is not authenticated. It only routes; it cannot read or forge messages.
10. **LAN availability is reported on `start()`**, not on a successful port bind. If the listening
    port is already taken, the route still claims to be available and the failure surfaces as an
    unreachable send.

## Test checklist for the owner

Automated (JVM, no device):
- [ ] `:app:testDebugUnitTest` — the six existing suites must still pass unchanged.
- [ ] Confirm `LanPeerTransportTest` still compiles (its constructor is unchanged).

Instrumented (device/emulator):
- [ ] `:app:assembleDebugAndroidTest` compiles.
- [ ] Migration 3 → 4 preserves rows and creates `peers`.
- [ ] Two devices on one Wi-Fi: QR verify → send. Expect **one** thread per peer in both directions.
- [ ] Same pair with Wi-Fi off: sending must fail with a specific reason, not a crash.

Two-device internet test (only after a relay node or a reachable endpoint exists):
1. Device A: enter node address in "مسیر اینترنتی" and enable node mode (needs a reachable address).
2. Device B: enter the same node address as its relay.
3. Both: verify each other by QR and fingerprint comparison.
4. B sends to A while A's app is open. Expected: delivered via `RELAY`.
5. Close A's app and repeat. Expected: `ارسال ناموفق` (offline), not silent loss.

## Next stage

**Phase 6.3** needs a decision first: run the ADR-001 gate (documented below), then either integrate
go-libp2p via gomobile for hole punching, or accept relay-first and invest in 6.5 delivery
guarantees. Given the zero-cost constraint, relay-first with store-and-forward is the cheaper path;
hole punching is the better experience but needs a native toolchain.

### ADR-001 gate (not yet executed)

1. Does `io.libp2p:jvm-libp2p` resolve through the configured mirror chain?
2. Does `:app:assembleDebug` succeed with it present (Netty + BouncyCastle dexing/conflicts)?
3. Does a loopback Noise handshake + stream echo actually run?
4. Which key types does the host accept — deciding `TransportIdentity`'s concrete key?
5. Does the QUIC binding (`kwik`, LGPL-3.0) ship Android ABIs?
6. Is hole punching available in the chosen stack?
