"""In-memory package fixtures; no Java execution, build, archive writes or downloads."""
import io
import struct
import unittest
import warnings
import zipfile
from pathlib import Path
import package_guard as guard

PROJECT = Path(__file__).resolve().parents[1]


def klass(name, reference='java/lang/Object'):
    def utf(s):
        b = s.encode('ascii')
        return b'\x01' + struct.pack('>H', len(b)) + b
    return (b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 52, 5) +
            utf(name) + b'\x07\x00\x01' + utf(reference) + b'\x07\x00\x03' +
            struct.pack('>HHHHHHH', 0x21, 2, 4, 0, 0, 0, 0))


def archive(members, extra=()):
    out = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
            for n, p in list(members.items()) + list(extra):
                info = zipfile.ZipInfo(n)
                # Windows ZipInfo normalizes backslashes at construction. Keep
                # malformed fixture names verbatim to exercise the reader.
                info.filename = n
                z.writestr(info, p)
    return out.getvalue()


class PackageGuardTests(unittest.TestCase):
    def setUp(self):
        name = 'li/cil/oc/vm/internal/jsonjava20260814/JSONWriter'
        self.expected = {name + '.class': klass(name)}
        self.members = dict(self.expected)
        self.members[guard.LICENSE_PATHS[0]] = (PROJECT / 'vendor/json-java-20260814/LICENSE').read_bytes()
        self.members[guard.NOTICE_PATHS[0]] = (PROJECT / 'LICENSES/JSON-java-20260814.txt').read_bytes()

    def check(self):
        return guard.validate(archive(self.members), self.expected, PROJECT)

    def reject(self):
        with self.assertRaises(guard.PackageError):
            self.check()

    def test_positive_isolated_uppercase_JSONWriter(self):
        self.assertEqual(self.check()['classes'], 1)

    def test_positive_external_reference_is_not_bundling(self):
        n = 'li/cil/oc/vm/ExternalReference.class'
        self.members[n] = self.expected[n] = klass(n[:-6], 'com/google/gson/Gson')
        self.check()

    def test_positive_gradle_notice_locations(self):
        for paths in (guard.LICENSE_PATHS, guard.NOTICE_PATHS):
            self.members[paths[1]] = self.members.pop(paths[0])
        self.check()

    def test_ordinary_gson_even_if_declared(self):
        n = 'com/google/gson/Gson.class'
        self.members[n] = self.expected[n] = klass(n[:-6])
        self.reject()

    def test_relocated_gson_even_inside_declared_namespace(self):
        for base in sorted(guard.GSON_NAMES):
            with self.subTest(base=base):
                n = 'li/cil/oc/vm/shaded/' + base + '.class'
                self.members[n] = self.expected[n] = klass(n[:-6])
                self.reject()
                del self.members[n], self.expected[n]

    def test_arbitrary_renamed_class_injection(self):
        n = 'li/cil/oc/vm/SeeminglyInnocent.class'
        self.members[n] = klass(n[:-6])
        self.reject()

    def test_changed_compiled_bytes(self):
        n = next(iter(self.expected))
        self.members[n] += b'changed'
        self.reject()

    def test_missing_compiled_class(self):
        del self.members[next(iter(self.expected))]
        self.reject()

    def test_entry_name_cannot_disguise_class(self):
        self.members['li/cil/oc/vm/Disguised.class'] = self.members.pop(next(iter(self.expected)))
        self.reject()

    def test_class_bytes_disguised_as_resource(self):
        self.members['hidden.bin'] = klass('com/google/gson/Gson')
        self.reject()

    def test_compressed_binary_disguised_as_text(self):
        import gzip
        self.members['notice.txt'] = gzip.compress(b'concealed archive')
        self.reject()

    def test_api_and_prototype_not_allowed(self):
        for n in ('li/cil/oc/api/vm/Transport', 'li/cil/oc/vm/embedded/Native'):
            with self.subTest(name=n):
                with self.assertRaises(guard.PackageError):
                    guard.check_class(n + '.class', klass(n))

    def test_nested_archive_extensions(self):
        for ext in ('.jar', '.JAR', '.zip', '.war', '.ear', '.jmod'):
            with self.subTest(ext=ext):
                self.members['dependency' + ext] = b'not even valid ZIP'
                self.reject()
                del self.members['dependency' + ext]

    def test_nested_archive_disguised_as_resource(self):
        self.members['resource.bin'] = archive({'com/google/gson/Gson.class': klass('com/google/gson/Gson')})
        self.reject()

    def test_self_extracting_nested_zip(self):
        self.members['resource.bin'] = b'prefix' + archive({'hidden': b'x'})
        self.reject()

    def test_missing_licence_or_notice(self):
        for n in (guard.LICENSE_PATHS[0], guard.NOTICE_PATHS[0]):
            p = self.members.pop(n)
            self.reject()
            self.members[n] = p

    def test_changed_licence_or_notice(self):
        for n in (guard.LICENSE_PATHS[0], guard.NOTICE_PATHS[0]):
            p = self.members[n]
            self.members[n] += b' changed'
            self.reject()
            self.members[n] = p

    def test_duplicate_member(self):
        with self.assertRaises(guard.PackageError):
            guard.validate(archive(self.members, [('extra', b'a'), ('extra', b'b')]), self.expected, PROJECT)

    def test_unsafe_paths(self):
        for n in ('../x', '/x', 'a/../x', 'a\\x', 'C:/x'):
            with self.subTest(name=n):
                self.members[n] = b'x'
                self.reject()
                del self.members[n]

    def test_member_bound(self):
        self.members['big.txt'] = b'x' * (guard.MAX_MEMBER + 1)
        self.reject()

    def test_entry_bound(self):
        self.members.update(('x' + str(i), b'') for i in range(guard.MAX_ENTRIES))
        self.reject()

    def test_invalid_class(self):
        n = next(iter(self.expected))
        self.members[n] = b'not a class'
        self.reject()

    def test_truncated_zip(self):
        with self.assertRaises(guard.PackageError):
            guard.validate(archive(self.members)[:20], self.expected, PROJECT)


if __name__ == '__main__':
    unittest.main()
