/*
 * Liked songs: what each ♥ shows, and the "♥ liked by …" line. Pure functions, no DOM or requests: index.html
 * sends the requests and paints the buttons; this file decides what they show.
 * Loaded by the panel as a classic script (window.Likes) and by Node for tests (module.exports).
 *
 * A state is never changed in place: every function that changes it returns a new one. It holds
 *   known    uri -> true for every song the server last said you liked (GET /api/likes/mine, and answers)
 *   toggles  uri -> the clicks on that song still waiting for an answer, oldest first: {id, want, done, ok, liked}
 *   marks    uri -> the clock when that song was last clicked or answered
 *   clock    counts clicks and answers; a click's id is the clock it got
 */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    else root.Likes = api;
}(typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    var FSI = '⁨'; // first strong isolate ...
    var PDI = '⁩'; // ... pop directional isolate
    // Embeddings, overrides and isolates inside a name could leak out of its isolate; they are dropped.
    var BIDI_CONTROLS = /[‪-‮⁦-⁩]/g;

    function map() {
        return Object.create(null);
    }

    function copy(from) {
        var to = map();
        for (var k in from) to[k] = from[k];
        return to;
    }

    function createState() {
        return { known: map(), toggles: map(), marks: map(), clock: 0 };
    }

    function next(state) {
        return { known: state.known, toggles: state.toggles, marks: state.marks, clock: state.clock };
    }

    /*
     * The newest click that has not failed decides: on its way, what it asked for; answered, what the server said.
     * When every click on the song failed (or there were none), what the server last said.
     */
    function shown(known, list, uri) {
        for (var i = list ? list.length - 1 : -1; i >= 0; i--) {
            var t = list[i];
            if (!t.done) return t.want;
            if (t.ok) return t.liked;
        }
        return known[uri] === true;
    }

    function isLiked(state, uri) {
        if (typeof uri !== 'string' || !uri) return false;
        return shown(state.known, state.toggles[uri], uri);
    }

    /*
     * A click on a ♥: it flips at once. Returns the new state, the request to send ({path, body}) and the click's
     * id, which goes back to settle() with the answer. `song` ({uri, title, author, durationMs, artworkUrl}) is what
     * a like sends; without it a like sends just the uri.
     */
    function toggle(state, uri, song) {
        var want = !isLiked(state, uri);
        var s = next(state);
        var id = s.clock = state.clock + 1;
        s.toggles = copy(state.toggles);
        s.toggles[uri] = (state.toggles[uri] || []).concat([{ id: id, want: want, done: false, ok: false, liked: false }]);
        s.marks = copy(state.marks);
        s.marks[uri] = id;
        var request = want
            ? { path: '/api/likes/add', body: { song: song || { uri: uri } } }
            : { path: '/api/likes/remove', body: { uri: uri } };
        return { state: s, request: request, id: id };
    }

    /*
     * The answer to click `id`: ok, and the server's `liked`. The latest click wins: an older click's answer never
     * overrides a newer click, and a failed click goes back to what was shown before it only when no newer click
     * happened. An unknown id, or a second answer to the same click, changes nothing.
     */
    function settle(state, uri, ok, serverLiked, id) {
        var list = state.toggles[uri];
        if (!list) return state;
        var at = -1;
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === id && !list[i].done) at = i;
        }
        if (at < 0) return state;
        var t = list[at];
        var answered = { id: t.id, want: t.want, done: true, ok: !!ok, liked: typeof serverLiked === 'boolean' ? serverLiked : t.want };
        var nextList = list.slice();
        nextList[at] = answered;
        var s = next(state);
        s.clock = state.clock + 1;
        s.marks = copy(state.marks);
        s.marks[uri] = s.clock;
        s.toggles = copy(state.toggles);
        var waiting = nextList.some(function (x) { return !x.done; });
        if (waiting) {
            s.toggles[uri] = nextList;
        } else {
            delete s.toggles[uri];
            s.known = copy(state.known);
            if (shown(state.known, nextList, uri)) s.known[uri] = true;
            else delete s.known[uri];
        }
        return s;
    }

    /* Read just before asking for /api/likes/mine; the answer goes to applyMine() with it. */
    function stamp(state) {
        return state.clock;
    }

    /*
     * The answer to /api/likes/mine (uris from fromMine()) asked at `since` (stamp()). It is the whole list, except
     * for songs clicked or answered after it was asked: those keep what this panel knows, so a ♥ never flips back
     * because of an answer older than the click.
     */
    function applyMine(state, uris, since) {
        var known = map();
        (uris || []).forEach(function (uri) { known[uri] = true; });
        for (var uri in state.marks) {
            if (state.marks[uri] <= since) continue;
            if (state.known[uri] === true) known[uri] = true;
            else delete known[uri];
        }
        var s = next(state);
        s.known = known;
        return s;
    }

    /* The uris of a /api/likes/mine answer: non-blank strings, each once. Anything else is ignored. */
    function fromMine(json) {
        if (!json || typeof json !== 'object' || !Array.isArray(json.uris)) return [];
        var seen = map();
        return json.uris.filter(function (uri) {
            if (typeof uri !== 'string' || !uri.trim() || seen[uri]) return false;
            seen[uri] = true;
            return true;
        });
    }

    /*
     * "♥ anna", "♥ anna, fred", "♥ anna and 3 others"; "" when nobody (else) liked it. Each name is bidi-isolated,
     * so a right-to-left name cannot reorder the rest of the line. Plain text: the panel sets it with textContent.
     */
    function likedByText(names) {
        if (!Array.isArray(names)) return '';
        var shownNames = names
            .filter(function (n) { return typeof n === 'string'; })
            .map(function (n) { return n.replace(BIDI_CONTROLS, '').trim(); })
            .filter(Boolean)
            .map(function (n) { return FSI + n + PDI; });
        if (!shownNames.length) return '';
        if (shownNames.length <= 2) return '♥ ' + shownNames.join(', ');
        return '♥ ' + shownNames[0] + ' and ' + (shownNames.length - 1) + ' others';
    }

    return {
        createState: createState,
        isLiked: isLiked,
        toggle: toggle,
        settle: settle,
        stamp: stamp,
        applyMine: applyMine,
        fromMine: fromMine,
        likedByText: likedByText
    };
}));
