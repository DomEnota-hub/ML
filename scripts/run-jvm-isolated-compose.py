#!/usr/bin/env python3
"""Run every test, separating recurring Robolectric Compose idling failures.

No retries, ignored tests, timeouts, or production changes. The bulk invocation
excludes only the affected classes whose *every* method is run in a fresh Gradle test
worker. Merge original testcase nodes into the usual JUnit XML output, failing
on missing/duplicate methods, unexpected filtered results, or any failed shard.
Raw XML/HTML for all invocations is kept for diagnosis.
"""
from collections import defaultdict
from pathlib import Path
import copy
import json
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MUSIC = ('com.domenota.medialoader.ui.screens.music.RecentMusicScreenTest',
         'com.domenota.medialoader.ui.screens.music.SmartMusicScreenTest')
COLLECTION = ('com.domenota.medialoader.ui.screens.organization.MediaOrganizationScreenTest',)
# Robolectric paused-looper popup state can inherit idle/view state when run
# among ~2000 UI methods. Preserve both checks, each in a fresh worker.
VIDEO_SONG_MENU = ('com.domenota.medialoader.ui.components.DownloadVideoSongMenuUiTest',)
GROUPS = {'music': MUSIC, 'collection': COLLECTION, 'full': MUSIC + COLLECTION + VIDEO_SONG_MENU}
RESULTS = ROOT / 'app/build/test-results/testDebugUnitTest'
HTML = ROOT / 'app/build/reports/tests/testDebugUnitTest'
SHARDS = ROOT / 'app/build/ml-jvm-shards'


def expected_methods(mode):
    result = []
    for cls in GROUPS[mode]:
        source = ROOT / 'app/src/test/java' / (cls.replace('.', '/') + '.kt')
        text = source.read_text(encoding='utf-8')
        names = re.findall(r'@Test(?:\s*\([^)]*\))?\s+fun\s+([A-Za-z_]\w*)\s*\(', text)
        annotations = re.findall(r'@Test\b', text)
        if not names or len(names) != len(annotations) or len(set(names)) != len(names):
            raise RuntimeError(f'Cannot enumerate every @Test method in {source}')
        result.extend((cls, name) for name in names)
    return result


def read_cases(directory):
    cases = {}
    for path in sorted(directory.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        if suite.tag != 'testsuite':
            raise RuntimeError(f'Unexpected JUnit root in {path}')
        for case in suite.findall('testcase'):
            key = (case.attrib['classname'], case.attrib['name'])
            if key in cases:
                raise RuntimeError(f'Duplicate test within shard: {key}')
            cases[key] = (suite, case)
    return cases


def run_shard(label, extra, shard_root):
    # No stale XML may satisfy a shard that fails to start or compile.
    shutil.rmtree(RESULTS, ignore_errors=True)
    shutil.rmtree(HTML, ignore_errors=True)
    dest = shard_root / label
    dest.mkdir(parents=True)
    cmd = [str(ROOT / 'gradlew'), ':app:testDebugUnitTest', '-Parm64Release=true',
           '--stacktrace', *extra]
    print(f'=== JVM shard {label} ===', flush=True)
    code = subprocess.run(cmd, cwd=ROOT).returncode
    if RESULTS.is_dir():
        shutil.copytree(RESULTS, dest / 'xml')
    if HTML.is_dir():
        shutil.copytree(HTML, dest / 'html')
    cases = read_cases(dest / 'xml')
    return code, cases


def merge_cases(cases):
    by_class = defaultdict(list)
    for key, value in cases.items():
        by_class[key[0]].append(value)
    shutil.rmtree(RESULTS, ignore_errors=True)
    RESULTS.mkdir(parents=True)
    for cls, entries in sorted(by_class.items()):
        template = entries[0][0]
        merged = ET.Element('testsuite', dict(template.attrib))
        merged.set('name', cls)
        properties = template.find('properties')
        if properties is not None:
            merged.append(copy.deepcopy(properties))
        count = {'failures': 0, 'errors': 0, 'skipped': 0}
        seconds = 0.0
        streams = {'system-out': [], 'system-err': []}
        seen_suites = set()
        for suite, case in entries:
            merged.append(copy.deepcopy(case))
            seconds += float(case.get('time', '0'))
            for attr, tag in (('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')):
                count[attr] += case.find(tag) is not None
            if id(suite) not in seen_suites:
                seen_suites.add(id(suite))
                for tag in streams:
                    if suite.findtext(tag):
                        streams[tag].append(suite.findtext(tag))
        merged.set('tests', str(len(entries)))
        merged.set('time', f'{seconds:.6f}')
        for attr, value in count.items():
            merged.set(attr, str(value))
        for tag, chunks in streams.items():
            ET.SubElement(merged, tag).text = '\n'.join(chunks)
        ET.ElementTree(merged).write(RESULTS / f'TEST-{cls}.xml', encoding='utf-8', xml_declaration=True)


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in GROUPS:
        raise SystemExit('usage: run-jvm-isolated-compose.py music|collection [Gradle --tests patterns] | full')
    mode, extra = sys.argv[1], sys.argv[2:]
    if mode == 'full' and extra:
        raise SystemExit('Full suite must not have a test filter')
    if mode != 'full' and (not extra or len(extra) % 2 or any(x != '--tests' for x in extra[::2])):
        raise SystemExit('Targeted suite requires the existing --tests patterns')
    expected = expected_methods(mode)
    isolated = set(expected)
    class_names = {k[0] for k in isolated}
    shard_root = SHARDS / mode
    shutil.rmtree(shard_root, ignore_errors=True)
    shard_root.mkdir(parents=True)
    all_cases = {}
    records = []
    errors = []

    def collect(label, args, wanted=None):
        code, cases = run_shard(label, args, shard_root)
        actual = set(cases)
        if code or not actual:
            errors.append(f'{label}: Gradle exit {code}, {len(actual)} reported tests')
        if wanted is not None and actual != wanted:
            errors.append(f'{label}: expected {sorted(wanted)}, got {sorted(actual)}')
        if wanted is None and any(k[0] in class_names for k in actual):
            errors.append(f'{label}: bulk unexpectedly ran isolated UI methods')
        if actual & all_cases.keys():
            errors.append(f'{label}: duplicate testcase {sorted(actual & all_cases.keys())}')
        for key, value in cases.items():
            case = value[1]
            if case.find('failure') is not None or case.find('error') is not None:
                errors.append(f'{label}: failing test {key}')
            if wanted is not None and case.find('skipped') is not None:
                errors.append(f'{label}: required isolated test skipped: {key}')
            # Keep the first result; the duplicate itself fails the entire run.
            all_cases.setdefault(key, value)
        records.append({'label': label, 'gradle_exit': code, 'tests': len(actual),
                        'expected': sorted(wanted) if wanted is not None else None})

    # A failed test is never retried or replaced with a successful attempt.
    collect('bulk', [f'-PmlIsolatedComposeBulk={mode}', *extra])
    for cls, method in expected:
        collect(f'{cls.rsplit(".", 1)[-1]}-{method}', ['--tests', f'{cls}.{method}'], {(cls, method)})
    if isolated - all_cases.keys():
        errors.append(f'Missing isolated tests: {sorted(isolated - all_cases.keys())}')
    merge_cases(all_cases)
    report = {'mode': mode, 'total': len(all_cases), 'isolated_methods': len(isolated),
              'shards': records, 'errors': errors}
    (shard_root / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return 1 if errors else 0


if __name__ == '__main__':
    raise SystemExit(main())
