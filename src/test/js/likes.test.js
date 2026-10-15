'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const Likes = require('../../main/resources/web/likes.js');

const FSI = '⁨';
const PDI = '⁩';

/** What a person sees: the line without its invisible bidi isolates. */
function visible(text) {
  return text.split(FSI).join('').split(PDI).join('');
}

const SONG = { uri: 'https://youtu.be/a', title: 'A', author: 'X', durationMs: 1000, artworkUrl: null };

test('toggleIsOptimistic', () => {
  let s = Likes.createState();
  assert.equal(Likes.isLiked(s, SONG.uri), false);

  const like = Likes.toggle(s, SONG.uri, SONG);
  assert.equal(Likes.isLiked(like.state, SONG.uri), true, 'filled at once, before any answer');
  assert.equal(Likes.isLiked(s, SONG.uri), false, 'the old state is left as it was');
  assert.deepEqual(like.request, { path: '/api/likes/add', body: { song: SONG } });

  s = Likes.settle(like.state, SONG.uri, true, true, like.id);
  assert.equal(Likes.isLiked(s, SONG.uri), true);

  const unlike = Likes.toggle(s, SONG.uri, SONG);
  assert.equal(Likes.isLiked(unlike.state, SONG.uri), false, 'emptied at once');
  assert.deepEqual(unlike.request, { path: '/api/likes/remove', body: { uri: SONG.uri } });
  assert.notEqual(unlike.id, like.id);
});

test('toggleWithoutASongSendsTheUri', () => {
  const t = Likes.toggle(Likes.createState(), 'ytsearch:abc');
  assert.deepEqual(t.request, { path: '/api/likes/add', body: { song: { uri: 'ytsearch:abc' } } });
});

test('latestToggleWins', () => {
  // like, unlike, like: three requests on their way, answered out of order
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  const b = Likes.toggle(s, 'u', SONG); s = b.state;
  const c = Likes.toggle(s, 'u', SONG); s = c.state;
  assert.equal(Likes.isLiked(s, 'u'), true);

  s = Likes.settle(s, 'u', true, true, c.id);
  assert.equal(Likes.isLiked(s, 'u'), true);
  s = Likes.settle(s, 'u', true, true, a.id);
  assert.equal(Likes.isLiked(s, 'u'), true);
  s = Likes.settle(s, 'u', true, false, b.id); // the unlike's answer comes last: it is not the latest toggle
  assert.equal(Likes.isLiked(s, 'u'), true, 'liked: the last click wins');

  // and every other order of the same three answers
  const orders = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]];
  orders.forEach((order) => {
    let st = Likes.createState();
    const ts = [];
    for (let i = 0; i < 3; i++) { const t = Likes.toggle(st, 'u', SONG); st = t.state; ts.push(t); }
    order.forEach((i) => {
      st = Likes.settle(st, 'u', true, i !== 1, ts[i].id);
      assert.equal(Likes.isLiked(st, 'u'), true, 'order ' + order.join(','));
    });
  });
});

test('anUnlikeLastWins', () => {
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  const b = Likes.toggle(s, 'u', SONG); s = b.state;
  s = Likes.settle(s, 'u', true, false, b.id);
  s = Likes.settle(s, 'u', true, true, a.id);
  assert.equal(Likes.isLiked(s, 'u'), false);
});

test('failedToggleReverts', () => {
  let s = Likes.createState();
  const like = Likes.toggle(s, 'u', SONG); s = like.state;
  s = Likes.settle(s, 'u', false, undefined, like.id);
  assert.equal(Likes.isLiked(s, 'u'), false, 'a refused like empties the ♥ again');

  const ok = Likes.toggle(s, 'u', SONG); s = Likes.settle(ok.state, 'u', true, true, ok.id);
  const unlike = Likes.toggle(s, 'u', SONG); s = unlike.state;
  assert.equal(Likes.isLiked(s, 'u'), false);
  s = Likes.settle(s, 'u', false, undefined, unlike.id);
  assert.equal(Likes.isLiked(s, 'u'), true, 'a refused unlike fills it again');
});

test('failedToggleRevertsOnlyIfNoNewerToggle', () => {
  // like (fails), unlike, like: the newer clicks decide, the old failure changes nothing
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  const b = Likes.toggle(s, 'u', SONG); s = b.state;
  const c = Likes.toggle(s, 'u', SONG); s = c.state;
  s = Likes.settle(s, 'u', false, undefined, a.id);
  assert.equal(Likes.isLiked(s, 'u'), true, 'still the last click while newer ones are on their way');
  s = Likes.settle(s, 'u', true, false, b.id);
  s = Likes.settle(s, 'u', true, true, c.id);
  assert.equal(Likes.isLiked(s, 'u'), true);

  // like (ok), unlike (fails): back to liked, the last answer that went through
  s = Likes.createState();
  const d = Likes.toggle(s, 'v', SONG); s = d.state;
  const e = Likes.toggle(s, 'v', SONG); s = e.state;
  s = Likes.settle(s, 'v', false, undefined, e.id);
  assert.equal(Likes.isLiked(s, 'v'), true, 'the newest click failed: show the one before it');
  s = Likes.settle(s, 'v', true, true, d.id);
  assert.equal(Likes.isLiked(s, 'v'), true);

  // both fail: as before the clicks
  s = Likes.createState();
  const f = Likes.toggle(s, 'w', SONG); s = f.state;
  const g = Likes.toggle(s, 'w', SONG); s = g.state;
  s = Likes.settle(s, 'w', false, undefined, g.id);
  s = Likes.settle(s, 'w', false, undefined, f.id);
  assert.equal(Likes.isLiked(s, 'w'), false);
});

test('settleIgnoresUnknownAndRepeatedAnswers', () => {
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  assert.equal(Likes.settle(s, 'other', true, false, a.id), s, 'another song');
  assert.equal(Likes.settle(s, 'u', true, false, a.id + 99), s, 'a toggle that never was');
  s = Likes.settle(s, 'u', true, true, a.id);
  assert.equal(Likes.settle(s, 'u', false, undefined, a.id), s, 'an answer that already came');
  assert.equal(Likes.isLiked(s, 'u'), true);
});

test('anAnswerWithoutLikedKeepsTheClick', () => {
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG);
  s = Likes.settle(a.state, 'u', true, 'yes', a.id);
  assert.equal(Likes.isLiked(s, 'u'), true);
});

test('mineFillsTheHearts', () => {
  let s = Likes.createState();
  const since = Likes.stamp(s);
  s = Likes.applyMine(s, Likes.fromMine({ uris: ['a', 'b'], count: 2, max: 2000 }), since);
  assert.equal(Likes.isLiked(s, 'a'), true);
  assert.equal(Likes.isLiked(s, 'b'), true);
  assert.equal(Likes.isLiked(s, 'c'), false);

  // a later answer is the whole list: what is gone from it is no longer liked
  s = Likes.applyMine(s, Likes.fromMine({ uris: ['b'] }), Likes.stamp(s));
  assert.equal(Likes.isLiked(s, 'a'), false);
  assert.equal(Likes.isLiked(s, 'b'), true);
});

test('mineDoesNotFlipAToggleInFlight', () => {
  let s = Likes.createState();
  s = Likes.applyMine(s, ['a'], Likes.stamp(s));
  const since = Likes.stamp(s);               // the mine request goes out...
  const like = Likes.toggle(s, 'b', SONG); s = like.state;      // ...then a like
  const unlike = Likes.toggle(s, 'a', SONG); s = unlike.state;  // ...and an unlike
  s = Likes.applyMine(s, ['a'], since);        // its answer knows nothing of them
  assert.equal(Likes.isLiked(s, 'b'), true);
  assert.equal(Likes.isLiked(s, 'a'), false);

  // nor once they are answered, when an answer to a mine request sent before them arrives late
  s = Likes.settle(s, 'b', true, true, like.id);
  s = Likes.settle(s, 'a', true, false, unlike.id);
  s = Likes.applyMine(s, ['a'], since);
  assert.equal(Likes.isLiked(s, 'b'), true);
  assert.equal(Likes.isLiked(s, 'a'), false);

  // a mine request sent after the answers is believed
  s = Likes.applyMine(s, ['a'], Likes.stamp(s));
  assert.equal(Likes.isLiked(s, 'a'), true);
  assert.equal(Likes.isLiked(s, 'b'), false);
});

test('mineSentBeforeAnAnswerDoesNotUndoIt', () => {
  // the like was on its way when mine went out, and its answer came first
  let s = Likes.createState();
  const like = Likes.toggle(s, 'u', SONG); s = like.state;
  const since = Likes.stamp(s);
  s = Likes.settle(s, 'u', true, true, like.id);
  s = Likes.applyMine(s, [], since);
  assert.equal(Likes.isLiked(s, 'u'), true);
});

test('aFailedToggleFallsBackToWhatMineSaid', () => {
  // mine (sent after the click) says liked; the like itself then fails, say liked from another tab meanwhile
  let s = Likes.createState();
  const like = Likes.toggle(s, 'u', SONG); s = like.state;
  s = Likes.applyMine(s, ['u'], Likes.stamp(s));
  s = Likes.settle(s, 'u', false, undefined, like.id);
  assert.equal(Likes.isLiked(s, 'u'), true);
});

test('fromMineIgnoresJunk', () => {
  assert.deepEqual(Likes.fromMine(null), []);
  assert.deepEqual(Likes.fromMine(undefined), []);
  assert.deepEqual(Likes.fromMine('uris'), []);
  assert.deepEqual(Likes.fromMine([]), []);
  assert.deepEqual(Likes.fromMine({}), []);
  assert.deepEqual(Likes.fromMine({ uris: 'a' }), []);
  assert.deepEqual(Likes.fromMine({ uris: { 0: 'a' } }), []);
  assert.deepEqual(Likes.fromMine({ uris: ['a', null, 7, '', '   ', { uri: 'b' }, ['c'], 'd', 'a'] }), ['a', 'd']);
  assert.deepEqual(Likes.fromMine({ uris: [], count: 0, max: 2000 }), []);
});

test('isLikedOfNothingIsFalse', () => {
  const s = Likes.createState();
  assert.equal(Likes.isLiked(s, ''), false);
  assert.equal(Likes.isLiked(s, null), false);
  assert.equal(Likes.isLiked(s, 'toString'), false);
  assert.equal(Likes.isLiked(s, '__proto__'), false);
});

test('likedByText', () => {
  assert.equal(Likes.likedByText([]), '');
  assert.equal(visible(Likes.likedByText(['anna'])), '♥ anna');
  assert.equal(visible(Likes.likedByText(['anna', 'fred'])), '♥ anna, fred');
  assert.equal(visible(Likes.likedByText(['anna', 'fred', 'Zed'])), '♥ anna and 2 others');
  assert.equal(visible(Likes.likedByText(['anna', 'b', 'c', 'd', 'e'])), '♥ anna and 4 others');
});

test('likedByTextIgnoresJunk', () => {
  assert.equal(Likes.likedByText(null), '');
  assert.equal(Likes.likedByText(undefined), '');
  assert.equal(Likes.likedByText('anna'), '');
  assert.equal(Likes.likedByText([null, 3, '', '  ']), '');
  assert.equal(visible(Likes.likedByText([' anna ', null, 'fred'])), '♥ anna, fred');
});

test('likedByNamesAreIsolated', () => {
  // a right-to-left name, or one with its own bidi controls, cannot reorder the rest of the line
  assert.equal(Likes.likedByText(['anna', 'fred']), '♥ ' + FSI + 'anna' + PDI + ', ' + FSI + 'fred' + PDI);
  assert.equal(Likes.likedByText(['a‮b⁩c', 'x']), '♥ ' + FSI + 'abc' + PDI + ', ' + FSI + 'x' + PDI);
  assert.equal(Likes.likedByText(['<b>anna</b>']), '♥ ' + FSI + '<b>anna</b>' + PDI, 'text, not markup: the panel sets it with textContent');
});
