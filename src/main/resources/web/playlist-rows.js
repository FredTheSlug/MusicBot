/*
 * Playlist rows: the "added by" line under a song, and where each Move item of the row menu sends it. Pure
 * functions, no DOM or requests: index.html draws the rows and sends the move; this file decides the wording and
 * the positions.
 * Loaded by the panel as a classic script (window.PlaylistRows) and by Node for tests (module.exports).
 */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    else root.PlaylistRows = api;
}(typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    var FSI = '\u2068'; // first strong isolate ...
    var PDI = '\u2069'; // ... pop directional isolate
    // Embeddings, overrides and isolates inside a name could leak out of its isolate; they are dropped.
    // (Same rule as Likes.likedByText; likes.js does not export its helper, so this stays a local copy.)
    var BIDI_CONTROLS = /[\u202A-\u202E\u2066-\u2069]/g;
    // Fixed English names: the rest of the panel is English, and the line must read the same on every browser.
    var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    function clock(now) {
        var d = now === undefined || now === null ? new Date() : new Date(now);
        return isNaN(d.getTime()) ? new Date() : d;
    }

    /*
     * "Oct 13", or "Oct 13, 2025" when that is not the year of `now`, in the viewer's time zone. "" when `iso` is
     * not an ISO date-time string. Shared by every row that says when something happened.
     */
    function shortDate(iso, now) {
        if (typeof iso !== 'string' || !iso) return '';
        var d = new Date(iso);
        if (isNaN(d.getTime())) return '';
        var text = MONTHS[d.getMonth()] + ' ' + d.getDate();
        return d.getFullYear() === clock(now).getFullYear() ? text : text + ', ' + d.getFullYear();
    }

    /*
     * "added by fred · Oct 13": who put the song in the playlist and when. What is not known is left out
     * ("added by fred", "added Oct 13", or "" when neither is). The year appears only when it is not the year
     * of `now`. The name is bidi-isolated, so a right-to-left name cannot reorder the rest of the line.
     * Plain text: the panel sets it with textContent.
     */
    function addedByText(song, now) {
        if (!song || typeof song !== 'object') return '';
        var name = typeof song.addedBy === 'string' ? song.addedBy.replace(BIDI_CONTROLS, '').trim() : '';
        var date = shortDate(song.addedAt, now);
        if (name && date) return 'added by ' + FSI + name + PDI + ' · ' + date;
        if (name) return 'added by ' + FSI + name + PDI;
        return date ? 'added ' + date : '';
    }

    /*
     * The Move items of the row at `index` in a list of `length` songs: [{label, toIndex}] in menu order, where
     * toIndex is the song's final position, as /songs/move expects it. An item that would leave the song where it
     * is is left out, so a list of one song has none and the top row cannot go up.
     */
    function moveTargets(index, length) {
        var whole = function (n) { return typeof n === 'number' && isFinite(n) && Math.floor(n) === n; };
        if (!whole(index) || !whole(length) || index < 0 || index >= length) return [];
        var items = [];
        if (index > 0) items.push({ label: 'Move up', toIndex: index - 1 });
        if (index < length - 1) items.push({ label: 'Move down', toIndex: index + 1 });
        if (index > 0) items.push({ label: 'Move to top', toIndex: 0 });
        if (index < length - 1) items.push({ label: 'Move to bottom', toIndex: length - 1 });
        return items;
    }

    return {
        shortDate: shortDate,
        addedByText: addedByText,
        moveTargets: moveTargets
    };
}));
