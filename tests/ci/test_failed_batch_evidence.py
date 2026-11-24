import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ml_jvm_runner', ROOT / 'scripts/run-jvm-ci.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class FailedBatchEvidenceTest(unittest.TestCase):
    def test_preserves_executed_success_and_failure_without_inventing_missing_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            batch, shards = base / 'batch', base / 'shards'
            shards.mkdir()
            for task, xml in [('mlCiIsolated000', '<testsuite><testcase name="ok"/></testsuite>'),
                              ('mlCiIsolated001', '<testsuite><testcase name="bad"><failure>exact cause</failure></testcase></testsuite>')]:
                path = batch / task / 'xml'
                path.mkdir(parents=True)
                (path / 'TEST-case.xml').write_text(xml)
            saved = runner.preserve_failed_batch(batch, shards, ['mlCiIsolated000','mlCiIsolated001','mlCiIsolated002'])
            self.assertEqual(saved, ['mlCiIsolated000/xml','mlCiIsolated001/xml'])
            for task in ('mlCiIsolated000','mlCiIsolated001'):
                self.assertEqual((batch/task/'xml/TEST-case.xml').read_bytes(),
                                 (shards/'failed-batch'/task/'xml/TEST-case.xml').read_bytes())
            self.assertFalse((shards/'failed-batch/mlCiIsolated002').exists())


if __name__ == '__main__':
    unittest.main()
