/*
 * Liked songs: what each ♥ shows, which like/unlike request to send, the "♥ liked by …" line, and the names and
 * order of the Liked songs section. Pure functions, no DOM or requests: index.html sends the requests and paints
 * the buttons; this file decides.
 * Loaded by the panel as a classic script (window.Likes) and by Node for tests (module.exports).
 *
 * At most one like/unlike request per song is on its way at a time, so the server gets them in click order. A click
 * meanwhile only changes what is wanted (the ♥ flips at once); when the request is answered and the server differs
 * from the last click, exactly one more request goes for it. The last click wins, on the page and on the server.
 *
 * A state is never changed in place: every function that changes it returns a new one. It holds
 *   known    uri -> true for every song the server last said you liked (GET /api/likes/mine, and answers)
 *   open     uri -> {want, song, sent: {id, liked}, lastClick} while a request for that song is on its way:
 *            want = the last click, sent = the request, lastClick = the clock of the last click
 *   marks    uri -> the clock when that song was last clicked or answered
 *   clock    counts clicks, requests and answers; a request's id is the clock it got
 */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    else root.Likes = api;
}(typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    var FSI = '\u2068'; // first strong isolate ...
    var PDI = '\u2069'; // ... pop directional isolate
    // Embeddings, overrides and isolates inside a name could leak out of its isolate; they are dropped.
    var BIDI_CONTROLS = /[\u202A-\u202E\u2066-\u2069]/g;

    function map() {
        return Object.create(null);
    }

    function copy(from) {
        var to = map();
        for (var k in from) to[k] = from[k];
        return to;
    }

    function createState() {
        return { known: map(), open: map(), marks: map(), clock: 0 };
    }

    // A copy of the state with the clock moved on, `uri` marked, and its own `open`, ready to change.
    function step(state, uri) {
        var s = { known: state.known, open: copy(state.open), marks: copy(state.marks), clock: state.clock + 1 };
        s.marks[uri] = s.clock;
        return s;
    }

    function isLiked(state, uri) {
        if (typeof uri !== 'string' || !uri) return false;
        var open = state.open[uri];
        return open ? open.want : state.known[uri] === true;
    }

    // {id, liked, path, body}: a like sends `song` ({uri, title, author, durationMs, artworkUrl}), or just the uri.
    function request(id, uri, liked, song) {
        return liked
            ? { id: id, liked: true, path: '/api/likes/add', body: { song: song || { uri: uri } } }
            : { id: id, liked: false, path: '/api/likes/remove', body: { uri: uri } };
    }

    /*
     * A click on a ♥: it flips at once. Returns {state, request}: the request to send, or null while one for this
     * song is still on its way (the click is then only remembered, and settle() sends for it if needed).
     */
    function toggle(state, uri, song) {
        var want = !isLiked(state, uri);
        var s = step(state, uri);
        var open = state.open[uri];
        if (open) {
            s.open[uri] = { want: want, song: song || open.song, sent: open.sent, lastClick: s.clock };
            return { state: s, request: null };
        }
        s.open[uri] = { want: want, song: song || null, sent: { id: s.clock, liked: want }, lastClick: s.clock };
        return { state: s, request: request(s.clock, uri, want, song) };
    }

    /*
     * The answer to request `id`: ok, and the server's `liked` (a non-boolean counts as what was asked for).
     * Returns {state, request, reverted}:
     *   request   exactly one more request when the server now differs from the last click, else null
     *   reverted  the request failed with no click after it, and the ♥ went back: the panel says why
     * A failed request is followed up only when there were clicks after it that the server does not match yet.
     * An answer to anything but the song's request on its way changes nothing.
     */
    function settle(state, uri, ok, serverLiked, id) {
        var open = state.open[uri];
        if (!open || open.sent.id !== id) return { state: state, request: null, reverted: false };
        var s = step(state, uri);
        var was = state.known[uri] === true;
        var now = ok ? (typeof serverLiked === 'boolean' ? serverLiked : open.sent.liked) : was;
        if (now !== was) {
            s.known = copy(state.known);
            if (now) s.known[uri] = true;
            else delete s.known[uri];
        }
        var clickedSince = open.lastClick > open.sent.id;
        if (open.want === now || (!ok && !clickedSince)) {
            delete s.open[uri];
            return { state: s, request: null, reverted: !ok && open.want !== now };
        }
        var id2 = s.clock = s.clock + 1;
        s.open[uri] = { want: open.want, song: open.song, sent: { id: id2, liked: open.want }, lastClick: open.lastClick };
        return { state: s, request: request(id2, uri, open.want, open.song), reverted: false };
    }

    /* Read just before asking for /api/likes/mine; the answer goes to applyMine() with it. */
    function stamp(state) {
        return state.clock;
    }

    /*
     * The answer to /api/likes/mine (uris from fromMine()) asked at `since` (stamp()). It is the whole list, except
     * for songs clicked or answered after it was asked: those keep what this panel knows, so a ♥ never flips back
     * because of an answer older than the click. A song with a request on its way shows its last click anyway.
     */
    function applyMine(state, uris, since) {
        var known = map();
        (uris || []).forEach(function (uri) { known[uri] = true; });
        for (var uri in state.marks) {
            if (state.marks[uri] <= since) continue;
            if (state.known[uri] === true) known[uri] = true;
            else delete known[uri];
        }
        return { known: known, open: state.open, marks: state.marks, clock: state.clock };
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
        var shownNames = names.map(isolated).filter(Boolean);
        if (!shownNames.length) return '';
        if (shownNames.length <= 2) return '♥ ' + shownNames.join(', ');
        return '♥ ' + shownNames[0] + ' and ' + (shownNames.length - 1) + ' others';
    }

    /* A name ready to sit next to other text: trimmed, its own bidi controls dropped, in an isolate. "" if none. */
    function isolated(name) {
        var n = typeof name === 'string' ? name.replace(BIDI_CONTROLS, '').trim() : '';
        return n ? FSI + n + PDI : '';
    }

    /*
     * The name of someone's Liked songs: "My likes" for your own, else "anna's likes" with the name bidi-isolated
     * ("Someone's likes" when there is no name to show). Plain text: the panel sets it with textContent.
     */
    function likesLabel(name, isMe) {
        if (isMe === true) return 'My likes';
        return (isolated(name) || 'Someone') + "'s likes";
    }

    function wholeCount(n) {
        return typeof n === 'number' && isFinite(n) && n >= 0 && Math.floor(n) === n ? n : 0;
    }

    function byName(a, b) {
        var x = a.name.toLowerCase();
        var y = b.name.toLowerCase();
        if (x !== y) return x < y ? -1 : 1;
        return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    }

    /*
     * The rows of the Liked songs section from a /api/likes/people answer and the logged-in person (/api/me, or
     * null with web login off): [{id, name, count, mine}], yours first (also with no likes yet, at 0), then
     * everyone else by name, ignoring case. Entries without a usable id are dropped, an id seen twice counts once,
     * a blank name goes by the id (as the server does), and a count that is not a whole number is 0.
     */
    function peopleOrder(people, me) {
        var myId = me && typeof me === 'object' && typeof me.id === 'string' && me.id ? me.id : null;
        var seen = map();
        var mine = null;
        var others = [];
        (Array.isArray(people) ? people : []).forEach(function (p) {
            if (!p || typeof p !== 'object' || typeof p.id !== 'string' || !p.id || seen[p.id]) return;
            seen[p.id] = true;
            var name = typeof p.name === 'string' && p.name.trim() ? p.name.trim() : p.id;
            var row = { id: p.id, name: name, count: wholeCount(p.count), mine: p.id === myId };
            if (row.mine) mine = row;
            else others.push(row);
        });
        others.sort(byName);
        if (!myId) return others;
        if (!mine) {
            var myName = typeof me.name === 'string' && me.name.trim() ? me.name.trim() : myId;
            mine = { id: myId, name: myName, count: 0, mine: true };
        }
        return [mine].concat(others);
    }

    return {
        createState: createState,
        isLiked: isLiked,
        toggle: toggle,
        settle: settle,
        stamp: stamp,
        applyMine: applyMine,
        fromMine: fromMine,
        likedByText: likedByText,
        likesLabel: likesLabel,
        peopleOrder: peopleOrder
    };
}));
