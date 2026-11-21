#!/usr/bin/env python3
"""Non-release, same-SHA A/B: legacy method-by-method vs one Gradle graph.
No retries or qualification receipts. Both paths must report exactly the same
isolated cases, one successful case per fresh task. No application APK is built.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('isolation', ROOT/'scripts/run-jvm-isolated-compose.py')
iso = importlib.util.module_from_spec(spec)
spec.loader.exec_module(iso)
OUT = ROOT/'ci-output/audit'
BENCH = 'com.domenota.medialoader.data.storage.HistoricalReferenceQueryBenchmarkTest'
PURE = ['com.domenota.medialoader.ui.screens.organization.CollectionPurePolicyTest',
        'com.domenota.medialoader.ui.screens.gallery.GalleryVideoClockTest']

def require_cases(cases, expected):
    if set(cases)!=set(expected):
        raise ValueError(f'Case mismatch: expected={sorted(expected)}, actual={sorted(cases)}')
    for key, (_, case) in cases.items():
        if any(case.find(tag) is not None for tag in ('failure','error','skipped')):
            raise ValueError(f'Non-passing audit case: {key}')

def checked_shard(label, args, expected):
    start=time.monotonic()
    code,cases=iso.run_shard(label,args,OUT)
    if code: raise RuntimeError(f'{label}: Gradle exit {code}')
    require_cases(cases,expected)
    return round(time.monotonic()-start,3), sum(float(c.get('time','0')) for _,c in cases.values())

def methods(cls):
    source=ROOT/'app/src/test/java'/(cls.replace('.','/')+'.kt')
    names=re.findall(r'@Test(?:\s*\([^)]*\))?\s+fun\s+([A-Za-z_]\w*)\s*\(',source.read_text())
    if not names:raise ValueError('No tests in '+cls)
    return {(cls,name) for name in names}

def run_batch(expected,label):
    plan=OUT/f'{label}-plan.json'
    plan.write_text(json.dumps(expected,indent=2)+'\n')
    batch_root=ROOT/'app/build/ml-ci-batch'
    shutil.rmtree(batch_root,ignore_errors=True)
    task_names=[f'mlCiIsolated{i:03d}' for i in range(len(expected))]
    cmd=[os.environ.get('ML_GRADLE',str(ROOT/'gradlew')),*(':app:'+n for n in task_names),
         '-Parm64Release=true','--no-parallel','--stacktrace','--console=plain',
         '--init-script',str(ROOT/'scripts/ci/isolated-methods.init.gradle'),
         '--init-script',str(ROOT/'scripts/ci/test-execution.init.gradle')]
    start=time.monotonic()
    env=dict(os.environ, ML_CI_ISOLATION_PLAN=str(plan))
    log=OUT/f'{label}.log'
    with log.open('w') as stream:
        proc=subprocess.Popen(cmd,cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        for line in proc.stdout:
            print(line,end='',flush=True);stream.write(line)
        exit_code=proc.wait()
    batch_seconds=round(time.monotonic()-start,3)
    if exit_code:raise RuntimeError(f'Batch Gradle exit {exit_code}')
    log_text=log.read_text()
    for task,(cls,method) in zip(task_names,expected):
        marker=f'ML_CI_TEST_EXECUTED task=:app:{task}'
        if sum(line.strip()==marker for line in log_text.splitlines())!=1:raise ValueError('Missing/duplicate worker marker '+task)
        require_cases(iso.read_cases(batch_root/task/'xml'),{(cls,method)})
    return batch_seconds

def main():
    shutil.rmtree(OUT,ignore_errors=True)
    OUT.mkdir(parents=True)
    expected=iso.expected_methods('full')
    if len(expected)!=len(set(expected)) or not expected:raise ValueError('Invalid isolation plan')
    # Warm both compilation and runtime dependencies. Also validate relocated methods
    # and compare mandatory regression vs performance-only repetitions.
    pure=set().union(*(methods(c) for c in PURE))
    bench=methods(BENCH)
    normal_args=[arg for cls in PURE+[BENCH] for arg in ('--tests',cls)]
    normal=checked_shard('warm-regression',normal_args,pure|bench)
    perf=checked_shard('performance',['-PmlPerfBenchmarks=true','--tests',BENCH],bench)
    normal_cases=iso.read_cases(OUT/'warm-regression/xml')
    normal_bench_seconds=sum(float(case.get('time','0')) for key,(_,case) in normal_cases.items() if key in bench)
    # Fail fast on graph/configuration errors before repeating all legacy methods.
    run_batch(expected[:2],'pilot-smoke')
    baseline_start=time.monotonic()
    for index,(cls,method) in enumerate(expected):
        checked_shard(f'baseline-{index:03d}',['--tests',cls+'.'+method],{(cls,method)})
    legacy_seconds=round(time.monotonic()-baseline_start,3)
    batch_seconds=run_batch(expected,'batch')
    report={'sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            'isolated_cases_each':len(expected),'legacy_gradle_invocations':len(expected),
            'pilot_gradle_invocations':1,'legacy_seconds':legacy_seconds,'pilot_seconds':batch_seconds,
            'normal_benchmark_testcase_seconds':normal_bench_seconds,'opt_in_benchmark_testcase_seconds':perf[1],
            'normal_and_benchmark_cases':len(pure|bench),'status':'PARITY_PASS','qualification':False}
    (OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report),flush=True)

if __name__=='__main__':main()
