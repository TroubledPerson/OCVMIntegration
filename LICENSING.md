# OCVMIntegration: QEMU-matching GPL version 2 scope

The owner selected the licence used by QEMU, with JSON-java remaining Public Domain.
The overall OCVMIntegration project is under GNU GPL version 2. LICENSE contains
the unchanged GPLv2 text. As in QEMU's root policy, project-owned source files with
no separate licence notice permit GPLv2 or, at the recipient's option, a later
version. Explicit file notices remain authoritative. Existing GPL-2.0-only
prototype headers are unchanged; the prototype is excluded from the test JAR.
Existing MIT grants/copyright and permission notices are preserved, not revoked.
No third-party file is upgraded or relabelled by this overall project declaration.

JSON-java 20260814 retains its exact upstream "Public Domain." declaration,
source hashes and relocation disclosure. It is not MIT, CC0 or ordinary Gson.
The MIT API snapshots are compile/test-only and excluded from the addon JAR.
Gradle/JUnit/Hamcrest are build/test inputs and are not shipped runtime libraries.
No QEMU executable, DLL, firmware, installer, VM image or main-mod audio is included.

## Public source and binary qualification

The owner authorized this public source repository and the separate private test.
Ordinary binary-release tasks remain qualification-gated. No native payload or
GitHub binary release is created by source publication.
This is not a claim of final redistribution/linking clearance. The real main mod
still uses ordinary Apache-2.0 Gson: QmpFixtureController.submit calls the actual
ProtocolV2.decode, and Provider2 inherits openOffline -> OfflineAdapterV2.request
-> that decoder. Registration alone does not parse JSON. Main VmManifest also
uses Gson in main-owned manager/mock code; it is not directly called by addon
production source. The migrated API snapshots do not replace installed main code.
Engineering review has identified these paths; unresolved legal uncertainty is
not labelled proven incompatibility. Public source publication is authorized. This is not final binary-distribution
clearance or a legal compatibility determination. These project-state gates add
no restriction to the GPL, MIT or Public Domain grants.

Overall QEMU licence reference: https://www.qemu.org/docs/master/about/license.html
Root-policy reference retained from qualification: https://gitlab.com/qemu-project/qemu/-/raw/v11.1.3/LICENSE
JSON reference: https://github.com/stleary/JSON-java/blob/20260814/LICENSE
