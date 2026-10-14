'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { unseenNotes, shouldAutoShow } = require('../../main/resources/web/whats-new.js');

// Newest first, like patch-notes.json.
const NOTES = [
  { id: '2026-10-14', date: '2026-10-14', title: 'Playlists, rebuilt', items: [] },
  { id: '2026-10-13', date: '2026-10-13', title: 'Move panes in the app', items: [] },
  { id: '2026-10-11', date: '2026-10-11', title: 'Autoplay and more', items: [] }
];

test('firstVisitShowsOnlyNewest', () => {
  assert.deepEqual(unseenNotes(NOTES, null), [NOTES[0]]);
  assert.deepEqual(unseenNotes(NOTES, undefined), [NOTES[0]]);
  assert.deepEqual(unseenNotes(NOTES, ''), [NOTES[0]]);
});

test('showsEverythingNewerThanSeen', () => {
  assert.deepEqual(unseenNotes(NOTES, '2026-10-11'), [NOTES[0], NOTES[1]]);
  assert.deepEqual(unseenNotes(NOTES, '2026-10-13'), [NOTES[0]]);
});

test('nothingWhenUpToDate', () => {
  assert.deepEqual(unseenNotes(NOTES, '2026-10-14'), []);
});

test('unknownSeenIdIsAFirstVisit', () => {
  assert.deepEqual(unseenNotes(NOTES, '2020-01-01'), [NOTES[0]]);
  assert.deepEqual(unseenNotes(NOTES, 42), [NOTES[0]]);
});

test('garbageListShowsNothing', () => {
  assert.deepEqual(unseenNotes(null, null), []);
  assert.deepEqual(unseenNotes(undefined, null), []);
  assert.deepEqual(unseenNotes({}, null), []);
  assert.deepEqual(unseenNotes('notes', null), []);
  assert.deepEqual(unseenNotes([], null), []);
  assert.deepEqual(unseenNotes([{}], null), []);
  assert.deepEqual(unseenNotes([null, 7, { id: 5 }], '2026-10-14'), []);
});

test('entriesWithoutAnIdAreIgnored', () => {
  const list = [{ title: 'no id' }, NOTES[0], null, NOTES[1], { id: 3 }, NOTES[2]];
  assert.deepEqual(unseenNotes(list, null), [NOTES[0]]);
  assert.deepEqual(unseenNotes(list, '2026-10-11'), [NOTES[0], NOTES[1]]);
});

test('noAutoShowWithoutStorage', () => {
  assert.equal(shouldAutoShow(false, [NOTES[0]]), false);
  assert.equal(shouldAutoShow(false, []), false);
  assert.equal(shouldAutoShow(true, []), false);
  assert.equal(shouldAutoShow(true, undefined), false);
  assert.equal(shouldAutoShow(true, [NOTES[0]]), true);
});
