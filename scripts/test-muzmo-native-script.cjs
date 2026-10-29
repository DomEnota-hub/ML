"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const file = fs.readFileSync(path.join(__dirname,
  "../app/src/main/java/com/domenota/medialoader/ui/components/MuzmoPageScripts.kt"), "utf8");
function read(name) {
  const re = new RegExp("val " + name + " = \"\"\"([\\s\\S]*?)\"\"\"\\.trimIndent\\(\\)");
  const match = file.match(re);
  assert.ok(match, name + " browser script must exist");
  assert.doesNotThrow(() => new vm.Script(match[1]));
  return match[1];
}
const search = read("SEARCH");
const detail = read("DETAIL");
const base = "https://rmr.muzmo.cc/search?q=Eva";
function run(source, document, href = base) {
  return JSON.parse(vm.runInNewContext(source, { URL, document, location: { href } }));
}
assert.deepEqual(Array.from(run(search, { querySelectorAll: () => [] }).tracks), []);

const row = {
  parentElement: null,
  innerText: "Ева Власова\nВчера\n02:57\n320Kbps",
  querySelectorAll: () => [1],
  querySelector: () => null,
};
const anchor = {
  parentElement: row,
  getAttribute: (name) => name === "href" ? "/info?id=82447323" : "",
};
const doc = { body: {}, querySelectorAll: () => [anchor] };
row.parentElement = doc.body;
const songs = run(search, doc);
assert.equal(songs.tracks.length, 1);
assert.equal(songs.tracks[0].artist, "Ева Власова");
assert.equal(songs.tracks[0].title, "Вчера");
assert.equal(songs.tracks[0].duration, "02:57");
assert.equal(songs.tracks[0].infoUrl, "https://rmr.muzmo.cc/info?id=82447323");

// Real search rows have two /info links for one song (title and download),
// while the clock is a sibling cell: .item-song .song-time.
const clock = { innerText: "02:12\n320Kbps", textContent: "02:12 320Kbps" };
const songRow = {
  parentElement: doc.body,
  innerText: "DJ NARODNII\nТы моя.\n02:12\n320Kbps",
  textContent: "DJ NARODNII Ты моя. 02:12 320Kbps",
  querySelectorAll: () => [songLink, downloadLink],
  querySelector: (selector) => selector === ".song-time" ? clock : null,
  getAttribute: () => null,
};
const titleCell = {
  parentElement: songRow,
  innerText: "DJ NARODNII\nТы моя.",
  textContent: "DJ NARODNII Ты моя.",
  querySelectorAll: () => [songLink],
  querySelector: () => null,
  getAttribute: () => null,
};
const songLink = {
  parentElement: titleCell,
  getAttribute: (name) => name === "href" ? "/info?id=82447323" : "",
  closest: (selector) => selector === ".item-song" ? songRow : null,
};
const downloadLink = {
  parentElement: songRow,
  getAttribute: (name) => name === "href" ? "/info?id=82447323" : "",
  closest: (selector) => selector === ".item-song" ? songRow : null,
};
const twinLinkDoc = { body: doc.body, querySelectorAll: () => [songLink, downloadLink] };
const twinLinkSongs = run(search, twinLinkDoc);
assert.equal(twinLinkSongs.tracks[0].duration, "02:12");

const audio = { getAttribute: () => "/get/music/20261006/Eva_Vlasova_-_Vchera_82447323.mp3" };
const infoDoc = { querySelectorAll: () => [audio] };
assert.equal(run(detail, infoDoc, "https://rmr.muzmo.cc/info?id=82447323").downloadUrl,
  "https://rmr.muzmo.cc/get/music/20261006/Eva_Vlasova_-_Vchera_82447323.mp3");
console.log("Muzmo synthetic DOM search/detail extraction: OK");
