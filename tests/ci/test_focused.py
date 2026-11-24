import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('focused', Path(__file__).parents[2]/'scripts/ci/focused.py')
focused = importlib.util.module_from_spec(spec)
spec.loader.exec_module(focused)

class FocusedTest(unittest.TestCase):
    def test_explicit_modes_and_risk_lint(self):
        self.assertEqual(focused.resolve_request('quick','archive,privacy','',[],'false'),(('archive','privacy'),True))
        self.assertEqual(focused.resolve_request('quick','baseline','ci:quick-navigation',['ci:quick-navigation'],'false'),(('navigation',),False))
        self.assertEqual(focused.resolve_request('verify','baseline','ci:verify',['ci:verify'],'false'),(('baseline',),False))
        self.assertEqual(focused.resolve_request('quick','baseline','',[],'true'),(('baseline',),True))
        for args in [('quick','unknown','',[],'false'),('quick','archive,archive','',[],'false'),
                     ('quick','archive,','',[],'false'),('verify','archive','',[],'false'),
                     ('quick','baseline','ci:quick-archive',['ci:quick-archive','ci:verify'],'false'),
                     ('verify','baseline','ci:quick-archive',['ci:quick-archive'],'false'),
                     ('quick','baseline','ci:quick-archive',[],'false')]:
            with self.assertRaises(ValueError):
                focused.resolve_request(*args)

    def test_real_inventory_profiles_preserve_baseline_and_isolation(self):
        runner = focused.load('runner', 'scripts/run-jvm-ci.py')
        base, _, _ = focused.make_plan(('baseline',), runner)
        self.assertTrue(base)
        for name in focused.PROFILES:
            selected, bulk, isolated = focused.make_plan((name,), runner)
            self.assertTrue(set(base) <= set(selected))
            self.assertEqual(set(selected), {k for k in selected if k.split('#')[0] in bulk} |
                             {c+'#'+m for c,m in isolated})
            self.assertTrue(set(bulk).isdisjoint(c for c,m in runner.expected_methods('full')))
        a, _, _ = focused.make_plan(('archive',), runner)
        n, _, _ = focused.make_plan(('navigation',), runner)
        combined, _, _ = focused.make_plan(('archive','navigation'), runner)
        self.assertEqual(set(combined), set(a)|set(n))
        self.assertTrue(any('.DownloadGroupCardUiTest#archiveActionRunsDirectlyWithoutOpeningGroupOrConfirmation' in k for k in a))

    def test_missing_baseline_and_isolation_inventory_rejected(self):
        runner = focused.load('runner', 'scripts/run-jvm-ci.py')
        baseline = (focused.ROOT/'scripts/ci/focused-tests.txt').read_text().splitlines()
        import json
        inventory = json.loads((focused.ROOT/'docs/ci/required-jvm-tests.json').read_text())
        isolated = runner.expected_methods('full')
        with self.assertRaises(ValueError):
            focused.select(inventory, baseline+['missing.Test'], ('baseline',), isolated)
        selected, _, methods = focused.select(inventory, baseline, ('archive',), isolated)
        self.assertTrue(methods)
        c,m=methods[0]
        bad=dict(inventory)
        bad.pop(c+'#'+m)
        with self.assertRaises(ValueError):
            focused.select(bad, baseline, ('archive',), isolated)

    def test_worker_markers_reject_missing_duplicate_extra(self):
        methods=[('A','first'),('B','second')]
        good=['ML_CI_TEST_EXECUTED task=:app:testDebugUnitTest',
              'ML_CI_TEST_EXECUTED task=:app:mlCiIsolated000',
              'ML_CI_TEST_EXECUTED task=:app:mlCiIsolated001']
        focused.validate_markers(good, methods)
        focused.validate_markers(good[:1], [])
        for markers in [good[:-1],good+[good[0]],good+['ML_CI_TEST_EXECUTED task=:app:other']]:
            with self.assertRaises(ValueError):
                focused.validate_markers(markers, methods)

if __name__ == '__main__':
    unittest.main()
