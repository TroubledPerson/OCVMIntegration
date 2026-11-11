# JSON-java migration preparation

## Qualified source input

The selected coordinate is `org.json:json:20260814`. The official tag is commit `6c140480797ad70a41df84bc8ebab405ca44656b`. Its POM declares source/target 1.8, Public Domain licensing and only test-scoped dependencies. All 26 vendored Java files must match that commit's Git blob IDs; SOURCE-MANIFEST.json also pins SHA-256 per file.

The direct Maven checksum/artifact attempt stopped on an expired-certificate validation error before any artifact was downloaded. It was not retried through another client or mirror. The independently documented official GitHub release sources are the qualified input used here. No Maven binary hash or locally tested Maven artifact is claimed. See dependency provenance.

Upstream declares **Public Domain** for this exact release. This is not a relabelling as MIT, CC0 or Unlicense, nor a jurisdiction-independent legal guarantee. Older JSON.org releases with the historical Good, not Evil restriction are not used.

## Parsing boundary

BoundedJson runs an independently written grammar/resource preflight before constructing any JSON-java JSONObject/JSONArray. It bounds bytes/chars first, counts nodes before descending, enforces depth/member/array limits before accepting each child, checks decoded key/string lengths while scanning, rejects duplicate decoded keys, validates JSON escapes/control characters, and checks integer lexical syntax and Long range before materialization. It does not copy Gson implementation code.

Only after preflight succeeds does JSON-java run with strict mode, duplicate overwrite disabled and a depth guard. Results are normalized to Maps/Lists, String, Boolean, null and Long, maintaining the public QMP/control data types without relying on coercive getters.

QMP retains: 16 KiB complete line including CRLF, 256 KiB lifetime, depth 8 (root=0), 256 nodes, 32 object members/array elements, 80 UTF-16-unit keys and 4096-unit strings. Legacy QMP and manifest parsers accepted -0 as integer zero; that explicit compatibility is retained. The unsigned CONTROL profile rejects all signed numbers including -0. Decimals, exponents, leading zeros, plus signs, overflow and numeric strings in integer fields are rejected.

Escaped UTF-16 surrogate code units retain the previous string behavior; malformed raw UTF-8 is rejected. A literal malformed Java String cannot be encoded silently with replacement characters. Manifest snapshots retain their larger character/depth/array policies; they now use the shared early validator, with a finite node/member budget implied by the existing 64 KiB input bound.

## Changes to the prototype

NoGuestSupervisor now uses bounded strict parsing and typed field access. Duplicate/escaped duplicate fields, unknown fields, coercive numeric/boolean strings and trailing data are rejected. Its owned-process stdout decoder is strict UTF-8 instead of replacement decoding. These are **new hardening changes**, not protections claimed for the old tree-access implementation.

The legacy version gate, fixed capabilities/status/quit sequence, admission checks, 15-second deadline, backward-clock rejection, observed clean exit and cleanup remain. Tests use memory peers only; ProcessLauncher is never invoked.

## Runtime packaging and real main-mod boundary

The build verifies the 26 source hashes, relocates only the package prefix to `li.cil.oc.vm.internal.jsonjava20260814`, and compiles those Java sources with the shared adapter. Both core and Forge JAR tasks include that isolated output and the Public Domain/provenance notices. No external org.json classes are assumed to exist in Minecraft. This source plan was compiled and included in the qualified private JSON-test JAR; source publication does not rerun its complete build.

The core library JAR is not an installable Forge addon. The separate forgeJar task adds the Forge entrypoint; it still requires an explicitly supplied Forge API JAR and unresolved packaging/licence gates.

**Actual OC++ remains unchanged.** Staged ProtocolV2 and VmManifest copies retain their public signatures and are excluded from addon JARs. Their private parsing implementation now differs. QmpFixtureController calls ProtocolV2.decode at runtime, so the actual main-mod implementation still indirectly uses Gson. Unit tests against the migrated snapshot cannot establish behavior or licence closure for that unchanged production path. No API class is shadowed or bundled to conceal this dependency.

## Validation status

Old suites are retained and migrated away from Gson; focused early-allocation, grammar/UTF-8, numeric and exact-bound tests were added. After the earlier stopped admission, the final migration passed Java 8 compilation, all 98 offline tests and actual-main-API/Forge compile checks. The owner later reported startup/mock success for the explicitly named private JSON-test JAR. No new full Java/Gradle build or Minecraft/QEMU execution is inferred from this source publication. GPLv2 scope and public source publication are now authorized; separate binary qualification remains gated.
