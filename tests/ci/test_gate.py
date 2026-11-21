import importlib.util
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('gate',pathlib.Path(__file__).parents[2]/'scripts/ci/gate.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class GateTest(unittest.TestCase):
    def test_required_missing_duplicate_failure_and_new_skip_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            def write(cases):
                (root/'TEST-a.xml').write_text('<testsuite>'+cases+'</testsuite>')
            required={'A#one':{'skipped':False},'A#opt':{'skipped':True}}
            ok='<testcase classname="A" name="one"/><testcase classname="A" name="opt"><skipped/></testcase>'
            write(ok);self.assertEqual(gate.validate_cases(root,required)['total'],2)
            for bad in [ok.replace('<testcase classname="A" name="one"/>',''),ok+ok,ok.replace('name="one"/>','name="one"><failure/></testcase>'),ok.replace('name="one"/>','name="one"><skipped/></testcase>')]:
                write(bad)
                with self.assertRaises(ValueError):gate.validate_cases(root,required)
    def test_receipts_must_match_sha_run_attempt_and_all_three_lanes(self):
        good=[dict(lane=x,sha='a'*40,run='1',attempt='2',success=True) for x in ['jvm','lint','arm64']]
        gate.validate_receipts(good,'a'*40,'1','2')
        for field,value in [('sha','b'*40),('run','9'),('attempt','3'),('success',False),('lane','jvm')]:
            bad=[dict(x) for x in good];bad[-1][field]=value
            with self.assertRaises(ValueError):gate.validate_receipts(bad,'a'*40,'1','2')
        with self.assertRaises(ValueError):gate.validate_receipts(good[:-1],'a'*40,'1','2')
    def test_network_retry_rejects_test_compile_lint_and_checksum_failures(self):
        net='Could not resolve dependency: Could not GET artifact. HTTP 503 Service Unavailable'
        self.assertTrue(gate.infrastructure_failure(net))
        for x in ['e: file.kt: type mismatch','Lint found errors','tests completed, 1 failed',':app:testDebugUnitTest','Checksum verification failed','Stub.java:5: error: incompatible types: String cannot be converted to int']:
            self.assertFalse(gate.infrastructure_failure(net+'\n'+x))
        self.assertFalse(gate.infrastructure_failure('BUILD FAILED: missing method'))

if __name__=='__main__':unittest.main()

class ResumeTest(unittest.TestCase):
    def test_previous_success_same_run_and_sha_can_resume_but_future_cannot(self):
        rows=[dict(lane=x,sha='a'*40,run='1',attempt='1',success=True) for x in ['jvm','lint','arm64']]
        gate.validate_receipts(rows,'a'*40,'1','2')
        rows[-1]['attempt']='3'
        with self.assertRaises(ValueError):gate.validate_receipts(rows,'a'*40,'1','2')

class IsolationTest(unittest.TestCase):
    def test_failed_bulk_is_explicitly_incomplete_and_does_not_launch_isolated_workers(self):
        import unittest.mock as mock
        import importlib.util
        import json
        p=pathlib.Path(__file__).parents[2]/'scripts/run-jvm-isolated-compose.py'
        spec=importlib.util.spec_from_file_location('isolation',p)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(module,'SHARDS',pathlib.Path(tmp)), mock.patch.object(module,'expected_methods',return_value=[('A','method')]), mock.patch.object(module,'run_shard',return_value=(1,{})) as run, mock.patch('sys.argv',['runner','full']):
            self.assertEqual(module.main(),1)
            self.assertEqual(run.call_count,1)
            manifest=json.loads((pathlib.Path(tmp)/'full/manifest.json').read_text())
            self.assertTrue(manifest['incomplete']);self.assertTrue(manifest['errors'])

class RequestTest(unittest.TestCase):
    def test_quick_negative_probes_cannot_bypass_the_required_lane(self):
        gate.validate_request('quick','none');gate.validate_request('qualify','none');gate.validate_request('perf','none')
        for fault in ['compile','lint','test']:
            gate.validate_request('qualify',fault)
            with self.assertRaises(ValueError):gate.validate_request('quick',fault)
            with self.assertRaises(ValueError):gate.validate_request('perf',fault)
        with self.assertRaises(ValueError):gate.validate_request('unknown','none')

class ReviewRegressions(unittest.TestCase):
    def test_no_green_consumption_after_head_change_cancellation_or_incomplete_run(self):
        receipt=dict(sha='a'*40,run='7',attempt='1')
        run=dict(id=7,head_sha='a'*40,status='completed',conclusion='success',run_attempt=1)
        gate.validate_consumption(receipt,'a'*40,run)
        for field,value in [('head_sha','b'*40),('status','in_progress'),('conclusion','cancelled'),('id',8)]:
            bad=dict(run);bad[field]=value
            with self.assertRaises(ValueError):gate.validate_consumption(receipt,'a'*40,bad)
        with self.assertRaises(ValueError):gate.validate_consumption(receipt,'b'*40,run)
    def test_signature_material_is_rejected_in_an_unsigned_candidate(self):
        import zipfile
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)/'test.apk'
            with zipfile.ZipFile(p,'w') as z:z.writestr('AndroidManifest.xml',b'test')
            gate.validate_unsigned(p)
            with zipfile.ZipFile(p,'a') as z:z.writestr('META-INF/CERT.RSA',b'certificate')
            with self.assertRaises(ValueError):gate.validate_unsigned(p)
    def test_forbidden_source_patterns_really_stop_bash(self):
        import subprocess
        lines=(pathlib.Path(__file__).parents[2]/'scripts/ci/source-guards.sh').read_text().splitlines()
        guards=[l for l in lines if l.startswith('if grep')]
        self.assertEqual(len(guards),2)
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)/'input'
            for line in guards:
                import shlex
                words=shlex.split(line);pattern=words[3];target=words[4].rstrip(';')
                command='set -euo pipefail\n'+line.replace(target,str(p))+'\ntrue'
                p.write_text(pattern);self.assertNotEqual(subprocess.run(['bash','-c',command],capture_output=True).returncode,0)
                p.write_text('safe');self.assertEqual(subprocess.run(['bash','-c',command],capture_output=True).returncode,0)
    def test_ci_test_tasks_disable_result_cache_but_do_not_disable_compile_cache(self):
        text=(pathlib.Path(__file__).parents[2]/'scripts/ci/test-execution.init.gradle').read_text()
        self.assertIn('withType(org.gradle.api.tasks.testing.Test)',text)
        self.assertIn('upToDateWhen { false }',text)
        self.assertIn('doNotCacheIf',text)

class CandidateTest(unittest.TestCase):
    def test_consumed_candidate_checks_all_bytes_and_exact_source_archive(self):
        import zipfile
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp);sha='a'*40
            (p/'ML_ARM64_UNSIGNED.apk').write_bytes(b'fixture')
            (p/'SOURCE_COMMIT_SHA.txt').write_text(sha+'\n')
            with zipfile.ZipFile(p/'SOURCES_EXACT_COMMIT.zip','w') as z:z.writestr('test.txt','fixture');z.comment=sha.encode()
            m=dict(sha=sha,files={x.name:gate.sha256(x) for x in p.iterdir()})
            gate.validate_candidate(p,m,sha)
            (p/'ML_ARM64_UNSIGNED.apk').write_bytes(b'changed')
            with self.assertRaises(ValueError):gate.validate_candidate(p,m,sha)
            with self.assertRaises(ValueError):gate.validate_candidate(p,m,'b'*40)

class RetryTest(unittest.TestCase):
    def test_transport_retries_are_visible_and_functional_failures_are_not_retried(self):
        import contextlib,io,json,sys
        import unittest.mock as mock
        for functional in [False,True]:
            with tempfile.TemporaryDirectory() as tmp:
                root=pathlib.Path(tmp);child=root/'child.py';counter=root/'counter'
                child.write_text('from pathlib import Path\nimport sys\np=Path('+repr(str(counter))+')\nn=int(p.read_text()) if p.exists() else 0\np.write_text(str(n+1))\nprint("Could not GET artifact. HTTP 503 Service Unavailable")\n'+('print("e: file.kt: type mismatch")\n' if functional else '')+'sys.exit(1 if n==0 else 0)\n')
                with mock.patch.object(gate,'ROOT',root),mock.patch.object(gate,'exact_sha',return_value='a'*40),mock.patch.object(gate.time,'sleep'),contextlib.redirect_stdout(io.StringIO()):
                    result=gate.stage('probe',[sys.executable,str(child)])
                self.assertEqual(result,1 if functional else 0)
                self.assertEqual(int(counter.read_text()),1 if functional else 2)
                timing=json.loads((root/'ci-output/probe-timing.json').read_text())
                self.assertEqual(len(timing['attempts']),1 if functional else 2)
