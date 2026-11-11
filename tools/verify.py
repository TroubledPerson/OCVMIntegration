"""Compile this standalone tree offline with pinned, isolated JSON-java sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--java-home", required=True, type=Path)
p.add_argument("--junit", required=True, type=Path)
p.add_argument("--hamcrest", required=True, type=Path)
p.add_argument("--forge-api", type=Path)
p.add_argument("--main-api", type=Path, help="Optional unchanged main-mod JAR for compile-only ABI checks")
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
os.chdir(root)
suffix = ".exe" if os.name == "nt" else ""
javac = args.java_home.resolve() / "bin" / ("javac" + suffix)
java = args.java_home.resolve() / "bin" / ("java" + suffix)
for item in [javac, java, args.junit, args.hamcrest]:
    if not item.is_file():
        p.error("An explicitly supplied tool/dependency file is missing.")
jars = [str(v.resolve()) for v in [args.junit, args.hamcrest]]
out = root / "build" / "offline-check"
out.mkdir(parents=True, exist_ok=True)
vendor = root / "vendor" / "json-java-20260814"
manifest = json.loads((vendor / "SOURCE-MANIFEST.json").read_text(encoding="utf-8"))
if manifest["version"] != "20260814" or manifest["commit"] != "6c140480797ad70a41df84bc8ebab405ca44656b":
    raise RuntimeError("Unexpected JSON-java release")
expected = {e["path"] for e in manifest["files"]}
actual = {v.relative_to(vendor / "src").as_posix() for v in (vendor / "src").rglob("*.java")}
if actual != expected:
    raise RuntimeError("Unexpected JSON-java source set")
generated = out / "generated-json"
generated.mkdir(exist_ok=True)
relocated = []
for entry in manifest["files"]:
    source = vendor / "src" / entry["path"]
    data = source.read_bytes()
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise RuntimeError("JSON-java source identity mismatch")
    target = generated / entry["path"].replace("org/json/", "li/cil/oc/vm/internal/jsonjava20260814/")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(data.decode("utf-8").replace("org.json", "li.cil.oc.vm.internal.jsonjava20260814"), encoding="utf-8")
    relocated.append(target)

def compile_set(name, sources, classpath):
    target = out / name
    target.mkdir(exist_ok=True)
    if isinstance(sources, str):
        sources = sorted(Path(sources).rglob("*.java"))
    if not sources:
        raise RuntimeError("Source set is empty: " + name)
    subprocess.run(
        [str(javac), "-J-Xmx128m", "-J-XX:MaxMetaspaceSize=64m",
         "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-cp", os.pathsep.join(classpath), "-d", str(target)]
        + [str(v) for v in sources], check=True)
    return str(target)

support = compile_set("json", relocated + sorted(Path("shared/src/main/java").rglob("*.java")), [])
api = compile_set("api", "api/src/main/java", [support])
main = compile_set("main", "src/main/java", [api, support])
tests = compile_set("test", "src/test/java", [api, main, support] + jars)
prototype = compile_set("prototype", "prototype/src/main/java", [api, main, support])
prototype_tests = compile_set("prototype-test", "prototype/src/test/java", [api, main, prototype, support] + jars)
fixtures = out / "inert-fixtures"
fixtures.mkdir(exist_ok=True)
cp = os.pathsep.join([api, main, tests, prototype, prototype_tests, support] + jars)
subprocess.run([
    str(java), "-Xmx128m", "-XX:MaxMetaspaceSize=64m", "-Dfile.encoding=UTF-8",
    "-Docpp.embedded.fixtureRoot=" + str(fixtures),
    "-cp", cp, "org.junit.runner.JUnitCore",
    "li.cil.oc.vm.json.BoundedJsonTest",
    "li.cil.oc.vm.json.WireContractTest",
    "li.cil.oc.vm.simulated.QmpBoundaryRegressionTest",
    "li.cil.oc.vm.simulated.QmpCodecTest",
    "li.cil.oc.vm.simulated.QmpFixtureTest",
    "li.cil.oc.vm.embedded.EmbeddedRuntimeTest",
    "li.cil.oc.vm.embedded.NoGuestSupervisorTest",
    "li.cil.oc.vm.embedded.NoGuestSupervisorStrictTest"], check=True)
if args.main_api:
    if not args.main_api.is_file():
        p.error("The explicitly supplied main API JAR is missing.")
    compile_set("main-against-real-api", "src/main/java", [str(args.main_api.resolve()), support])
    compile_set("prototype-against-real-api", "prototype/src/main/java", [str(args.main_api.resolve()), support])
if args.forge_api:
    if not args.forge_api.is_file():
        p.error("The explicitly supplied Forge API JAR is missing.")
    compile_set("forge", "forge/src/main/java", [api, main, support, str(args.forge_api.resolve())])
print("Offline suites completed; no QEMU or Minecraft process was launched. No binary release was produced.")
