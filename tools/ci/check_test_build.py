# SPDX-License-Identifier: GPL-2.0-or-later
"""Test-only CI boundaries; never produces or publishes a release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import package_guard

CHECKOUT = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
JAVA = "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961"
WRAPPER = "080e30657661539701b66827b96eb0043191e0a7a73090e8a57bd6735e5af5c5"
DIST = "673d9776f303bc7048fc3329d232d6ebf1051b07893bd9d11616fad9a8673be0"
MAIN_API_URL = ("https://github.com/TroubledPerson/OpenComputersPlusPlus/releases/download/"
                "ocpp-dev33-close-onset-pre.3/"
                "OpenComputersPlusPlus-MC1.12.2-dev.33-close-onset-pre.3.jar")
MAIN_API_SHA = "01b8450c834809ead077b28080f48886526a4f266141e342bbc49a7ea7f1f3b6"
MAIN_API_BYTES = 25102877
INIT = """
gradle.taskGraph.whenReady { graph ->
    def allowed = ['generateIsolatedJsonJava','compileJsonJava','processJsonResources',
        'jsonClasses','compileApiJava','processApiResources','apiClasses','compileJava',
        'processResources','classes','compileTestJava','processTestResources','testClasses',
        'test','compilePrototypeJava','processPrototypeResources','prototypeClasses',
        'compilePrototypeTestJava','processPrototypeTestResources','prototypeTestClasses',
        'prototypeTest','check'] as Set
    graph.allTasks.each { task ->
        if (task instanceof org.gradle.jvm.tasks.Jar || !allowed.contains(task.name)) {
            throw new GradleException('Test-only CI refuses artifact task: ' + task.path)
        }
    }
}
"""


def require(value, message):
    if not value:
        raise ValueError(message)


def workflow_policy(workflow):
    require(set(workflow["on"]) == {"push", "pull_request", "workflow_dispatch"},
            "Unexpected workflow event")
    require(workflow["permissions"] == {"contents": "read"}, "Read-only token required")
    require(set(workflow["jobs"]) == {"offline"}, "Unexpected job")
    job = workflow["jobs"]["offline"]
    require(not job.get("permissions") and not job.get("secrets"), "Unexpected job access")
    require(job["runs-on"] == "ubuntu-24.04" and job["timeout-minutes"] <= 20,
            "Unexpected runner or timeout")
    actions = [s["uses"] for s in job["steps"] if "uses" in s]
    require(actions == [CHECKOUT, JAVA], "Only pinned checkout and Java setup allowed")
    require(job["steps"][0]["with"]["persist-credentials"] is False,
            "Checkout credentials must not persist")
    require(not any("secrets." in str(s) for s in job["steps"]), "Secrets are not needed")
    return True


def inventory_policy(entries):
    seen = set()
    for entry in entries:
        path = entry["path"]
        parts = path.split("/")
        require(path and "\\" not in path and ":" not in path and
                all(p not in ("", ".", "..") for p in parts), "Unsafe inventory path")
        require(parts[0] not in {"build", ".git", "verification", "deliverables",
                                "run", "logs", ".cache"}, "Private output in inventory")
        require(path not in seen, "Duplicate inventory entry")
        seen.add(path)
        require(not path.lower().endswith((".exe", ".dll", ".class", ".ogg", ".wav",
                                          ".zip", ".iso", ".qcow2", ".vmdk")) and
                (not path.lower().endswith(".jar") or
                 path == "gradle/wrapper/gradle-wrapper.jar"),
                "Unexpected binary inventory entry")
    require(bool(seen), "Empty source inventory")
    return True


def inputs():
    package_guard.source_lock(ROOT)
    inventory_policy(json.loads((ROOT / "SOURCE-PROVENANCE.json").read_text())["files"])
    decision = json.loads((ROOT / "LICENSE-DECISION.json").read_text())
    require(decision["confirmed"] and decision["spdx"] == "GPL-2.0-only",
            "Recorded licence decision changed")
    require(decision["publicBinaryReleaseCleared"] is False and
            decision["publicReleaseCleared"] is False and
            decision["indirectRuntimeReviewConfirmed"] is False,
            "Existing binary-publication gate changed")
    for path, expected in {
        "LICENSE": "aaf135472f81c5b4a0dca9367e5bb5e9750032b5bebe5442b36e4c0a47430df3",
        "gradle/wrapper/gradle-wrapper.jar": WRAPPER,
    }.items():
        require(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == expected,
                "Pinned input changed: " + path)
    workflow_policy(json.loads((ROOT / ".github/workflows/test.yml").read_text()))


def prepare(init):
    inputs()
    p = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    raw = p.read_text()
    values = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)
    require(values["distributionUrl"].replace("\\:", ":") ==
            "https://services.gradle.org/distributions/gradle-7.6.6-bin.zip",
            "Unexpected Gradle distribution")
    require(values.get("distributionSha256Sum", DIST) == DIST, "Distribution hash changed")
    # Only the disposable hosted checkout is modified, never committed wrapper bytes.
    if "distributionSha256Sum" not in values:
        p.write_text(raw + "\ndistributionSha256Sum=" + DIST + "\n")
    init.write_text(INIT)
    print("Pinned wrapper, distribution, JSON sources/notices and test-only policy verified.")


def report_counts(documents, minimum=98):
    total = 0
    require(bool(documents), "No JUnit reports")
    names = set()
    for raw in documents:
        suite = ET.fromstring(raw)
        require(suite.tag == "testsuite", "Unexpected JUnit report shape")
        count = int(suite.attrib["tests"])
        require(all(int(suite.attrib.get(k, "0")) == 0
                    for k in ("failures", "errors", "skipped")), "JUnit checks did not all pass")
        cases = suite.findall("testcase")
        require(count == len(cases) and not suite.findall(".//failure") and
                not suite.findall(".//error") and not suite.findall(".//skipped"),
                "JUnit counts disagree")
        require(suite.attrib["name"] not in names, "Duplicate suite")
        names.add(suite.attrib["name"])
        total += count
    require(total >= minimum, "Offline suite coverage fell below 98 tests")
    return total


def read_pinned(stream, expected_bytes, expected_sha):
    require(0 < expected_bytes <= 32 * 1024 * 1024, "Compile input size bound")
    data = bytearray()
    while True:
        limit = min(65536, expected_bytes + 1 - len(data))
        chunk = stream.read(limit)
        require(len(chunk) <= limit, "Transport exceeded bounded read")
        if not chunk:
            break
        data.extend(chunk)
        require(len(data) <= expected_bytes, "Oversized compile input")
    require(len(data) == expected_bytes, "Truncated compile input")
    require(hashlib.sha256(data).hexdigest() == expected_sha, "Compile input hash mismatch")
    return bytes(data)


def main_api():
    inputs()
    temp = Path(os.environ["RUNNER_TEMP"]).resolve()
    jar = temp / "ocvm-pinned-dev33-api.jar"
    require(temp.is_dir() and not jar.exists(), "Fresh runner compile input required")
    # Public, immutable release input; no credentials, native extraction or execution.
    with urllib.request.urlopen(MAIN_API_URL, timeout=60) as response:
        final = urllib.parse.urlparse(response.geturl())
        require(final.scheme == "https" and final.hostname in {
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"},
            "Unexpected compile-input redirect")
        size = response.headers.get("Content-Length")
        require(size is None or int(size) == MAIN_API_BYTES, "Unexpected compile-input length")
        data = read_pinned(response, MAIN_API_BYTES, MAIN_API_SHA)
    jar.write_bytes(data)
    support = ROOT / "build/classes/java/json"
    require(support.is_dir(), "Compile isolated JSON before the API boundary check")
    out = ROOT / "build/actual-main-api-compile"
    require(not out.exists(), "Fresh compile-only output required")
    out.mkdir()
    empty = out / "empty-sourcepath"
    empty.mkdir()
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    sources += sorted((ROOT / "prototype/src/main/java").rglob("*.java"))
    require(bool(sources), "No compile-boundary sources")
    javac = Path(os.environ["JAVA_HOME"]) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    # No API snapshot or earlier snapshot-compiled main classes enter this classpath.
    subprocess.run([str(javac), "-J-Xmx128m", "-J-XX:MaxMetaspaceSize=64m",
                    "-source", "8", "-target", "8", "-encoding", "UTF-8",
                    "-proc:none", "-implicit:none", "-sourcepath", str(empty),
                    "-cp", os.pathsep.join((str(jar), str(support))), "-d", str(out)]
                   + [str(p) for p in sources], check=True, timeout=90)
    classes = sorted(out.rglob("*.class"))
    require(0 < len(classes) <= 256, "Unexpected compile-boundary output")
    for path in classes:
        name = package_guard.class_name(path.read_bytes())
        require(name.startswith("li/cil/oc/vm/"), "Unexpected compile-boundary class")
    record = {"inputSHA256": MAIN_API_SHA, "inputBytes": MAIN_API_BYTES,
              "sourceFiles": len(sources), "java8Classes": len(classes),
              "snapshotClasspathIncluded": False, "annotationProcessing": False,
              "codeExecuted": False, "forgeCompiled": False}
    (ROOT / "build/actual-main-api-compile.json").write_text(json.dumps(record))
    print("PASS: actual dev33 API compile; %d sources; %d Java-8 classes; "
          "snapshots excluded; no addon/main/native code executed." % (len(sources), len(classes)))


def results():
    inputs()
    documents = []
    for task in ("test", "prototypeTest"):
        reports = sorted((ROOT / "build/test-results" / task).glob("TEST-*.xml"))
        require(bool(reports), "Missing reports for " + task)
        documents.extend(p.read_bytes() for p in reports)
    total = report_counts(documents)
    roots = [ROOT / "build/classes/java" / n for n in ("main", "json")]
    classes = package_guard.compiled_classes(roots)
    require(any(n.startswith("li/cil/oc/vm/internal/jsonjava20260814/") for n in classes),
            "Isolated JSON-java missing")
    require(not list((ROOT / "build").rglob("*.jar")), "Unexpected binary artifact produced")
    boundary = json.loads((ROOT / "build/actual-main-api-compile.json").read_text())
    require(boundary["inputSHA256"] == MAIN_API_SHA and
            boundary["snapshotClasspathIncluded"] is False and
            boundary["annotationProcessing"] is False and boundary["codeExecuted"] is False,
            "Actual-main API compile evidence missing or invalid")
    print("PASS: %d offline tests; %d Java-8 addon/isolated-JSON classes checked; no JAR." %
          (total, len(classes)))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## Test-only OCVMIntegration checks\n\n")
            f.write("- %d offline JUnit tests passed; no skipped tests.\n" % total)
            f.write("- %d compiled addon/isolated JSON-java classes passed Java 8/namespace/Gson checks.\n" % len(classes))
            f.write("- API snapshots/prototype are compile/test inputs, excluded from addon roots.\n")
            f.write("- Packaging fixtures and actual forbidden-task rejection passed.\n")
            f.write("- Binary-publication gate and licence notices retained. No JAR, upload or release.\n")
            f.write("- Actual pinned dev33 API compilation passed with snapshots and annotation processing excluded.\n")
            f.write("- That compile check does not execute main-mod Gson paths or prove runtime/linkage compatibility.\n")
            f.write("- Forge compilation and Minecraft/QEMU runtime tests are not run here.\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "main-api", "results"))
    parser.add_argument("--init", type=Path)
    args = parser.parse_args()
    if args.phase == "prepare":
        require(args.init is not None, "Explicit runner init-script path required")
        prepare(args.init)
    elif args.phase == "main-api":
        main_api()
    else:
        results()
