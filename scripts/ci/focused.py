#!/usr/bin/env python3
"""Risk-oriented quick; exact inventory, original Compose isolation and fresh XML."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'com.domenota.medialoader.'
GUARDS = ('ui.components.PrivateAccessGateTest ui.components.PrivatePlaybackAliasTest '
          'ui.components.PrivatePlaybackPolicyTest data.privatevault.PrivateAccessManagerTest '
          'data.library.MediaWriteAccessTest data.playback.FolderVisibilityPlaybackSafetyTest '
          'ui.LibraryValidationGateTest').split()
PROFILES = {
    'baseline': '',
    'chrome': 'ui.components.LibraryModeHeaderTest ui.components.LibraryHeaderActionsTest ui.components.MediaNavigationDrawerTest ui.navigation.DrawerPolicyTest ui.navigation.JournalAccessTest ui.GalleryNavigationPlacementTest ui.screens.LibraryModeScreensTest ui.screens.LibraryModeShortViewportTest ui.theme.TransparentNavigationTest ui.theme.SettingsIconPaletteTest',
    'collections': 'data.library.Collection data.library.LastCollection data.library.MediaOrganization data.library.PlaylistRepository data.catalog.visibility.VisibilityCollectionLinks data.catalog.CatalogFavoriteBatchTest ui.CollectionReadBridgeTest ui.HomeOrganizationPolicyTest ui.screens.organization. ui.screens.HomeOrganizationVisibilityTest ui.screens.Playlist ui.screens.gallery.GalleryAlbumOrganizationTest ui.screens.music.MusicFavoritesPolicyTest',
    'archive': 'data.archive. data.catalog. data.privatevault. data.storage. data.library. data.playback. ui.screens.Archive ui.screens.Downloads ui.screens.Gallery ui.screens.gallery. ui.screens.music. ui.screens.organization. ui.components.DownloadGroupCard ui.components.Private ui.model.DownloadCatalog ui.Library ui.Collection ui.HomeOrganization ui.FolderVisibility data.MusicDurationBackfillTest data.tasks. ui.screens.DownloadErrorGridPolicyTest ui.components.DownloadFailureDetailsTest ui.navigation.DrawerPolicyTest',
    'privacy': 'data.privatevault. data.catalog.visibility. data.storage. data.archive. data.library. data.playback. ui.components.Private ui.screens.Private ui.screens.GridViewerUiTest ui.screens.gallery.Private ui.navigation. ui.Library ui.Collection ui.HomeOrganization ui.FolderVisibility data.MusicDurationBackfillTest',
    'navigation': 'ui.navigation. ui.components.MediaNavigationDrawer ui.components.Library ui.screens.Library ui.GalleryNavigation ui.HomeOrganization ui.Library ui.RecoveryRefreshModesTest ui.screens.GalleryAlbumNavigationTest ui.screens.GalleryDateNavigationTest ui.screens.gallery.GalleryViewerOverlayPolicyTest ui.screens.gallery.GalleryVideoHeaderActionPlacementTest data.privatevault. data.catalog.visibility. data.playback. data.library.ModeShortcutsTest ui.theme.TransparentNavigationTest data.tasks.',
    'library': 'data.library. data.playback. data.catalog. data.archive. ui.screens.Library ui.screens.Gallery ui.screens.Music ui.screens.Downloads ui.screens.Archive ui.screens.gallery. ui.screens.music. ui.screens.organization. ui.components.Library ui.components.BackgroundLibraryProjection ui.components.DownloadGroupCard ui.Library ui.HomeOrganization ui.Collection data.MusicDurationBackfillTest',
    'playback': 'data.playback. data.library. ui.components.Audio ui.components.PrivatePlayback ui.components.Waveform ui.screens.Music ui.screens.Video ui.screens.music. ui.screens.gallery. ui.navigation.ViewerQueue data.MusicDurationBackfillTest',
    'editors': 'data.editor. ui.components.Editor ui.screens.Photo ui.screens.Video ui.screens.BatchPhoto ui.screens.Ai ui.screens.gallery.Cutout ui.screens.gallery.Magnetic ui.screens.gallery.U2Net ui.navigation.VideoEditorRouteAdmission data.storage. data.playback.',
    'download': 'core.provider. data.download. data.gapi. data.youtube. data.vk. data.vkcommunity. data.instagramprofile. data.share. data.search. data.recognition. data.storage. ui.screens.Downloads ui.components.Download ui.model. ui.screens.SourceBrowser ui.RutubeAuthNavigation data.MusicDurationBackfillTest data.tasks. ui.screens.DownloadErrorGridPolicyTest',
    'storage': 'data.storage. data.privatevault. data.archive. data.catalog. data.library. data.playback. data.PermanentMediaDeletion ui.components.MediaDeletion ui.components.Private ui.components.LibraryMediaDelete ui.screens.Private ui.screens.GridViewerUiTest ui.screens.Archive ui.FolderVisibility ui.Library ui.Collection data.MusicDurationBackfillTest',
    'diagnostics': 'core.diagnostics. core.logging. data.diagnostics. ui.navigation.JournalAccess ui.components.PrivateAccessGate',
}
RISK_LINT = {'archive', 'privacy', 'storage'}
GUARDED_PROFILES = {'archive', 'privacy', 'storage', 'library', 'navigation', 'playback', 'collections'}
DEVICE_COMPILE_PROFILES = {'archive', 'privacy', 'storage', 'editors'}
CI_LABELS = {'ci:verify', 'ci:qualify'} | {prefix + p for prefix in ('ci:quick-', 'ci:qa-') for p in PROFILES}

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def profiles(raw):
    result = tuple(x.strip() for x in raw.split(','))
    if not result or any(x not in PROFILES for x in result) or len(set(result)) != len(result):
        raise ValueError('Unknown, empty or duplicate quick profile: ' + raw)
    return result

def resolve_request(mode, raw, event_label, active_labels, lint):
    if lint not in {'true', 'false'}:
        raise ValueError('Invalid quick_lint')
    active = [x for x in active_labels if x in CI_LABELS or x.startswith(('ci:quick-', 'ci:qa-')) or x == 'ci:audit']
    if event_label:
        if event_label not in CI_LABELS or active != [event_label]:
            raise ValueError('Require exactly one explicit supported CI label')
        want = ('quick' if event_label.startswith('ci:quick-') else
                'qa' if event_label.startswith('ci:qa-') else event_label.removeprefix('ci:'))
        if mode != want:
            raise ValueError('CI mode/label mismatch')
        if want in {'quick', 'qa'}:
            raw = event_label.removeprefix('ci:' + want + '-')
    elif mode not in {'quick', 'qa'} and (raw != 'baseline' or lint == 'true'):
        raise ValueError('Focused options only valid in quick mode')
    names = profiles(raw)
    if mode == 'qa' and names == ('baseline',):
        raise ValueError('QA requires an explicit functional or visual profile')
    return names, mode in {'quick', 'qa'} and (lint == 'true' or bool(set(names) & RISK_LINT))

def request():
    labels = json.loads(os.getenv('ML_CI_LABELS') or 'null') or []
    if not isinstance(labels, list) or not all(isinstance(x, str) for x in labels):
        raise ValueError('Invalid CI labels')
    return resolve_request(os.environ['ML_MODE'], os.getenv('ML_QUICK_PROFILES', 'baseline'),
                           os.getenv('ML_CI_EVENT_LABEL', ''), labels,
                           os.getenv('ML_QUICK_LINT', 'false').lower())

def select(inventory, baseline, names, isolated):
    classes = {key.split('#', 1)[0] for key in inventory}
    base = set(baseline)
    if set(names) & GUARDED_PROFILES:
        base |= {PREFIX + x for x in GUARDS}
    if not base or base - classes:
        raise ValueError('Missing baseline classes: ' + str(sorted(base - classes)))
    selected = set(base)
    for name in names:
        for short in PROFILES[name].split():
            matches = {cls for cls in classes if cls.startswith(PREFIX + short)}
            if not matches:
                raise ValueError('Stale quick profile selector: ' + short)
            selected.update(matches)
    expected = {key: value for key, value in inventory.items() if key.split('#', 1)[0] in selected}
    isolated_classes = {cls for cls, _ in isolated}
    methods = [(cls, method) for cls, method in isolated if cls in selected]
    inventory_isolated = {key for key in expected if key.split('#', 1)[0] in isolated_classes}
    if inventory_isolated != {cls + '#' + method for cls, method in methods}:
        raise ValueError('Isolated source and inventory mismatch')
    bulk = sorted(selected - isolated_classes)
    if not bulk or not expected:
        raise ValueError('Empty focused bulk or inventory')
    return expected, bulk, methods

def make_plan(names, runner):
    inventory = json.loads((ROOT / 'docs/ci/required-jvm-tests.json').read_text())
    baseline = (ROOT / 'scripts/ci/focused-tests.txt').read_text().splitlines()
    return select(inventory, [x.strip() for x in baseline if x.strip()],
                  names, runner.expected_methods('full'))

def validate_markers(markers, methods):
    wanted = ['ML_CI_TEST_EXECUTED task=:app:testDebugUnitTest'] + [
        'ML_CI_TEST_EXECUTED task=:app:mlCiIsolated%03d' % i for i in range(len(methods))]
    if sorted(markers) != sorted(wanted):
        raise ValueError('Missing/duplicate/unexpected focused worker markers')

def run(names, runner, gate):
    required, bulk, methods = make_plan(names, runner)
    shard_root = runner.SHARDS / 'focused'
    shutil.rmtree(shard_root, ignore_errors=True)
    shard_root.mkdir(parents=True)
    plan = {'profiles': list(names), 'required_ids': sorted(required), 'bulk_classes': bulk,
            'isolated_methods': methods}
    (shard_root / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    args = ['-PmlIsolatedComposeBulk=full', '--console=plain']
    for cls in bulk:
        args += ['--tests', cls]
    code, cases = runner.run_shard('bulk', args, shard_root)
    isolated_keys = set(methods)
    wanted_bulk = {tuple(key.split('#', 1)) for key in required} - isolated_keys
    if code or set(cases) != wanted_bulk:
        raise ValueError('Focused bulk failed or exact ID mismatch: missing=%s extra=%s' %
                         (sorted(wanted_bulk-set(cases)), sorted(set(cases)-wanted_bulk)))
    # Check failures/skips before spending time on isolated workers.
    gate.validate_cases(shard_root / 'bulk/xml', {c+'#'+m: required[c+'#'+m] for c,m in wanted_bulk})
    if methods:
        for label, key, exit_code, observed in runner.run_batch(methods, shard_root):
            if exit_code or set(observed) != {key} or key in cases:
                raise ValueError('Invalid focused isolated result: ' + label)
            cases.update(observed)
    runner.merge_cases(cases)
    result = gate.validate_cases(runner.RESULTS, required)
    print(json.dumps(result), flush=True)
    return 0

def validate(names, runner, gate):
    required, bulk, methods = make_plan(names, runner)
    root = runner.SHARDS / 'focused'
    plan = json.loads((root / 'plan.json').read_text())
    if plan != {'profiles': list(names), 'required_ids': sorted(required), 'bulk_classes': bulk,
                'isolated_methods': [list(x) for x in methods]}:
        raise ValueError('Focused plan mismatch')
    out = ROOT / 'ci-output'
    markers = [line.strip() for p in out.glob('focused-*.log') for line in p.read_text().splitlines()
               if line.strip().startswith('ML_CI_TEST_EXECUTED task=')]
    validate_markers(markers, methods)
    report = gate.validate_cases(runner.RESULTS, required)
    report.update({'profiles': list(names), 'isolated_methods': len(methods),
                   'workers_observed': len(markers), 'qualification': False})
    (out / 'focused-coverage.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report), flush=True)

def main():
    names, lint = request()
    action = sys.argv[1]
    if action == 'request':
        if os.environ['ML_MODE'] in {'quick', 'qa'}:
            runner = load('ml_runner', 'scripts/run-jvm-ci.py')
            required, bulk, methods = make_plan(names, runner)
            print(json.dumps({'profiles': names, 'required': len(required),
                              'bulk_classes': len(bulk), 'isolated_methods': len(methods), 'lint': lint}))
        if os.getenv('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
                f.write('lint=' + str(lint).lower() + '\n')
                f.write('device_compile=' + str(bool(set(names) & DEVICE_COMPILE_PROFILES)).lower() + '\n')
        return 0
    if os.environ['ML_MODE'] not in {'quick', 'qa'}:
        raise ValueError('Focused runner requires quick or qa')
    runner = load('ml_runner', 'scripts/run-jvm-ci.py')
    gate = load('ml_gate', 'scripts/ci/gate.py')
    if action == 'run':
        return run(names, runner, gate)
    if action == 'validate':
        validate(names, runner, gate)
        return 0
    raise ValueError('Unknown focused action')

if __name__ == '__main__':
    raise SystemExit(main())
