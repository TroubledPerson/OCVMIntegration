# OCVMIntegration

Experimental, optional OC++ VM Integration dependency source for Minecraft 1.12.2. The main [OpenComputers++](https://github.com/TroubledPerson/OpenComputersPlusPlus/tree/ocpp-dev33) mod remains MIT. This separate dependency uses GPL version 2 overall, with existing per-file grants preserved and JSON-java separately Public Domain. See [LICENSING.md](LICENSING.md).

This is a **vibe-coded mod, developed with AI assistance**. If you do not support AI-assisted development, it may be best to ignore this mod.

Direct staged Gson usage has been migrated to exact JSON-java 20260814 sources with an early bounded parser and private namespace packaging. The owner has confirmed the QEMU-matching GPLv2 scope; the migration does not relicense third-party components. Actual main-mod API code still uses Gson indirectly. See JSON-MIGRATION.md.

## What is included

- API1/API2 offline provider registration and bounded in-memory QMP transcript fixtures.
- Synthetic frame/input fixtures with authority, generation, timeout and memory limits.
- A separate, excluded-from-JAR Java prototype for manifest validation, versioned extraction and supervised no-guest control. Its tests use inert byte fixtures and injected memory peers.
- A separately compiled MIT API snapshot, so core/tests do not require the entire main project or its build output.

This is an experimental source repository, not a stable release or proof of a reproducible clean Gradle build. In-game registration remains OFFLINE; a successful fixture is not a running VM. No guest boot, live framebuffer, real disk attachment or host network capability is supplied. OpenOS CD support belongs to ordinary OC++ computers, not Tier 7 guest boot. The intended VM storage model remains one aggregate RAID virtual disk.

## Build boundaries

Use a 64-bit Java 8 JDK. The inherited Gradle wrapper selects Gradle 7.6.6. No Java installation, runtime acquisition or credential setup is automatic.

- `gradlew classes test prototypeTest`: build core/API and run offline suites.
- `gradlew forgeJar -PforgeApiJar=<Forge-1.12.2-14.23.5.2860-API-or-universal.jar>`: compile the Forge entrypoint and compile the entrypoint; ordinary JAR packaging retains its separate binary-qualification gate.
- `gradlew jar`: core library only, **not** a Minecraft-loadable addon.
- `python tools/verify.py --help`: direct offline javac/JUnit route using explicitly supplied existing dependency JARs. It performs no dependency acquisition or native launch.

The API snapshot is compile/test-only; the Forge addon expects OC++ dev.20 or newer, with dev.33 as this project's accepted source baseline. Install at most one OCVMIntegration variant; it uses the same mod ID as the earlier offline/simulated dependency. This preparation does not deploy into any instance.

The addon packages a source-compiled private JSON-java namespace rather than relying on a Minecraft JSON library. Test-only external dependencies are JUnit 4.13.2 and Hamcrest 1.3. Compile/test API snapshots remain excluded from addon JARs; the real main API still uses Gson. No private cache binaries are exported here.

## Native runtime is excluded

No QEMU/native payload or pinned private bundle is included. The Java prototype retains a legacy 4.0.92 greeting profile for fixture compatibility and cannot load a packaged runtime in this source-only tree. It is not qualification of the newer candidate.

The latest separate qualification work inspected MSYS2 QEMU 11.1.3-1 binary metadata and a matching PKGBUILD. Upstream tarball/signature, three auxiliary inputs, selected DLL source and notices remain unresolved. Do not claim complete corresponding source or redistribute the private runtime on that basis. Windows/Linux/macOS and x86_64/Arm64 remain separate future packaging/test targets; no cross-platform native support is certified here.

## Verification

See [TESTING.md](TESTING.md). Original historical test results are not a fresh clean standalone build result. Current admission and executed/not-run evidence is kept outside this prospective public tree. Never publish build directories, fixture outputs, reports containing machine paths, native assets or private evidence.

## Qualified private test artifact

The separate `OCVMIntegration-0.3.0-dev.3-json-test.1-private.jar` has SHA-256
`4d745cdff0159d604e7a48430f0753ca6be3777664b78677fa1397dc322af84a`.
Its source migration passed 98 offline tests and Java 8/actual-main-API/Forge
compilation. The owner reported boot/startup and the suggested mock checks pass
for that filename; no installed-byte hash or per-check logs were supplied.
Those reports are not agent-executed game tests, new QMP-path coverage or evidence
of a real guest/QEMU backend. The older simulated dev.3 report is separate.

This repository publishes source only. No addon binary, native prototype payload,
installer, firmware, guest image or main-mod audio is included. Build/test
outputs and private records are excluded. Source publication does not complete
the separate binary-linkage/native redistribution qualification. Existing code
and component licence grants are unchanged by project qualification gates.
