/*
 * "What's new": which patch notes the person has not seen yet. Pure functions, no DOM or storage:
 * index.html reads and writes the seen marker and draws the dialog; this file only decides what is unseen.
 * Loaded by the panel as a classic script (window.WhatsNew) and by Node for tests (module.exports).
 */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    else root.WhatsNew = api;
}(typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /*
     * list: the "notes" array of patch-notes.json, newest first. Entries without a string id are ignored.
     * lastSeenId: the newest id the person has seen. Missing, or no longer in the list: a first visit, so only
     * the newest entry (not the whole history). Otherwise the entries above it, newest first.
     */
    function unseenNotes(list, lastSeenId) {
        if (!Array.isArray(list)) return [];
        var notes = list.filter(function (n) { return n && typeof n.id === 'string'; });
        if (!notes.length) return [];
        var seenAt = -1;
        if (typeof lastSeenId === 'string' && lastSeenId) {
            for (var i = 0; i < notes.length; i++) {
                if (notes[i].id === lastSeenId) { seenAt = i; break; }
            }
        }
        return seenAt < 0 ? [notes[0]] : notes.slice(0, seenAt);
    }

    /* Without working storage the "seen" marker can't last, so the dialog would pop on every visit: never auto-show. */
    function shouldAutoShow(storageOk, unseen) {
        return !!storageOk && Array.isArray(unseen) && unseen.length > 0;
    }

    return { unseenNotes: unseenNotes, shouldAutoShow: shouldAutoShow };
}));
