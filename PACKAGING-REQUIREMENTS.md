# Dependency packaging requirements

The owner requires that OCVMIntegration must not bundle Gson, including ordinary,
shaded/relocated or nested copies. JSON-java 20260814 remains isolated under
`li.cil.oc.vm.internal.jsonjava20260814` with its exact upstream Public Domain
declaration and component notice. Other component grants remain unchanged.

`tools/package_guard.py` is the shared fail-closed validator for core/Forge Gradle
JARs and the separate private-test packager. Supply explicit, reviewed compilation
output roots. It checks each class's internal name and Java 8 version, rejects
Gson package/implementation names (case-sensitive), and requires the exact class
set and bytes from those roots. API snapshots and native prototypes are excluded.
Unexpected nested archives, including renamed ZIPs, unsafe paths, duplicates,
oversized content and missing/altered JSON-java notices are rejected. The pinned
26 upstream sources and source manifest are also checked.
Current non-class resources must be UTF-8 text without binary control bytes;
renamed class/compressed/native payloads are not accepted as resources.

Future packaging paths must call this validator before declaring success. Gradle
requires `-PocvmPython=<existing Python 3 executable>`; it does not install Python.
Example validation, with paths appropriate to that artifact:

```
python -B tools/package_guard.py artifact.jar --class-root build/classes/java/main --class-root build/classes/java/json
python -B tools/test_package_guard.py
```

The compilation roots are a trust boundary, not an arbitrary input to accept from
an untrusted artifact. Exact byte binding detects additional/renamed shading at
packaging time; it is not a proof against deliberately disguised implementations
already introduced into reviewed source/compilation outputs. References to an
external class are not bundled implementations and are not classified as such.
Conditional calls to the actual main mod's `ProtocolV2.decode` can still use its
external Gson. This requirement does not migrate the main mod or settle legal
compatibility questions. No runtime/QEMU capability follows from these checks.

The current private JSON-test JAR is preserved. This source publication does not rebuild
it. Public source publication is authorized; binary-release qualification remains
separate. That project status adds no restriction to component licence grants.
