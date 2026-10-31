#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(
  'app/src/main/java/com/domenota/medialoader/ui/screens/SourceBrowserScreen.kt',
  'utf8');
const start = source.indexOf('  var stalledBackdrop = null');
const end = source.indexOf('  var recoveryUntil =', start);
assert(start >= 0 && end > start, 'Instagram recovery function must be injected into browserScript');
const recovery = source.slice(start, end);
assert(recovery.includes('function recoverInstagramStalledBackdrop()'));

function makeFixture({ route = '/accounts/privacy/', sourceId = 'instagram',
  interactive = false, hasDialog = false, scrim = true } = {}) {
  let now = 0;
  const logs = [];
  const dimensions = { width: 400, height: 800, top: 0, bottom: 800 };
  const body = { style: {}, isConnected: true };
  const root = { style: {}, isConnected: true };
  const overlay = {
    parentElement: body,
    isConnected: scrim,
    textContent: interactive ? 'Это настоящее окно с управляющими кнопками' : '',
    matches: () => false,
    querySelector: () => interactive ? {} : null,
    getBoundingClientRect: () => dimensions,
    remove() { this.isConnected = false; },
  };
  const modal = {
    isConnected: true,
    getBoundingClientRect: () => ({ width: 300, height: 500, top: 40, bottom: 540 }),
  };
  const context = {
    source: sourceId,
    innerWidth: 400,
    innerHeight: 800,
    location: { pathname: route },
    Date: { now: () => now },
    document: {
      body, documentElement: root, visibilityState: 'visible',
      querySelectorAll: () => hasDialog ? [modal] : [],
      querySelector: () => hasDialog ? modal : null,
      elementFromPoint: () => overlay.isConnected ? overlay : null,
    },
    MediaLoaderBridge: { debug: msg => logs.push(msg) },
    getComputedStyle: el => el === overlay ? {
      display: 'block', visibility: 'visible', position: 'fixed',
      pointerEvents: 'auto', backgroundColor: 'rgba(0, 0, 0, 0.6)',
      backdropFilter: 'none', overflowY: 'visible',
    } : {
      display: 'block', visibility: 'visible', position: 'static',
      pointerEvents: 'auto', backgroundColor: 'transparent',
      backdropFilter: 'none', overflowY: 'hidden',
    },
  };
  vm.createContext(context);
  vm.runInContext(recovery, context, { timeout: 2000 });
  return {
    context, overlay, logs,
    scan(timestamp) {
      now = timestamp;
      vm.runInContext('recoverInstagramStalledBackdrop()', context, { timeout: 2000 });
    },
  };
}

// Regression: Instagram settings routes are no longer excluded from recovery.
const stuck = makeFixture();
stuck.scan(0);
stuck.scan(3200);
assert(stuck.overlay.isConnected, 'A transient overlay must survive');
stuck.scan(5000);
assert.equal(stuck.overlay.isConnected, false, 'A stale orphaned backdrop must clear');
assert.equal(stuck.logs.length, 1);
assert.equal(stuck.context.document.body.style.overflow, 'auto');

// Do not dismiss login/consent or controls, even after several seconds.
const dialog = makeFixture({ hasDialog: true });
dialog.scan(0);
dialog.scan(10000);
assert(dialog.overlay.isConnected, 'Visible genuine dialog must remain open');
const controls = makeFixture({ interactive: true });
controls.scan(0);
controls.scan(10000);
assert(controls.overlay.isConnected, 'Interactive full-screen overlay must remain open');

// A route change restarts persistence detection rather than clearing immediately.
const route = makeFixture();
route.scan(0);
route.context.location.pathname = '/accounts/settings/';
route.scan(8000);
assert(route.overlay.isConnected, 'Navigating must restart orphan timer');
route.scan(13000);
assert.equal(route.overlay.isConnected, false);

const other = makeFixture({ sourceId: 'youtube' });
other.scan(0);
other.scan(15000);
assert(other.overlay.isConnected, 'Recovery must not touch other sources');
console.log('Instagram recovery: stale settings scrim, real dialogs, controls, navigation and source guard OK');
