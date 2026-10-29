const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/java/com/domenota/medialoader/ui/screens/SourceBrowserScreen.kt', 'utf8');
const scriptStart = source.indexOf('private val browserScript = """') + 'private val browserScript = """'.length;
const scriptEnd = source.indexOf('""".trimIndent()', scriptStart);
assert.ok(scriptStart > 0 && scriptEnd > scriptStart);
new vm.Script(source.slice(scriptStart, scriptEnd).replace('__SOURCE__', 'youtube'));
const start = source.indexOf('  function youtubeIdFromThumbnail(thumb) {');
const end = source.indexOf('  function positionYoutubeOverlay', start);
assert.ok(start >= 0 && end > start, 'YouTube thumbnail discovery helper exists');
const context = { getComputedStyle: node => ({ backgroundImage: node.backgroundImage || 'none' }) };
vm.createContext(context);
vm.runInContext(source.slice(start, end), context);

function thumbnail(attributes, backgroundImage) {
  return {
    ...attributes,
    backgroundImage,
    getAttribute(name) { return attributes[name] || null; },
  };
}

const id = 'ADlGkXAz1D0';
assert.equal(context.youtubeIdFromThumbnail(thumbnail({ src: `https://i.ytimg.com/vi/${id}/hqdefault.jpg` })), id);
assert.equal(context.youtubeIdFromThumbnail(thumbnail({ currentSrc: `https://i.ytimg.com/vi_webp/${id}/mqdefault.webp` })), id);
assert.equal(context.youtubeIdFromThumbnail(thumbnail({}, `url("https://i.ytimg.com/vi/${id}/hqdefault.jpg")`)), id);
assert.equal(context.youtubeIdFromThumbnail(thumbnail({ 'data-src': `https://img.youtube.com/vi/${id}/0.jpg` })), id);
assert.equal(context.youtubeIdFromThumbnail(thumbnail({ src: `https://evil.example/vi/${id}/hqdefault.jpg` })), null);
assert.equal(context.youtubeIdFromThumbnail(thumbnail({ src: 'https://i.ytimg.com/vi/short/hqdefault.jpg' })), null);
assert.equal(context.youtubeIdFromThumbnail(thumbnail({ currentSrc: { invalid: true } })), null);

const dataStart = source.indexOf('  function youtubeIdFromRendererData(card) {');
const dataEnd = source.indexOf('  function youtubeIdFromThumbnail', dataStart);
assert.ok(dataStart >= 0 && dataEnd > dataStart, 'renderer data discovery helper exists');
vm.runInContext(source.slice(dataStart, dataEnd), context);
const renderer = {
  data: { videoWithContextRenderer: {
    navigationEndpoint: { watchEndpoint: { videoId: id } },
  } },
};
assert.equal(context.youtubeIdFromRendererData(renderer), id);
const cyclic = { data: { videoWithContextRenderer: { videoId: 'too-short' } } };
cyclic.data.self = cyclic.data;
assert.equal(context.youtubeIdFromRendererData(cyclic), null);
const videoUrlStart = source.indexOf('  function youtubeVideoUrl(card) {');
assert.ok(videoUrlStart >= 0 && videoUrlStart < dataStart);
context.valid = () => false;
vm.runInContext(source.slice(videoUrlStart, dataStart), context);
renderer.matches = () => false;
renderer.querySelectorAll = () => [];
renderer.getAttribute = () => null;
renderer.parentElement = null;
assert.equal(context.youtubeVideoUrl(renderer), `https://www.youtube.com/watch?v=${id}`);

const titlesStart = source.indexOf('  var youtubeFeedRoot = null');
const titlesEnd = source.indexOf('  function positionYoutubeOverlay', titlesStart);
assert.ok(titlesStart >= 0 && titlesEnd > titlesStart);
const titleContext = {
  window: { ytInitialData: { contents: [{ videoRenderer: {
    videoId: id, title: { runs: [{ text: 'Little Big - Go Bananas' }] },
  } }] } },
  document: { body: {} },
  innerWidth: 390,
};
vm.createContext(titleContext);
vm.runInContext(source.slice(titlesStart, titlesEnd), titleContext);
const feedTitles = titleContext.youtubeFeedTitles();
titleContext.window.ytInitialData = { contents: [
  { videoRenderer: { videoId: id, title: { runs: [{ text: 'Little Big - Go Bananas' }] } } },
  { title: { runs: { unexpected: true } } },
] };
assert.equal(titleContext.youtubeFeedTitles().length, 1, 'malformed unrelated title must not abort feed scan');
const cardText = {
  innerText: 'Little Big - Go Bananas\n2 млн просмотров',
  parentElement: titleContext.document.body,
  getBoundingClientRect: () => ({ width: 340, height: 250 }),
};
const thumbText = {
  innerText: '', parentElement: cardText,
  getBoundingClientRect: () => ({ width: 320, height: 180 }),
};
assert.equal(titleContext.youtubeIdFromFeedTitle(thumbText, feedTitles), id);
assert.equal(titleContext.youtubeIdFromFeedTitle({ ...thumbText, parentElement: {
  ...cardText, innerText: 'A different video',
} }, feedTitles), null);
titleContext.window.ytInitialData.contents.push({ videoRenderer: {
  videoId: 'BBBBBBBBBBB', title: { simpleText: 'Little Big - Go Bananas' },
} });
titleContext.window.ytInitialData = { contents: titleContext.window.ytInitialData.contents };
assert.equal(titleContext.youtubeFeedTitles().length, 0, 'ambiguous titles are ignored');

const installStart = source.indexOf('  function youtubeDownloadIcon() {');
const installEnd = source.indexOf('  var lastYoutubeDebugAt', installStart);
assert.ok(installStart >= 0 && installEnd > installStart);
const buttons = [];
const picked = [];
const overlayContext = {
  valid: url => url === `https://www.youtube.com/watch?v=${id}`,
  positionYoutubeOverlay: () => true,
  send: url => picked.push(url),
  document: {
    querySelectorAll: () => buttons,
    createElement: () => ({
      style: {}, dataset: {},
      setAttribute() {},
      appendChild(child) { this.icon = child; },
      addEventListener(name, listener) { this[name] = listener; },
      set innerHTML(_) { throw new TypeError('TrustedHTML required'); },
    }),
    createElementNS: (_, name) => ({
      name, attributes: {},
      setAttribute(key, value) { this.attributes[key] = value; },
      appendChild(child) { this.child = child; },
    }),
    body: { appendChild(button) { buttons.push(button); } },
  },
};
vm.createContext(overlayContext);
vm.runInContext(source.slice(installStart, installEnd), overlayContext);
overlayContext.installYoutubeOverlay({}, `https://www.youtube.com/watch?v=${id}`);
assert.equal(buttons.length, 1);
assert.equal(buttons[0].icon.name, 'svg');
assert.equal(buttons[0].icon.child.name, 'path');
const stopped = [];
buttons[0].click({
  preventDefault() { stopped.push('default'); },
  stopPropagation() { stopped.push('bubble'); },
  stopImmediatePropagation() { stopped.push('immediate'); },
});
assert.deepEqual(stopped, ['default', 'bubble', 'immediate']);
assert.deepEqual(picked, [`https://www.youtube.com/watch?v=${id}`]);
overlayContext.installYoutubeOverlay({}, 'https://evil.example/watch?v=ADlGkXAz1D0');
assert.equal(buttons.length, 1);

const fallbackStart = source.indexOf('    var thumbnailNodes = document.querySelectorAll', installEnd);
const fallbackEnd = source.indexOf('    previous.forEach', fallbackStart);
assert.ok(fallbackStart >= 0 && fallbackEnd > fallbackStart);
const noAnchorCard = thumbnail({ src: `https://i.ytimg.com/vi/${id}/hqdefault.jpg` });
noAnchorCard.parentElement = null;
noAnchorCard.getBoundingClientRect = () => ({ width: 320, height: 180 });
const discovered = [];
const feedContext = {
  document: { querySelectorAll: () => [noAnchorCard] },
  youtubeIdFromThumbnail: context.youtubeIdFromThumbnail,
  youtubeFeedTitles: () => [],
  youtubeThumbnail: () => null,
  installYoutubeOverlay: (surface, url) => discovered.push({ surface, url }),
  innerWidth: 390,
};
vm.createContext(feedContext);
vm.runInContext(source.slice(fallbackStart, fallbackEnd), feedContext);
assert.equal(discovered.length, 1);
assert.equal(discovered[0].surface, noAnchorCard);
assert.equal(discovered[0].url, `https://www.youtube.com/watch?v=${id}`);

const probeStart = source.indexOf('    setTimeout(function() {\n      try {\n        var flags = window.ytcfg');
const probeEnd = source.indexOf('    var youtubeFrame = 0;', probeStart);
assert.ok(probeStart >= 0 && probeEnd > probeStart, 'local token probe exists');
const secretToken = 'private_token_should_never_be_logged';
const probeLogs = [];
let proofReadyCalls = 0;
const probeContext = {
  window: {
    ytcfg: { get: key => key === 'EXPERIMENT_FLAGS' ? { bg_st_hr: true } : 'visitor-binding' },
    'havuokmhhs-0': { bevasrs: { wpc: async () => ({ mws: async () => secretToken }) } },
  },
  MediaLoaderBridge: {
    debug: message => probeLogs.push(message),
    localProofReady: () => { proofReadyCalls++; },
  },
  setTimeout: callback => callback(),
};
probeContext.window.top = probeContext.window;
vm.createContext(probeContext);
vm.runInContext(source.slice(probeStart, probeEnd), probeContext);
setImmediate(() => {
  assert.ok(probeLogs.includes('YouTube local token mint: available'));
  assert.equal(proofReadyCalls, 1);
  assert.ok(probeLogs.every(line => !line.includes(secretToken) && !line.includes('visitor-binding')));
  console.log('YouTube thumbnail, renderer data, card button and token privacy checks passed');
});
