'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const PlaylistRows = require('../../main/resources/web/playlist-rows.js');

const FSI = '\u2068';
const PDI = '\u2069';
const RLO = '\u202E';

/** What a person sees: the line without its invisible bidi isolates. */
function visible(text) {
  return text.split(FSI).join('').split(PDI).join('');
}

/** An ISO time that is the given day at local noon, so the day reads the same in every time zone. */
function noon(year, month, day) {
  return new Date(year, month - 1, day, 12, 0, 0).toISOString();
}

const NOW = new Date(2026, 9, 20, 9, 0, 0); // Oct 20, 2026

test('addedByNamesWhoAndWhen', () => {
  const text = PlaylistRows.addedByText({ addedBy: 'fred', addedAt: noon(2026, 10, 13) }, NOW);
  assert.equal(visible(text), 'added by fred · Oct 13');
  assert.ok(text.includes(FSI + 'fred' + PDI), 'the name is bidi-isolated');
});

test('addedByLeavesOutWhatIsNotKnown', () => {
  assert.equal(visible(PlaylistRows.addedByText({ addedBy: 'fred', addedAt: '' }, NOW)), 'added by fred');
  assert.equal(visible(PlaylistRows.addedByText({ addedBy: 'fred' }, NOW)), 'added by fred');
  assert.equal(PlaylistRows.addedByText({ addedBy: '', addedAt: noon(2026, 10, 13) }, NOW), 'added Oct 13');
  assert.equal(PlaylistRows.addedByText({ addedAt: noon(2026, 10, 13) }, NOW), 'added Oct 13');
  assert.equal(PlaylistRows.addedByText({ addedBy: '', addedAt: '' }, NOW), '');
  assert.equal(PlaylistRows.addedByText({}, NOW), '');
  assert.equal(PlaylistRows.addedByText(null, NOW), '');
  assert.equal(PlaylistRows.addedByText(undefined, NOW), '');
});

test('addedByIgnoresADateItCannotRead', () => {
  assert.equal(visible(PlaylistRows.addedByText({ addedBy: 'fred', addedAt: 'yesterday' }, NOW)), 'added by fred');
  assert.equal(PlaylistRows.addedByText({ addedAt: 'not a date' }, NOW), '');
  assert.equal(PlaylistRows.addedByText({ addedAt: 12345 }, NOW), '', 'only an ISO string counts');
});

test('addedByShowsTheYearOnlyWhenItIsNotThisYear', () => {
  const song = { addedBy: 'fred', addedAt: noon(2025, 10, 13) };
  assert.equal(visible(PlaylistRows.addedByText(song, NOW)), 'added by fred · Oct 13, 2025');
  assert.equal(PlaylistRows.addedByText({ addedAt: noon(2025, 1, 2) }, NOW), 'added Jan 2, 2025');
  assert.equal(PlaylistRows.addedByText({ addedAt: noon(2026, 1, 2) }, NOW), 'added Jan 2');
  // just over New Year: the day before is last year's
  const january = new Date(2027, 0, 2, 9, 0, 0);
  assert.equal(PlaylistRows.addedByText({ addedAt: noon(2026, 12, 30) }, january), 'added Dec 30, 2026');
  assert.equal(PlaylistRows.addedByText({ addedAt: noon(2027, 1, 1) }, january), 'added Jan 1');
});

test('addedByMonthsAreTheSameInEveryLanguageSetting', () => {
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  months.forEach((name, i) => {
    assert.equal(PlaylistRows.addedByText({ addedAt: noon(2026, i + 1, 9) }, NOW), 'added ' + name + ' 9');
  });
});

test('addedByWithoutAClockUsesTheCurrentOne', () => {
  const today = new Date().toISOString();
  const text = PlaylistRows.addedByText({ addedBy: 'fred', addedAt: today });
  assert.ok(/^added by .*fred.* · [A-Z][a-z]{2} \d{1,2}$/.test(text), text);
});

test('addedByNameIsTextOnly', () => {
  const html = PlaylistRows.addedByText({ addedBy: '<img src=x onerror=alert(1)>' }, NOW);
  assert.equal(visible(html), 'added by <img src=x onerror=alert(1)>', 'left as it is: the panel sets it with textContent');
  assert.ok(html.startsWith('added by ' + FSI) && html.endsWith(PDI), 'isolated');
});

test('addedByNameCannotReorderTheRestOfTheLine', () => {
  const text = PlaylistRows.addedByText({ addedBy: 'ann' + RLO + 'a' + PDI + 'b ' + FSI, addedAt: noon(2026, 10, 13) }, NOW);
  assert.equal(visible(text), 'added by annab · Oct 13', 'embeddings, overrides and isolates inside the name are dropped');
  assert.ok(!text.includes(RLO));
  const opened = text.split(FSI).length - 1;
  const closed = text.split(PDI).length - 1;
  assert.equal(opened, 1);
  assert.equal(closed, 1);
});

test('addedByNameOfOnlyBidiControlsIsNoName', () => {
  assert.equal(PlaylistRows.addedByText({ addedBy: RLO + FSI + PDI + '  ' }, NOW), '');
  assert.equal(PlaylistRows.addedByText({ addedBy: 42 }, NOW), '');
});

function labels(targets) {
  return targets.map((t) => t.label);
}

test('moveTargetsInTheMiddle', () => {
  assert.deepEqual(PlaylistRows.moveTargets(2, 5), [
    { label: 'Move up', toIndex: 1 },
    { label: 'Move down', toIndex: 3 },
    { label: 'Move to top', toIndex: 0 },
    { label: 'Move to bottom', toIndex: 4 }
  ]);
});

test('moveTargetsOfTheFirstRowOnlyGoDown', () => {
  assert.deepEqual(PlaylistRows.moveTargets(0, 5), [
    { label: 'Move down', toIndex: 1 },
    { label: 'Move to bottom', toIndex: 4 }
  ]);
});

test('moveTargetsOfTheLastRowOnlyGoUp', () => {
  assert.deepEqual(PlaylistRows.moveTargets(4, 5), [
    { label: 'Move up', toIndex: 3 },
    { label: 'Move to top', toIndex: 0 }
  ]);
});

test('moveTargetsOfASingleRowAreNone', () => {
  assert.deepEqual(PlaylistRows.moveTargets(0, 1), []);
});

test('moveTargetsOfTwoRows', () => {
  assert.deepEqual(PlaylistRows.moveTargets(0, 2), [
    { label: 'Move down', toIndex: 1 },
    { label: 'Move to bottom', toIndex: 1 }
  ]);
  assert.deepEqual(PlaylistRows.moveTargets(1, 2), [
    { label: 'Move up', toIndex: 0 },
    { label: 'Move to top', toIndex: 0 }
  ]);
});

test('moveTargetsOnlyLeaveOutWhatWouldDoNothing', () => {
  // the second row: "Move up" and "Move to top" both land on the first place, and both do something
  assert.deepEqual(labels(PlaylistRows.moveTargets(1, 4)), ['Move up', 'Move down', 'Move to top', 'Move to bottom']);
  assert.deepEqual(labels(PlaylistRows.moveTargets(2, 4)), ['Move up', 'Move down', 'Move to top', 'Move to bottom']);
});

test('moveTargetsNeverMoveToWhereTheSongIs', () => {
  for (let length = 0; length <= 7; length++) {
    for (let index = 0; index < length; index++) {
      PlaylistRows.moveTargets(index, length).forEach((t) => {
        assert.notEqual(t.toIndex, index, t.label + ' at ' + index + ' of ' + length);
        assert.ok(t.toIndex >= 0 && t.toIndex < length, t.label + ' stays inside the list');
      });
    }
  }
});

test('moveTargetsGiveTheFinalPositionTheServerExpects', () => {
  // /songs/move takes the final index: take the song out, put it back at toIndex
  const list = ['a', 'b', 'c', 'd', 'e'];
  function after(index, label) {
    const target = PlaylistRows.moveTargets(index, list.length).find((t) => t.label === label);
    assert.ok(target, label + ' is offered at ' + index);
    const moved = list.slice();
    const song = moved.splice(index, 1)[0];
    moved.splice(target.toIndex, 0, song);
    return moved.join('');
  }
  assert.equal(after(2, 'Move up'), 'acbde');
  assert.equal(after(2, 'Move down'), 'abdce');
  assert.equal(after(2, 'Move to top'), 'cabde');
  assert.equal(after(2, 'Move to bottom'), 'abdec');
  assert.equal(after(0, 'Move down'), 'bacde');
  assert.equal(after(4, 'Move up'), 'abced');
});

test('moveTargetsOfAnImpossiblePlaceAreNone', () => {
  assert.deepEqual(PlaylistRows.moveTargets(-1, 5), []);
  assert.deepEqual(PlaylistRows.moveTargets(5, 5), []);
  assert.deepEqual(PlaylistRows.moveTargets(1.5, 5), []);
  assert.deepEqual(PlaylistRows.moveTargets(NaN, 5), []);
  assert.deepEqual(PlaylistRows.moveTargets(0, 0), []);
  assert.deepEqual(PlaylistRows.moveTargets('1', 5), []);
  assert.deepEqual(PlaylistRows.moveTargets(undefined, undefined), []);
});
