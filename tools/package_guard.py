"""Fail-closed dependency package checks. Python stdlib only; never loads Java code."""
import argparse
import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path

MAX_ARCHIVE = 16 * 1024 * 1024
MAX_EXPANDED = 32 * 1024 * 1024
MAX_ENTRIES = 1024
MAX_MEMBER = 1024 * 1024
MANIFEST_SHA = '93924452466635bbac9dbc7c35672e3bcb7bb3a88802d6633c27ada330ce5a77'
LICENSE_SHA = '86cc84f1773e3318ba26fd61b3ae508a167df38b361695e105f6e499e7a2736f'
NOTICE_SHA = '7d1bca0fda85306ad2034af0b43808ffcb458cf0ceb2ea6aa15ee95528711dc3'
GSON_NAMES = frozenset(('Gson', 'GsonBuilder', 'JsonReader', 'JsonWriter',
                        'TypeAdapter', 'TypeToken', 'LinkedTreeMap'))
LICENSE_PATHS = ('META-INF/licenses/JSON-java-LICENSE.txt',
                 'META-INF/ocvmintegration-licenses/JSON-java-UPSTREAM-LICENSE.txt')
NOTICE_PATHS = ('META-INF/licenses/JSON-java-20260814.txt',
                'META-INF/ocvmintegration-licenses/JSON-java-20260814.txt')


class PackageError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise PackageError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def class_name(data):
    """Read this_class, not referenced classes (external references are permitted)."""
    require(len(data) >= 10 and data[:4] == b'\xca\xfe\xba\xbe', 'Invalid class header')
    require(45 <= struct.unpack_from('>H', data, 6)[0] <= 52, 'Not Java 8 bytecode')
    count = struct.unpack_from('>H', data, 8)[0]
    pool, offset, index = {}, 10, 1
    try:
        while index < count:
            tag = data[offset]
            offset += 1
            if tag == 1:
                size = struct.unpack_from('>H', data, offset)[0]
                offset += 2
                value = data[offset:offset + size]
                require(len(value) == size, 'Truncated UTF8 constant')
                pool[index] = value
                offset += size
            elif tag == 7:
                pool[index] = struct.unpack_from('>H', data, offset)[0]
                offset += 2
            elif tag in (3, 4, 9, 10, 11, 12, 18):
                offset += 4
            elif tag in (5, 6):
                offset += 8
                index += 1
            elif tag in (8, 16):
                offset += 2
            elif tag == 15:
                offset += 3
            else:
                raise PackageError('Unsupported class constant tag')
            index += 1
        this_class = struct.unpack_from('>H', data, offset + 2)[0]
        # All permitted project/package identifiers are ASCII; Java modified UTF8
        # string constants are deliberately not decoded or interpreted.
        return pool[pool[this_class]].decode('ascii')
    except (IndexError, KeyError, struct.error, UnicodeError, TypeError) as exc:
        raise PackageError('Malformed class constant pool') from exc


def check_class(name, data):
    internal = class_name(data)
    require(name == internal + '.class', 'Class entry/internal name mismatch: ' + name)
    simple = internal.rsplit('/', 1)[-1].split('$', 1)[0]
    require(not internal.startswith('com/google/gson/') and
            '/gson/' not in internal and simple not in GSON_NAMES,
            'Bundled ordinary/relocated Gson class: ' + name)
    require(internal.startswith('li/cil/oc/vm/') and
            not internal.startswith('li/cil/oc/vm/embedded/'),
            'Class outside declared addon/isolated JSON namespaces: ' + name)


def source_lock(project):
    vendor = project / 'vendor/json-java-20260814'
    raw = (vendor / 'SOURCE-MANIFEST.json').read_bytes()
    require(sha(raw) == MANIFEST_SHA, 'JSON-java source manifest changed')
    manifest = json.loads(raw)
    require(manifest['version'] == '20260814' and
            manifest['commit'] == '6c140480797ad70a41df84bc8ebab405ca44656b',
            'JSON-java release changed')
    actual = {p.relative_to(vendor / 'src').as_posix()
              for p in (vendor / 'src').rglob('*.java')}
    require(actual == {e['path'] for e in manifest['files']}, 'JSON-java source set changed')
    for entry in manifest['files']:
        require(sha((vendor / 'src' / entry['path']).read_bytes()) == entry['sha256'],
                'JSON-java source changed: ' + entry['path'])
    require(sha((vendor / 'LICENSE').read_bytes()) == LICENSE_SHA,
            'Upstream Public Domain declaration changed')
    require(sha((project / 'LICENSES/JSON-java-20260814.txt').read_bytes()) == NOTICE_SHA,
            'JSON-java notice changed')


def compiled_classes(roots):
    require(bool(roots), 'Explicit reviewed compilation roots required')
    expected = {}
    for root in roots:
        root = Path(root)
        require(root.is_dir(), 'Missing compilation root: ' + str(root))
        files = sorted(root.rglob('*.class'))
        require(bool(files), 'Empty compilation root: ' + str(root))
        for path in files:
            require(not path.is_symlink() and path.stat().st_size <= MAX_MEMBER,
                    'Invalid compilation output')
            name, data = path.relative_to(root).as_posix(), path.read_bytes()
            require(name not in expected, 'Duplicate compilation output: ' + name)
            check_class(name, data)
            expected[name] = data
            require(len(expected) <= MAX_ENTRIES, 'Too many compiled classes')
    require(sum(map(len, expected.values())) <= MAX_EXPANDED, 'Compilation output too large')
    return expected


def validate(data, expected, project):
    """Validate a complete package against caller-reviewed compiled class bytes."""
    source_lock(Path(project))
    require(0 < len(data) <= MAX_ARCHIVE, 'Archive size bound exceeded')
    require(bool(expected), 'No declared compiled classes')
    for name, payload in expected.items():
        check_class(name, payload)
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            require(len(infos) <= MAX_ENTRIES, 'Archive entry bound exceeded')
            require(len({e.filename for e in infos}) == len(infos), 'Duplicate archive entry')
            require(sum(e.file_size for e in infos) <= MAX_EXPANDED, 'Expansion bound exceeded')
            members = {}
            for entry in infos:
                name = entry.filename
                require(name == entry.orig_filename and name and
                        '\\' not in name and ':' not in name and
                        not name.startswith('/') and
                        all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')),
                        'Unsafe archive path')
                require(not entry.flag_bits & 1 and entry.file_size <= MAX_MEMBER,
                        'Encrypted/oversized archive member')
                require((entry.external_attr >> 16) & 0o170000 != 0o120000,
                        'Archive symlink rejected')
                if entry.is_dir():
                    continue
                payload = archive.read(entry)  # bounded above; also verifies CRC
                require(not name.lower().endswith(('.jar', '.zip', '.war', '.ear', '.jmod')) and
                        payload[:4] not in (b'PK\x03\x04', b'PK\x05\x06', b'PK\x07\x08') and
                        not zipfile.is_zipfile(io.BytesIO(payload)),
                        'Unexpected nested archive: ' + name)
                if name.endswith('.class'):
                    check_class(name, payload)
                else:
                    require(not payload.startswith(b'\xca\xfe\xba\xbe'),
                            'Class bytes disguised as a resource: ' + name)
                    # Current source-only simulated addon resources are text.
                    # Reject renamed binary/compressed payloads without expanding
                    # them; native/runtime payloads require a different review.
                    try:
                        text = payload.decode('utf-8')
                    except UnicodeError as exc:
                        raise PackageError('Unexpected binary resource: ' + name) from exc
                    require(all(ord(c) >= 32 or c in '\t\r\n' for c in text),
                            'Unexpected binary/control resource: ' + name)
                members[name] = payload
            classes = {n: p for n, p in members.items() if n.endswith('.class')}
            require(classes == expected, 'Packaged class set/bytes differ from declared outputs')
            require(any(n.startswith('li/cil/oc/vm/internal/jsonjava20260814/')
                        for n in classes), 'Isolated JSON-java missing')
            for paths, digest in ((LICENSE_PATHS, LICENSE_SHA), (NOTICE_PATHS, NOTICE_SHA)):
                present = [members[n] for n in paths if n in members]
                require(present and all(sha(p) == digest for p in present),
                        'Missing/changed JSON-java Public Domain licence or notice')
            return {'classes': len(classes), 'entries': len(members),
                    'sha256': sha(data), 'bytes': len(data)}
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError) as exc:
        raise PackageError('Invalid/unsupported ZIP: ' + str(exc)) from exc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--class-root', action='append', type=Path, required=True)
    parser.add_argument('--project-root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    require(args.archive.stat().st_size <= MAX_ARCHIVE, 'Archive size bound exceeded')
    print(json.dumps(validate(args.archive.read_bytes(), compiled_classes(args.class_root),
                              args.project_root), sort_keys=True))


if __name__ == '__main__':
    main()
