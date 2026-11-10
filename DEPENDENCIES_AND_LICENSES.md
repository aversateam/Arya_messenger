# Arya Messenger — Dependencies and Licences (Phase 6)

Last updated: 2026-11-10

Phase 6.1/6.2 adds **no runtime dependency** to `:app`. Everything below is either already present,
evaluated for a future stage, or used only by a spike module.

## Current runtime dependencies

| Dependency | Version | Licence | Purpose |
|---|---|---|---|
| androidx.core:core-ktx | 1.17.0 | Apache-2.0 | Android primitives |
| androidx.activity:activity-compose | 1.11.0 | Apache-2.0 | Activity + Compose entry point |
| androidx.lifecycle:lifecycle-* | 2.9.4 | Apache-2.0 | Lifecycle-aware state |
| androidx.compose (BOM 2025.10.00) | — | Apache-2.0 | UI |
| androidx.navigation:navigation-compose | 2.9.5 | Apache-2.0 | Navigation |
| androidx.room:room-runtime / room-ktx | 2.8.3 | Apache-2.0 | Local database |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.10.2 | Apache-2.0 | Concurrency |
| com.google.zxing:core | 3.5.4 | Apache-2.0 | QR encode/decode |
| com.journeyapps:zxing-android-embedded | 4.3.0 | Apache-2.0 | QR capture UI |
| *(emoji assets adapted from Telegram)* | — | **GPLv2-or-later** | Emoji pack — pre-existing, see README |

Build-only additions in this phase:

| Dependency | Scope | Licence |
|---|---|---|
| org.jetbrains.kotlinx:kotlinx-coroutines-test 1.10.2 | test | Apache-2.0 |
| androidx.room:room-testing 2.8.3 | androidTest | Apache-2.0 |

## Evaluated, not adopted

### jvm-libp2p (`io.libp2p`)
- Dual **MIT / Apache-2.0**. Actively released (latest Oct 2026), Android sample app included.
- Production-grade: TCP, Noise, multistream-select, mplex, ping, identify.
- Beta: QUIC, WebSocket, TLS, yamux, AutoNAT, Circuit Relay v2, mDNS, floodsub.
- **Absent:** hole punching, Kademlia DHT, bootstrap, rendezvous, WebRTC, WebTransport.
- Transitive: Netty (Apache-2.0), BouncyCastle (MIT). Android hazards to verify: duplicate
  `org.bouncycastle` classes against the platform provider, Netty `META-INF/INDEX.LIST` packaging.
- Verdict: cannot deliver the target architecture alone; kept behind the ADR-001 gate.

### kwik (jvm-libp2p's QUIC backend)
- **LGPL-3.0** — this is a distribution obligation, not a permissive licence. It also relies on
  per-OS native bindings, so Android ABI coverage must be proven before QUIC can be claimed.
- Verdict: treat QUIC on Android as unproven until the gate runs.

### go-libp2p via gomobile
- MIT/Apache-2.0. Complete feature set (QUIC, Noise, TCP, DHT, AutoNAT, hole punching, Circuit
  Relay v2, rendezvous).
- Cost: Go toolchain + `gomobile` + Android NDK; output is an out-of-tree `.aar` with larger ABI
  payload; callback-based API ergonomics.
- Verdict: the only path to real hole punching and DHT; optional because it is not free to build.

### End-to-end messaging protocols (Phase 6.6 input)
- **libsignal** — **AGPL-3.0. Not usable** for this project as licensed. Using it would force the
  whole app to AGPL.
- **Olm / Megolm (Matrix)** — Apache-2.0. Double Ratchet-style 1:1 plus group ratchet; mature
  Android bindings exist. Leading candidate.
- **OpenMLS (RFC 9420)** — MIT, Rust, requires JNI. Candidate for groups at 6.6, heavier build.
- **PQXDH** — post-quantum extension to X3DH; no mature Android JVM implementation exists.
  Tracked, not adopted.

## Rules going forward

1. No dependency is added without resolving it in this build and passing `assembleDebug`.
2. No custom cryptographic protocol. Only standard schemes, using maintained implementations.
3. Any LGPL/GPL component is called out here before it ships, because it constrains distribution.
4. Relay and bootstrap endpoints are configuration, never compiled-in constants.
