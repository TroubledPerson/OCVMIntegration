# Standalone offline tests

Use Java 8 and explicitly supplied JUnit 4.13.2/Hamcrest 1.3 JARs:

`python tools/verify.py --java-home <existing-jdk8> --junit <junit-4.13.2.jar> --hamcrest <hamcrest-core-1.3.jar>`

The verifier hash-checks the exact 26 JSON-java sources, generates isolated package names and compiles this staged tree. It acquires nothing. Optional `--forge-api <existing-forge-api.jar>` also compiles the Forge entrypoint. No QEMU or Minecraft process is started.

The original 68 tests remain; 30 added methods bring the total to 98. Added tests cover preflight-before-materialization, raw UTF-8, decoded duplicate keys, unknown/trailing data, controls/escapes, integer lexical/range rules, legacy negative zero versus unsigned control, string/key/array/object/depth/node limits and limit+1, exact line/lifetime caps, fragmented chunks and failure cleanup. Snapshot control/manifest contracts and stricter prototype replies have additional coverage.

The final migration subsequently passed Java 8 compilation and all 98 offline tests, plus actual-main-API and Forge compile checks. Those historical results are separate from the owner's file-specific startup/mock reports. This packaging-guard change does not rerun Java tests or establish any additional runtime coverage.

Gradle `classes test prototypeTest` builds/tests the same isolated source sets when resources permit. JAR packaging remains licence-gated. API snapshots are excluded from production artifacts and do not replace the real OC++ API; passing migrated snapshot tests would not prove that unchanged Gson-based main-mod runtime path.

All prototype tests use inert byte fixtures and injected memory peers. ProcessLauncher is never called. Generated fixtures and reports stay under build/ and must not be published. No live audio, GL, guest, real disk, networking or external IPC test is included.

Optional --main-api <unchanged-main-JAR> adds a compile-only compatibility check against actual OC++ classes. It does not execute that Gson-based runtime path or replace the snapshot test coverage.

Run `python -B tools/test_package_guard.py` for in-memory packaging fixtures. See
PACKAGING-REQUIREMENTS.md for mandatory package validation and its source/output
trust boundary. These tests neither execute Java nor create an installable JAR.

## GitHub Actions: test-only checks

The workflow runs on pushes and pull requests to main, and manual dispatch.
It uses Ubuntu 24.04, pinned checkout/Java setup actions, Java 8 and one Gradle
worker. The Gradle distribution checksum is verified in the disposable runner
checkout; committed wrapper bytes are preserved.

The workflow compiles core/API/isolated JSON and prototype sources, runs classes
and check (including the 98 existing offline JUnit tests), runs the 23 packaging
fixtures plus CI-policy fixtures, and inspects actual compiled class names and
Java 8 format. Missing reports, failures, errors, skipped tests or reduced
coverage fail CI. An actual dry-run invocation proves JAR tasks are refused by
the execution-graph allowlist before task actions can run.

The token is read-only; checkout does not persist credentials. There are no
artifact uploads, publishing commands, release jobs or native launches. Existing
binary-publication gates and licence notices remain unchanged. Results appear
in logs and the job summary, with no distributable JAR produced.

Offline runtime tests use the compile/test API snapshot. A separate compile-only
check fetches the immutable dev33 pre.3 JAR into runner temporary storage, enforces
its exact 25,102,877-byte size and SHA-256, and compiles addon/prototype sources
against that real API plus isolated JSON support. Snapshots are excluded from the
classpath. Annotation processing and implicit source compilation are disabled.
The input JAR is neither executed nor uploaded, and no addon JAR is produced.

This verifies a real compile boundary, not execution of main-mod Gson paths,
runtime/linkage compatibility or legal clearance. Forge compilation and
Minecraft/QEMU/guest behavior remain separate qualification steps.
