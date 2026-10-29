"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const kotlin = fs.readFileSync(
  path.join(__dirname, "../app/src/main/java/com/domenota/medialoader/ui/components/HitMozSearchDialog.kt"),
  "utf8"
);
const match = kotlin.match(/private val NATIVE_RESULT_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/);
assert.ok(match, "Native music results script not found");
const source = match[1];
assert.doesNotThrow(() => new vm.Script(source), "Embedded script must be valid JavaScript");

const document = { querySelectorAll: () => [] };
const result = vm.runInNewContext(source, {
  document,
  URL,
  location: { href: "https://ru.hitmoz.org/search?q=test" },
});
const parsed = JSON.parse(result);
assert.deepEqual(Array.from(parsed.tracks), [], "Empty page returns empty tracks array");
assert.equal(parsed.nextPageUrl, "");
console.log("HitMoz native JS syntax and empty-page evaluation: OK");

const image = {
  currentSrc: "",
  getAttribute: (name) => ({
    "data-src": "https://ru.hitmoz.org/images/cover.jpg",
    "src": "https://ru.hitmoz.org/images/placeholder.png",
    "style": "",
  })[name] || "",
};
const row = {
  classList: { contains: () => false },
  querySelector: (selector) => {
    if (selector.includes("track__title")) return { textContent: "Active" };
    if (selector.includes("track__desc")) return { textContent: "Jahballa" };
    return null;
  },
  querySelectorAll: () => [image],
  parentElement: null,
};
const link = {
  parentElement: row,
  getAttribute: () => "/get/music/Active_123456.mp3",
};
const mediaPage = {
  body: {},
  querySelectorAll: (selector) => selector.includes("get/music") ? [link] : [],
};
const coverResult = JSON.parse(vm.runInNewContext(source, {
  document: mediaPage,
  URL,
  location: { href: "https://ru.hitmoz.org/search?q=Active" },
}));
assert.equal(coverResult.tracks.length, 1);
assert.equal(coverResult.tracks[0].artworkUrl, "https://ru.hitmoz.org/images/cover.jpg");
console.log("HitMoz lazy artwork extraction: OK");

const cssCoverRow = {
  ...row,
  querySelectorAll: () => [{ currentSrc: "", getAttribute: () => "" }],
};
const cssCoverLink = { parentElement: cssCoverRow, getAttribute: () => "/get/music/Active_123456.mp3" };
const cssPage = {
  body: {},
  querySelectorAll: (selector) => selector.includes("get/music") ? [cssCoverLink] : [],
};
const cssResult = JSON.parse(vm.runInNewContext(source, {
  document: cssPage,
  URL,
  location: { href: "https://ru.hitmoz.org/search?q=Active" },
  getComputedStyle: () => ({
    backgroundImage: "url(https://static.hitmcdn.com/images/active-cover.jpg)",
  }),
}));
assert.equal(cssResult.tracks[0].artworkUrl, "https://static.hitmcdn.com/images/active-cover.jpg");
console.log("HitMoz computed-style artwork extraction: OK");
