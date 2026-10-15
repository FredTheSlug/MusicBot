'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const Likes = require('../../main/resources/web/likes.js');

const FSI = '\u2068';
const PDI = '\u2069';
const RLO = '\u202E';

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
  assert.deepEqual(like.request, { id: like.request.id, liked: true, path: '/api/likes/add', body: { song: SONG } });

  s = Likes.settle(like.state, SONG.uri, true, true, like.request.id).state;
  assert.equal(Likes.isLiked(s, SONG.uri), true);

  const unlike = Likes.toggle(s, SONG.uri, SONG);
  assert.equal(Likes.isLiked(unlike.state, SONG.uri), false, 'emptied at once');
  assert.deepEqual(unlike.request, { id: unlike.request.id, liked: false, path: '/api/likes/remove', body: { uri: SONG.uri } });
  assert.notEqual(unlike.request.id, like.request.id);
});

test('toggleWithoutASongSendsTheUri', () => {
  const t = Likes.toggle(Likes.createState(), 'ytsearch:abc');
  assert.deepEqual(t.request, { id: t.request.id, liked: true, path: '/api/likes/add', body: { song: { uri: 'ytsearch:abc' } } });
});

test('oneRequestPerUriAtATime', () => {
  // like, unlike, like clicked before any answer: only the first goes out; the ♥ follows every click
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  const b = Likes.toggle(s, 'u', SONG); s = b.state;
  assert.equal(Likes.isLiked(s, 'u'), false);
  const c = Likes.toggle(s, 'u', SONG); s = c.state;
  assert.equal(Likes.isLiked(s, 'u'), true);
  assert.equal(a.request.liked, true);
  assert.equal(b.request, null, 'the like is still on its way');
  assert.equal(c.request, null);

  // another song is not held up
  const other = Likes.toggle(s, 'v', SONG); s = other.state;
  assert.equal(other.request.liked, true);
  assert.notEqual(other.request.id, a.request.id);

  // the server already matches the last click: nothing more to send
  const answer = Likes.settle(s, 'u', true, true, a.request.id);
  assert.equal(answer.request, null);
  assert.equal(answer.reverted, false);
  assert.equal(Likes.isLiked(answer.state, 'u'), true);
  assert.equal(Likes.toggle(answer.state, 'u', SONG).request.liked, false, 'the next click sends again');
});

test('latestToggleWins', () => {
  // like, unlike, like: the like's answer is final, the ♥ stays liked
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  s = Likes.toggle(s, 'u', SONG).state;
  s = Likes.toggle(s, 'u', SONG).state;
  const res = Likes.settle(s, 'u', true, true, a.request.id);
  assert.equal(res.request, null);
  assert.equal(Likes.isLiked(res.state, 'u'), true);
  // a stray answer to a request that is not on its way changes nothing
  assert.equal(Likes.settle(res.state, 'u', true, false, a.request.id).state, res.state);
});

test('anUnlikeLastWins', () => {
  // like, unlike: the like answers, then exactly one unlike follows for the last click
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  s = Likes.toggle(s, 'u', SONG).state;
  const first = Likes.settle(s, 'u', true, true, a.request.id);
  assert.equal(Likes.isLiked(first.state, 'u'), false, 'still the last click while the unlike goes out');
  assert.deepEqual(first.request, { id: first.request.id, liked: false, path: '/api/likes/remove', body: { uri: 'u' } });
  assert.equal(first.reverted, false);
  const second = Likes.settle(first.state, 'u', true, false, first.request.id);
  assert.equal(second.request, null);
  assert.equal(Likes.isLiked(second.state, 'u'), false);
});

/*
 * One song through a list of steps: 'c' a click, 'a' the server answers the request on its way, 'f' that request
 * fails. The fake server applies a request when it answers it; afterwards every request left is answered.
 */
function drive(steps) {
  let s = Likes.createState();
  let server = false;
  let open = null;
  let sent = 0;
  let clicks = 0;
  let lastClick = null;
  const answer = (ok) => {
    const r = open;
    open = null;
    if (ok) server = r.liked;
    const res = Likes.settle(s, 'u', ok, ok ? server : undefined, r.id);
    s = res.state;
    if (res.request) {
      open = res.request;
      sent++;
    }
  };
  for (const step of steps) {
    if (step === 'c') {
      const t = Likes.toggle(s, 'u', SONG);
      s = t.state;
      clicks++;
      lastClick = Likes.isLiked(s, 'u');
      if (t.request) {
        assert.equal(open, null, 'a second request while one is on its way: ' + steps);
        open = t.request;
        sent++;
      }
    } else if (open) {
      answer(step === 'a');
    }
  }
  while (open) answer(true);
  return { shown: Likes.isLiked(s, 'u'), server, lastClick, sent, clicks };
}

function allSteps(letters, maxLength) {
  const out = [];
  const grow = (prefix) => {
    if (prefix.length) out.push(prefix);
    if (prefix.length < maxLength) letters.forEach((l) => grow(prefix + l));
  };
  grow('');
  return out;
}

test('lastClickWinsOnTheServer', () => {
  // every order of clicks and answers up to 9 steps: the server ends on the last click, as the ♥ does
  allSteps(['c', 'a'], 9).filter((st) => st.includes('c')).forEach((steps) => {
    const r = drive(steps);
    assert.equal(r.server, r.lastClick, steps + ': server');
    assert.equal(r.shown, r.lastClick, steps + ': ♥');
    assert.ok(r.sent <= r.clicks, steps + ': never more requests than clicks');
  });
  assert.equal(drive('ccc').sent, 1, 'like, unlike, like before any answer: one request');
  assert.equal(drive('cc').sent, 2, 'like, unlike before any answer: the like, then one unlike');
});

test('failuresNeverLeaveTheHeartWrong', () => {
  // with failures too: once everything is answered the ♥ shows what the server has
  allSteps(['c', 'a', 'f'], 7).filter((st) => st.includes('c')).forEach((steps) => {
    const r = drive(steps);
    assert.equal(r.shown, r.server, steps);
    assert.ok(r.sent <= r.clicks, steps + ': never more requests than clicks');
  });
});

test('failedToggleReverts', () => {
  let s = Likes.createState();
  const like = Likes.toggle(s, 'u', SONG); s = like.state;
  const refused = Likes.settle(s, 'u', false, undefined, like.request.id);
  assert.equal(Likes.isLiked(refused.state, 'u'), false, 'a refused like empties the ♥ again');
  assert.equal(refused.reverted, true, 'and the panel says why');
  assert.equal(refused.request, null);
  s = refused.state;

  const ok = Likes.toggle(s, 'u', SONG); s = Likes.settle(ok.state, 'u', true, true, ok.request.id).state;
  const unlike = Likes.toggle(s, 'u', SONG); s = unlike.state;
  assert.equal(Likes.isLiked(s, 'u'), false);
  const again = Likes.settle(s, 'u', false, undefined, unlike.request.id);
  assert.equal(Likes.isLiked(again.state, 'u'), true, 'a refused unlike fills it again');
  assert.equal(again.reverted, true);
});

test('failedToggleRevertsOnlyIfNoNewerToggle', () => {
  // like (fails) with unlike, like clicked meanwhile: no revert, one like for the last click
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  s = Likes.toggle(s, 'u', SONG).state;
  s = Likes.toggle(s, 'u', SONG).state;
  const failed = Likes.settle(s, 'u', false, undefined, a.request.id);
  assert.equal(failed.reverted, false);
  assert.equal(Likes.isLiked(failed.state, 'u'), true, 'still the last click');
  assert.equal(failed.request.liked, true, 'the last click is sent');
  const done = Likes.settle(failed.state, 'u', true, true, failed.request.id);
  assert.equal(done.request, null);
  assert.equal(Likes.isLiked(done.state, 'u'), true);

  // like (fails) with an unlike clicked meanwhile: the server already has what the last click wants
  s = Likes.createState();
  const b = Likes.toggle(s, 'v', SONG); s = b.state;
  s = Likes.toggle(s, 'v', SONG).state;
  const quiet = Likes.settle(s, 'v', false, undefined, b.request.id);
  assert.equal(quiet.request, null);
  assert.equal(quiet.reverted, false, 'nothing went back: no message');
  assert.equal(Likes.isLiked(quiet.state, 'v'), false);

  // and when the request for the last click fails too, the ♥ goes back
  s = Likes.createState();
  const c = Likes.toggle(s, 'w', SONG); s = c.state;
  s = Likes.toggle(s, 'w', SONG).state;
  s = Likes.toggle(s, 'w', SONG).state;
  const retry = Likes.settle(s, 'w', false, undefined, c.request.id);
  const last = Likes.settle(retry.state, 'w', false, undefined, retry.request.id);
  assert.equal(last.reverted, true);
  assert.equal(last.request, null);
  assert.equal(Likes.isLiked(last.state, 'w'), false);
});

test('settleIgnoresUnknownAndRepeatedAnswers', () => {
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG); s = a.state;
  const none = { state: s, request: null, reverted: false };
  assert.deepEqual(Likes.settle(s, 'other', true, false, a.request.id), none, 'another song');
  assert.deepEqual(Likes.settle(s, 'u', true, false, a.request.id + 99), none, 'a request that never was');
  s = Likes.settle(s, 'u', true, true, a.request.id).state;
  assert.equal(Likes.settle(s, 'u', false, undefined, a.request.id).state, s, 'an answer that already came');
  assert.equal(Likes.isLiked(s, 'u'), true);
});

test('anAnswerWithoutLikedKeepsTheClick', () => {
  let s = Likes.createState();
  const a = Likes.toggle(s, 'u', SONG);
  const res = Likes.settle(a.state, 'u', true, 'yes', a.request.id);
  assert.equal(Likes.isLiked(res.state, 'u'), true);
  assert.equal(res.request, null);
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
  s = Likes.settle(s, 'b', true, true, like.request.id).state;
  s = Likes.settle(s, 'a', true, false, unlike.request.id).state;
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
  s = Likes.settle(s, 'u', true, true, like.request.id).state;
  s = Likes.applyMine(s, [], since);
  assert.equal(Likes.isLiked(s, 'u'), true);
});

test('aFailedToggleFallsBackToWhatMineSaid', () => {
  // mine (sent after the click) says liked; the like itself then fails, say liked from another tab meanwhile
  let s = Likes.createState();
  const like = Likes.toggle(s, 'u', SONG); s = like.state;
  s = Likes.applyMine(s, ['u'], Likes.stamp(s));
  const res = Likes.settle(s, 'u', false, undefined, like.request.id);
  assert.equal(Likes.isLiked(res.state, 'u'), true);
  assert.equal(res.reverted, false, 'the ♥ did not go back: no message');
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
  assert.equal(Likes.likedByText(['a' + RLO + 'b' + PDI + 'c', 'x']), '♥ ' + FSI + 'abc' + PDI + ', ' + FSI + 'x' + PDI);
  assert.equal(Likes.likedByText(['<b>anna</b>']), '♥ ' + FSI + '<b>anna</b>' + PDI, 'text, not markup: the panel sets it with textContent');
});
