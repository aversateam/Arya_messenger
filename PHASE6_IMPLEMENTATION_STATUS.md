# Arya Messenger — Phase 6.1 / 6.2 Implementation Status

Last updated: 2026-11-10

## Simplified product model

Connection happens in exactly one way: an **invitation**. The QR code, the copied link and a pasted
message are the same payload (`arya://v2/<base64url(json)>`), so there is nothing else to configure.

- **Home** = your conversations. `+` goes to contacts.
- **Contacts** = scan a QR or paste a link, then compare the fingerprint and verify.
- **Conversation** = type and send. The bubble footer reports the route (`SENT · DIRECT`,
  `SENT · RELAY`) or the specific failure.

Removed in this pass: the network settings screen, the standalone "new local conversation" flow, the
manual direct-address field, the relay list field, and the public-endpoint field as a separate screen.
The only network controls left are the address field and the relay-node switch inside the invite
dialog.

The invitation can carry two optional, unauthenticated hints: the sender's reachable `host:port` and a
relay `host:port`. Whichever the inviter includes, the recipient stores and uses. Relay addresses also
propagate automatically from received invitations, so no user ever types one.

**Reachability, without a settings screen:** the invite dialog has one optional address field and one
switch, "اجازه بده دیگران از من رد شوند". Turning it on starts `RelayNode` on `NetworkConfig.nodePort`
and makes this device advertise itself as the relay inside its own invite, so recipients need to type
nothing. It needs two forwarded ports to actually help anyone — the direct port from the address field
and the node port — and the dialog says so.

If neither side has a reachable address and no invitation has ever carried a relay, two ordinary
phones cannot reach each other. The app never pretends otherwise: the send fails with a specific
reason instead of silently queueing.

## Verification status — read this first

Per the project owner's instruction, **no Gradle build, unit test, or device test was executed while
writing this code**. Nothing below is reported as "passing". The compile state is unverified and the
owner runs builds, tests and commits.

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebugAndroidTest
```

The Room KSP step must regenerate the schema and produce
`app/schemas/dev.arya.messenger.data.MessengerDatabase/4.json`, which should be committed.

## Change of direction recorded here

The local-network mode was **removed** from the project at the owner's request:

- deleted `LanPeerTransport`, `LanDiscovery` (`AryaNsd`), `LanTransportAdapter`, `LanScreen`;
- deleted the obsolete `LanPeerTransportTest` and replaced it with `PeerSessionProtocolTest`, which
  keeps the same security assertions (signed handshake, encrypted delivery, untrusted peer rejected)
  over a plain socket pair;
- `RouteKind` no longer has `LAN_TCP`; the wire protocol, however, is byte-identical to Phase 4.

There is now no LAN transport, no mDNS/NSD, no subnet discovery, no LAN advertising, and no LAN UI.

## Implemented — network core

- `network/NetworkTypes.kt` — `RouteKind` (`DIRECT_QUIC`, `DIRECT_TCP`, `RELAY`), `PeerAddress`,
  `PeerRef`, `FailureReason`, `DeliveryOutcome`, `NetworkState`, `Reachability`.
- `network/PeerTransport.kt` — the replaceable transport contract.
- `network/NetworkRuntime.kt` + `DefaultNetworkRuntime` — ordered route escalation, 15 s
  per-(peer, route) backoff that never blocks the cheapest route, observable state.
- `network/TransportSupport.kt` — strict UTF-8 payload decoding and typed failure classification.
- `data/PeerSessionProtocol.kt` — the Phase 4 handshake and record layer, extracted so it can run
  over any byte stream. `initiate` for the dialer, `respond` for the inbound side.

## Implemented — routes

### DIRECT_TCP — `network/DirectTcpTransport.kt`
Dials a reachable `host:port` and accepts inbound sessions on the port derived from the device's
advertised endpoint. No third party, no cost. Cannot reach a peer behind carrier-grade NAT.

### RELAY — `network/relay/`
- `RelayProtocol.kt` — `ARYR`/v1 pairing: role + 32-byte rotating handle, explicit status codes.
- `RelayNode.kt` — optional node any user can run. Pure byte splice: no storage, no user database, no
  account, no plaintext, no stable identifiers. Connection cap and pairing timeout.
- `RelayPeerTransport.kt` — sender pairs with the recipient's inbox attachment through a configured
  node, then runs the normal session across the splice. Two inbox slots cover the current and next
  rotation window.
- `security/RendezvousToken.kt` — 15-minute rotating handle, unlinkable to the identity and to
  previous windows.

## Implemented — identity, storage, lifecycle

- `PeerIds` — direction-symmetric peer id (`pk:`) and conversation id (`dm:`). Fixes the old behaviour
  where inbound and outbound used different derivations and produced two threads per peer.
- `AppRepository.recordIncoming` / `recordOutgoing` — one path for every transport, keyed by the
  identity the handshake proved.
- Room v3 → v4: `peers` table + `identity.transportPublicKey` / `identity.transportKeySealed`.
  Additive only, no destructive fallback. Legacy `lan-*` conversations remain readable but orphaned:
  the peer key was never stored for them, so they cannot be re-keyed honestly.
- `SecretVault` / `KeystoreAesVault` — Keystore AES-GCM vault extracted from `GroupKeyVault`, which
  now delegates. No behaviour change to Phase 5.
- `network/identity/TransportIdentity.kt` — Layer-2 transport keypair sealed by Keystore and bound to
  the root key. **Provisioned but not yet consumed**; it is the input to the libp2p gate.
- `AryaApplication` — process-scoped repository and runtime, optional relay node.

## Implemented — invitation and configuration

- `IdentityQr` — one invitation format: `encode` (JSON, used inside the link), `link`
  (`arya://v2/<base64url>`), `decodeInvite` accepting a QR payload, a link, or a bare body. Optional
  `endpoint` / `relay` hints are strictly shaped and dropped if invalid.
- `NetworkConfig` — relay list, public endpoint and per-peer hints, populated only from invitations.
  No endpoint is hardcoded anywhere. There is no settings UI for it.

## Single inbound port

Inbound traffic for both roles arrives on **one** port, `45871` by default (or the port in your
advertised address). `PeerEntryPort` peeks the first four bytes:

| Magic | Role |
|---|---|
| `ARY4` (`0x41525934`) | an authenticated session — a peer sending us a message |
| `ARYR` (`0x41525952`) | a relay pairing |

The four peeked bytes are pushed back, so `PeerSessionProtocol` and `RelayNode` still read a normal
stream. After the handshake or hello the raw socket is used, because the pushback buffer is empty by
then. Unknown magic is dropped. A released relay node therefore reuses the same address that is
already in the invite, and only one port has to be forwarded.

## Release build, signing and architecture outputs

`app/build.gradle.kts` now has a real release type: minified, resource-shrunk, R8 rules in
`app/proguard-rules.pro`, and signing read from `keystore.properties` (gitignored). Without that file
the build still succeeds but stays unsigned.

**About per-architecture APKs:** there is currently **no native code at all** — no `.so`, no NDK, no
JNI. ABI splits would produce four byte-identical APKs and help nobody. The correct outputs are:

- **`bundleRelease`** → AAB. Google Play generates per-architecture, per-density and per-language
  splits itself, which is what "an APK for old and new devices" means in practice.
- **`assembleRelease`** → one universal signed APK that installs on every device from `minSdk 26`
  (Android 8.0) upward. That is what you sideload.
- Per-ABI splits become meaningful only when a native transport (libp2p/QUIC) is added; the block is
  documented but intentionally not enabled.

There is no older-API variant because `minSdk 26` is the floor. A device below Android 8.0 cannot be
targeted without dropping the Keystore and Compose baselines this app depends on.

## A note on store scanners and "malware" detection

No application can guarantee how an automated scanner classifies it, and I will not pretend
otherwise. What can be said precisely:

- The app is **not published on Google Play**, so there is no Play review verdict. What is seen is
  usually Play Protect's on-device scan of a sideloaded APK, or a multi-engine site such as
  VirusTotal. Those heuristics weigh code obfuscation, self-signed certificates, raw sockets,
  camera usage and the absence of any store reputation — none of which is evidence of malice, and
  all of which a private messenger legitimately has.
- Actions taken here that reduce real causes: an unused permission (`ACCESS_NETWORK_STATE`) was
  removed, leaving only `INTERNET` and `CAMERA`; release builds are minified with **targeted** keep
  rules rather than blanket `-keep class **` (blanket rules produce opaque artifacts that scanners
  dislike); signing material is separated from the repo so releases are consistently signed by one
  identity; `abortOnError` is enabled for lint.
- What would reduce ambiguity further, and is therefore recommended: publish a signed, versioned
  release on Play (internal testing is enough), keep one stable signing key forever, and publish the
  source and the APK checksums. Reputation, not obfuscation, is what scanners respond to.
- Do **not** add "anti-detection" tricks: packing, dynamic code loading, hiding the launcher icon, or
  disabling the debug flag at runtime. Those are exactly the behaviours scanners treat as malicious
  and they would also break the security model.

## Not implemented

| Area | State |
|---|---|
| libp2p / QUIC / Noise via a library | not started — gate not executed |
| AutoNAT, hole punching, Circuit Relay v2 | not started (Phase 6.3) |
| Kademlia DHT / rendezvous service | deliberately none (ADR-002) |
| Store-and-forward, offline queue, retries, dedupe, expiry | not started (Phase 6.5) |
| App-layer E2EE (PQXDH / Double Ratchet) | not started (Phase 6.6) |
| MLS for groups; group key distribution | not started (Phase 6.6) |
| Encrypted file transfer | not started (Phase 6.6) |
| Message bodies encrypted at rest | still plaintext for 1:1 |
| Foreground service / background delivery | not started (Phase 6.5) |
| Tor, revocation | not started (Phase 6.7) |
| Migration test 3 → 4 | `androidTest` source set still empty |

## Honest limitations

1. **No internet transport has been executed on a device.** The code path exists; runtime behaviour is
   unverified.
2. **A relay node must be reachable.** A node behind carrier-grade NAT helps nobody; node mode is for
   public hosts, port-forwarded devices, or peers sharing a VPN.
3. **Both peers must be online at the same time.** Nothing is stored, so an offline recipient is a
   failure, not a queue.
4. **A relay sees source IPs, timing and session sizes.** Content is not visible and the handle
   rotates, but the network layer is not anonymous. Tor is 6.7.
5. **Relay pairing depends on loosely synchronised clocks.** Handles are derived from a 15-minute
   window; two slots absorb ordinary skew, not a badly wrong device clock.
6. **Node-mode inbox burns battery and upload** while the process is alive, and dies with it.
7. **Transport is text-only.** Non-UTF-8 payloads are rejected rather than mangled; binary transfer
   needs the framed transport in 6.6.
8. **One message per connection.** No multiplexing, no keep-alive, no streaming.
9. **Changing the advertised endpoint port does not move the listener** until the app restarts.
10. **A QR relay hint is added to the relay list on scan.** The user consented by scanning, but the
    node is unauthenticated. It can only route; it cannot read or forge messages.

## Test checklist for the owner

Automated (JVM):
- [ ] `:app:testDebugUnitTest` — `PeerSessionProtocolTest` replaces the deleted LAN test; the other
      five suites must pass unchanged.

Instrumented:
- [ ] `:app:assembleDebugAndroidTest` compiles.
- [ ] Migration 3 → 4 preserves rows and creates `peers`.

Two-device internet test (needs a relay node or a reachable endpoint):
1. Put a publicly reachable machine (VPS, port-forwarded PC, or VPN peer) online with node mode on.
2. Both phones: enter that node address under "نود رله" and save.
3. Both phones: verify each other by QR and fingerprint comparison.
4. A sends to B while B's app is open. Expected route: `RELAY`.
5. Close B's app and repeat. Expected: a specific failure message, never silent loss.

## Next stage

**Phase 6.3** begins with the ADR-001 gate, then either go-libp2p for hole punching or relay-first plus
6.5 delivery guarantees. Given the zero-cost constraint, relay-first with store-and-forward is the
cheaper path; hole punching gives a better experience but needs a native toolchain.
