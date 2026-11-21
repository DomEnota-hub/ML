#!/usr/bin/env python3
"""Exact-source qualification. A receipt never substitutes for a failed job."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]

def validate_cases(directory, required):
    found={}
    for path in sorted(Path(directory).glob('TEST-*.xml')):
        suite=ET.parse(path).getroot()
        cases=suite.findall('testcase')
        if suite.tag!='testsuite' or not cases:raise ValueError('Missing testcase report: '+path.name)
        for case in cases:
            key=case.attrib['classname']+'#'+case.attrib['name']
            if key in found:raise ValueError('Duplicate testcase: '+key)
            if case.find('failure') is not None or case.find('error') is not None:raise ValueError('Failed testcase: '+key)
            skipped=case.find('skipped') is not None
            if skipped and not required.get(key,{}).get('skipped',False):raise ValueError('New skip: '+key)
            found[key]={'skipped':skipped}
        if int(suite.get('failures','0')) or int(suite.get('errors','0')):raise ValueError('Failed suite: '+path.name)
    missing=set(required)-set(found)
    if missing or not found:raise ValueError('Missing required tests: '+str(sorted(missing)))
    unreviewed=set(found)-set(required)
    if unreviewed:raise ValueError('Unreviewed JVM test identities; reconcile required inventory deliberately: '+str(sorted(unreviewed)))
    return {'total':len(found),'passed':sum(not x['skipped'] for x in found.values()),'skipped':[k for k,v in found.items() if v['skipped']]}

def validate_isolation_manifest(manifest, expected, total, workers):
    """Fresh worker per current isolated method; no silent count-based bypass."""
    expected = set(tuple(x) for x in expected)
    if not expected or manifest.get('errors') or manifest.get('incomplete') or manifest.get('total') != total:
        raise ValueError('Invalid full isolation manifest')
    if manifest.get('isolated_methods') != len(expected):
        raise ValueError('Isolated method count mismatch')
    shards=manifest.get('shards', [])
    if len(shards)!=len(expected)+1 or workers!=len(shards):
        raise ValueError('Missing required fresh test workers')
    bulk=shards[0]
    if bulk.get('label')!='bulk' or bulk.get('expected') is not None or bulk.get('gradle_exit')!=0 or bulk.get('tests',0)<1:
        raise ValueError('Invalid bulk shard')
    observed=[]
    for shard in shards[1:]:
        if shard.get('gradle_exit')!=0 or shard.get('tests')!=1 or len(shard.get('expected') or [])!=1:
            raise ValueError('Invalid isolated shard')
        observed.append(tuple(shard['expected'][0]))
    if len(set(observed))!=len(observed) or set(observed)!=expected or sum(s['tests'] for s in shards)!=total:
        raise ValueError('Missing, duplicate, or unexpected isolated test')
    
def validate_batch_markers(manifest,expected,markers):
    wanted=['ML_CI_TEST_EXECUTED task=:app:testDebugUnitTest']+[
        f'ML_CI_TEST_EXECUTED task=:app:mlCiIsolated{i:03d}' for i in range(len(expected))]
    if manifest.get('runner')!='batched' or manifest.get('gradle_invocations')!=2 or sorted(markers)!=sorted(wanted):
        raise ValueError('Missing/duplicate/unexpected fresh batch worker markers')

def validate_receipts(receipts,sha,run,attempt):
    if len(receipts)!=3 or {r.get('lane') for r in receipts}!={'jvm','lint','arm64'}:raise ValueError('Three unique required lanes must pass')
    for r in receipts:
        if r.get('success') is not True:raise ValueError('Receipt must explicitly succeed')
        if (r.get('sha'),str(r.get('run')),r.get('success'))!=(sha,str(run),True) or not 1<=int(r.get('attempt',0))<=int(attempt):raise ValueError('Unqualified, stale or mixed-source receipt')

def infrastructure_failure(log):
    # Never retry after any test execution, compiler diagnostic, lint or integrity error.
    reject=r'testDebugUnitTest|tests completed|(?m:^e: )|(?i:\berror:)|Lint found errors|NewApi|[Cc]hecksum|verification failed|Compilation error|test failures'
    return not re.search(reject,log,re.I) and bool(re.search(r'Could not (GET|HEAD|resolve|download)|Could not resolve all|Plugin .* was not found',log)) and bool(re.search(r'HTTP (429|5\d\d)|status code (429|5\d\d)|timed out|Connection reset|Temporary failure|Service Unavailable',log,re.I))

def sha256(path):
    with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def exact_sha():
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    expected=os.environ.get('ML_EXPECTED_SHA',actual)
    if not re.fullmatch('[0-9a-f]{40}',expected) or actual!=expected:raise ValueError('Checkout is not exact reviewed HEAD')
    return actual

def clean_inputs():
    exact_sha()
    for path in ['.gapi-local','.signing-local','app/src/main/assets/gapi-builtin-keys.json']:
        if (ROOT/path).exists():raise ValueError('Protected build input must not enter CI/cache: '+path)
    if subprocess.check_output(['git','status','--porcelain','--untracked-files=all','--','app/src'],cwd=ROOT,text=True).strip():raise ValueError('Uncommitted application inputs')
    if subprocess.check_output(['git','diff','--name-only','HEAD'],cwd=ROOT,text=True).strip():raise ValueError('Tracked source modified before build')

def stage(label,cmd):
    out=ROOT/'ci-output';out.mkdir(exist_ok=True)
    elapsed=time.monotonic();attempts=[]
    if label!='bootstrap' and Path(cmd[0]).name=='gradlew':cmd=[*cmd,'--profile','--init-script',str(ROOT/'scripts/ci/test-execution.init.gradle')]
    for attempt in range(1,3):
        path=out/f'{label}-{attempt}.log'
        with path.open('w') as log:
            proc=subprocess.Popen(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
            for line in proc.stdout:sys.stdout.write(line);log.write(line)
            code=proc.wait()
        attempts.append({'exit':code,'attempt':attempt})
        if code==0 or attempt==2 or not infrastructure_failure(path.read_text()):break
        print('INFRASTRUCTURE RETRY: dependency transport failed before tests; 10s backoff',flush=True);time.sleep(10)
    record={'stage':label,'sha':exact_sha(),'seconds':round(time.monotonic()-elapsed,3),'attempts':attempts,'exit':code}
    (out/f'{label}-timing.json').write_text(json.dumps(record,indent=2)+'\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write(f"\n| Stage | Seconds | Exit | Attempts |\n|---|---:|---|---:|\n| {label} | {record['seconds']} s | exit {code} | attempts {len(attempts)} |\n")
    return code

def validate_candidate(candidate,manifest,sha):
    if manifest['sha']!=sha:raise ValueError('Candidate source mismatch')
    if set(manifest['files'])!={'ML_ARM64_UNSIGNED.apk','SOURCES_EXACT_COMMIT.zip','SOURCE_COMMIT_SHA.txt'}:raise ValueError('Incomplete candidate')
    for name,digest in manifest['files'].items():
        if Path(name).name!=name or sha256(candidate/name)!=digest:raise ValueError('Candidate bytes mismatch')
    if (candidate/'SOURCE_COMMIT_SHA.txt').read_text().strip()!=sha:raise ValueError('Source manifest mismatch')
    with zipfile.ZipFile(candidate/'SOURCES_EXACT_COMMIT.zip') as z:
        if z.comment.decode()!=sha or z.testzip() is not None:raise ValueError('Archive not exact source')

def validate_request(mode,fault):
    if mode not in {'quick','qualify','perf','verify'} or fault not in {'none','compile','lint','test'}:raise ValueError('Invalid CI request')
    if fault!='none' and mode!='qualify':raise ValueError('Negative probes require mode=qualify')

def validate_consumption(receipt,live_sha,run):
    if receipt['sha']!=live_sha or run.get('head_sha')!=receipt['sha'] or str(run.get('id'))!=str(receipt['run']):raise ValueError('Stale or mismatched qualification')
    if run.get('status')!='completed' or run.get('conclusion')!='success' or int(run.get('run_attempt',0))<int(receipt['attempt']):raise ValueError('Workflow did not conclude successfully')

def validate_unsigned(path):
    with zipfile.ZipFile(path) as z:
        if z.testzip() is not None:raise ValueError('Corrupt APK')
        if any(n.upper().startswith('META-INF/') and n.upper().endswith(('.RSA','.DSA','.EC','.SF')) for n in z.namelist()):raise ValueError('Signing material in unsigned APK')
        with Path(path).open('rb') as stream:
            stream.seek(max(0,z.start_dir-16))
            if stream.read(16)==b'APK Sig Block 42':raise ValueError('APK signing block in unsigned candidate')

def api(path):
    if os.environ.get('GITHUB_TOKEN'):
        req=urllib.request.Request(os.environ.get('GITHUB_API_URL','https://api.github.com')+path,headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json'})
        with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
    return json.loads(subprocess.check_output(['gh','api',path],text=True))

def live_sha(repository,branch,pr=None):
    if pr:
        data=api('/repos/'+repository+'/pulls/'+str(pr))
        if data['head']['repo']['full_name']!=repository:raise ValueError('Forks cannot produce signing handoffs')
        return data['head']['sha']
    return api('/repos/'+repository+'/git/ref/heads/'+urllib.parse.quote(branch,safe=''))['object']['sha']


def main():
    action=sys.argv[1]
    out=ROOT/'ci-output';out.mkdir(exist_ok=True)
    if action=='request':validate_request(os.environ['ML_MODE'],os.environ['ML_FAULT'])
    elif action=='inputs':clean_inputs();print('Exact source, protected inputs absent:',exact_sha())
    elif action=='stage':return stage(sys.argv[2],sys.argv[3:])
    elif action=='cases':
        r=validate_cases(ROOT/'app/build/test-results/testDebugUnitTest',json.loads((ROOT/'docs/ci/required-jvm-tests.json').read_text()))
        m=json.loads((ROOT/'app/build/ml-jvm-shards/full/manifest.json').read_text())
        import importlib.util
        spec=importlib.util.spec_from_file_location('ml_isolation',ROOT/'scripts/run-jvm-ci.py')
        isolation=importlib.util.module_from_spec(spec);spec.loader.exec_module(isolation)
        expected=isolation.expected_methods('full')
        markers=[line.strip() for p in out.glob('full-jvm-*.log') for line in p.read_text().splitlines() if line.strip().startswith('ML_CI_TEST_EXECUTED task=')]
        validate_batch_markers(m,expected,markers)
        workers=len(markers)
        validate_isolation_manifest(m,expected,r['total'],workers)
        r['workers_observed']=workers
        (out/'jvm-coverage.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
    elif action=='receipt':
        lane=sys.argv[2];r={'lane':lane,'sha':exact_sha(),'run':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'],'success':True}
        (out/f'{lane}-receipt.json').write_text(json.dumps(r,indent=2)+'\n')
    elif action=='qualify':
        receipts=[json.loads(p.read_text()) for p in (ROOT/'received').rglob('*-receipt.json')]
        validate_receipts(receipts,exact_sha(),os.environ['GITHUB_RUN_ID'],os.environ['GITHUB_RUN_ATTEMPT'])
        candidate=ROOT/'received/arm64-candidate'
        manifest=json.loads((candidate/'CANDIDATE.json').read_text())
        validate_candidate(candidate,manifest,exact_sha())
        # All expensive verification precedes the final live-head check.
        sha=exact_sha();repository=os.environ['GITHUB_REPOSITORY'];branch=os.environ['ML_SOURCE_BRANCH'];pr=os.environ.get('ML_SOURCE_PR')
        if live_sha(repository,branch,pr)!=sha:raise ValueError('Branch HEAD changed; refusing stale handoff')
        dest=ROOT/'qualified';dest.mkdir()
        (dest/'CANDIDATE.json').write_bytes((candidate/'CANDIDATE.json').read_bytes())
        receipt={'candidate_manifest_sha256':sha256(candidate/'CANDIDATE.json'),'sha':sha,'run':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'],'repository':repository,'branch':branch,'pr':pr,'candidate_artifact':'ml-ci-'+os.environ['GITHUB_RUN_ID']+'-UNQUALIFIED-arm64','receipts':receipts,'unsigned':True,'device_qa':'pending','required_certificate_sha256':'4f6f0ddf4d8fd2669fd2a3a6ebab6e84bda17281a3a5badada0e1eeb73a4f1d2'}
        (dest/'QUALIFIED.json').write_text(json.dumps(receipt,indent=2)+'\n')
    elif action=='consume':
        receipt=json.loads(Path(sys.argv[2]).read_text())
        manifest_path=Path(sys.argv[2]).parent/'CANDIDATE.json'
        if sha256(manifest_path)!=receipt['candidate_manifest_sha256']:raise ValueError('Qualification manifest changed')
        validate_candidate(Path(sys.argv[3]),json.loads(manifest_path.read_text()),receipt['sha'])
        run=api('/repos/'+receipt['repository']+'/actions/runs/'+str(receipt['run']))
        validate_consumption(receipt,live_sha(receipt['repository'],receipt['branch'],receipt.get('pr')),run)
        print('Successful exact-source qualification is current:',receipt['sha'])
    else:raise ValueError('Unknown gate action')
    return 0

if __name__=='__main__':sys.exit(main())
