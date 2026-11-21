import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('repository_policy',Path(__file__).resolve().parents[2]/'scripts/ci/repository_policy.py')
policy=importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)

class RepositoryPolicyTest(unittest.TestCase):
    def test_current_and_next_release_versions(self):
        for version,code in [('1.7.211',271),('1.7.212',272)]:
            self.assertEqual((version,code),policy.validate_version(f"versionCode {code}\nversionName '{version}'\nversionCode 100000 + number"))
    def test_invalid_versions_rejected(self):
        for value in ["versionCode 0\nversionName '1.7.212'", "versionCode 272\nversionName 'next'", "versionCode 272\nversionCode 273\nversionName '1.7.212'"]:
            with self.assertRaises(ValueError):policy.validate_version(value)
    def test_workflow_restoration_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            directory=root/'.github/workflows'
            directory.mkdir(parents=True)
            (directory/'ml-ci.yml').write_text('current')
            policy.validate_workflows(root,['ml-ci.yml'])
            (directory/'old.yml').write_text('retired')
            with self.assertRaises(ValueError):policy.validate_workflows(root,['ml-ci.yml'])
            (directory/'old.yml').unlink()
            (directory/'ml-ci.yml').unlink()
            with self.assertRaises(ValueError):policy.validate_workflows(root,['ml-ci.yml'])

    def test_retired_runner_or_archive_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            policy.validate_retired_paths(root)
            for rel in ['scripts/run-jvm-isolated-compose.py','scripts/ci/isolation_audit.py','docs/ci/archive/workflows-17211']:
                item=root/rel
                item.parent.mkdir(parents=True,exist_ok=True)
                item.write_text('retired')
                with self.assertRaises(ValueError):policy.validate_retired_paths(root)
                item.unlink()
