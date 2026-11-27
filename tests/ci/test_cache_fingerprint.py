import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('cache_fingerprint',Path(__file__).resolve().parents[2]/'scripts/ci/cache_fingerprint.py')
cache=importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)

class CacheFingerprintTest(unittest.TestCase):
    def test_only_base_release_metadata_is_neutral(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            app=root/'app/build.gradle'
            app.parent.mkdir()
            original="        versionCode 294\n        versionName '1.7.234'\n        versionCode 100000 + number\n"
            app.write_text(original)
            (root/'settings.gradle').write_text('mavenCentral()')
            paths=['app/build.gradle','settings.gradle']
            first=cache.fingerprint(root,paths)
            new=original.replace('294','295').replace('1.7.234','1.7.235')
            app.write_text(new)
            self.assertEqual(first,cache.fingerprint(root,paths))
            self.assertEqual(new,app.read_text())
            app.write_text(new.replace('100000','200000'))
            self.assertNotEqual(first,cache.fingerprint(root,paths))
            app.write_text(new)
            (root/'settings.gradle').write_text('google()')
            self.assertNotEqual(first,cache.fingerprint(root,paths))

    def test_migration_is_exact_and_fail_closed(self):
        old,new=next(iter(cache.MIGRATION.items()))
        self.assertEqual(new,cache.migration(old))
        self.assertEqual('a'*64,cache.migration('a'*64))
        with self.assertRaises(ValueError):
            cache.migration('not-a-hash')
        with self.assertRaises(ValueError):
            cache.normalized('app/build.gradle',b'')
