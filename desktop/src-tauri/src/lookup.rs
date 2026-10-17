//! Search results, lyrics and links for the mini-player. The bot's JSON comes from the panel page,
//! so everything here is validated and capped before it reaches the UI.

use crate::bridge::{BotCommand, PlaylistSong};
use crate::spotify::parse_spotify_link;
use crate::state::{Me, Track};
use serde::Serialize;
use serde_json::Value;

const MAX_RESULTS: usize = 16;
/// Recently played / most played lists.
const MAX_LIST: usize = 50;
const MAX_PLAYLISTS: usize = 100;
/// The v2 tree lists every playlist (72 today); this only bounds what an odd answer can put in the UI.
const MAX_PLAYLIST_ENTRIES: usize = 500;
const MAX_FOLDER_CHARS: usize = 1000;
const MAX_FOLDER_DEPTH: usize = 8;
const MAX_FIELD_CHARS: usize = 200;
const MAX_LYRIC_LINES: usize = 500;
const MAX_LINE_CHARS: usize = 300;
/// The bot keeps at most 2000 likes a person; this only bounds what an odd answer can put in the UI.
const MAX_LIKED_URIS: usize = 5000;
/// The bot stores a uri cut to 300 chars, so a longer one in its answer is not one of its own.
const MAX_LIKED_URI_CHARS: usize = 2000;

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub title: String,
    pub author: String,
    pub uri: String,
    pub duration_ms: u64,
    pub is_stream: bool,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LyricLine {
    pub ms: u64,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Lyrics {
    pub synced: bool,
    pub instrumental: bool,
    pub lines: Vec<LyricLine>,
    pub error: Option<String>,
}

/// Cuts to `max` chars (not bytes), ending in "…" when something was cut.
pub(crate) fn cut(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_string();
    }
    let mut out: String = text.chars().take(max - 1).collect();
    out.push('…');
    out
}

fn text_field(item: &Value, key: &str, max: usize) -> String {
    cut(item.get(key).and_then(Value::as_str).unwrap_or(""), max)
}

/// A playlist saved on the bot; `folder` is None for the top level.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistInfo {
    pub name: String,
    pub folder: Option<String>,
    pub track_count: u32,
}

/// A playlist in the Playlists tab and the add-to-playlist picker; `folder` is None for the top level.
/// `legacy` playlists are still being converted by the bot: they play, but can't be edited yet.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistEntry {
    pub name: String,
    pub folder: Option<String>,
    pub song_count: u32,
    pub legacy: bool,
}

impl From<PlaylistInfo> for PlaylistEntry {
    fn from(info: PlaylistInfo) -> Self {
        PlaylistEntry { name: info.name, folder: info.folder, song_count: info.track_count, legacy: false }
    }
}

/// The bot's `/search` answer (an array of tracks) → at most 16 results that can be queued.
pub fn search_results(data: &Value) -> Vec<SearchResult> {
    data.as_array().map_or_else(Vec::new, |items| tracks_from(items, MAX_RESULTS))
}

/// The bot's `/history` or `/frequent` answer (`{tracks: [...]}`) → at most 50 songs.
pub fn track_list(data: &Value) -> Vec<SearchResult> {
    data.get("tracks").and_then(Value::as_array).map_or_else(Vec::new, |items| tracks_from(items, MAX_LIST))
}

fn tracks_from(items: &[Value], cap: usize) -> Vec<SearchResult> {
    items
        .iter()
        .filter(|item| item.get("uri").and_then(Value::as_str).is_some_and(|uri| !uri.is_empty()))
        .take(cap)
        .map(|item| SearchResult {
            title: text_field(item, "title", MAX_FIELD_CHARS),
            author: text_field(item, "author", MAX_FIELD_CHARS),
            // Never cut: a shortened uri would queue the wrong thing.
            uri: item["uri"].as_str().unwrap_or_default().to_string(),
            duration_ms: item.get("duration").and_then(Value::as_u64).unwrap_or(0),
            is_stream: item.get("isStream").and_then(Value::as_bool).unwrap_or(false),
            source: text_field(item, "source", MAX_FIELD_CHARS),
        })
        .collect()
}

/// The bot's `/lyrics` answer → lines with timestamps (at most 500).
pub fn lyrics_from(data: &Value) -> Lyrics {
    let lines = data
        .get("lines")
        .and_then(Value::as_array)
        .map(|lines| {
            lines
                .iter()
                .take(MAX_LYRIC_LINES)
                .map(|line| LyricLine {
                    ms: line.get("ms").and_then(Value::as_u64).unwrap_or(0),
                    text: text_field(line, "text", MAX_LINE_CHARS),
                })
                .collect()
        })
        .unwrap_or_default();
    Lyrics {
        synced: data.get("synced").and_then(Value::as_bool).unwrap_or(false),
        instrumental: data.get("instrumental").and_then(Value::as_bool).unwrap_or(false),
        lines,
        error: data.get("error").and_then(Value::as_str).map(|e| cut(e, MAX_FIELD_CHARS)),
    }
}

/// The bot's `/playlists-tree` answer → every playlist, folders flattened (at most 100).
pub fn playlists(tree: &Value) -> Vec<PlaylistInfo> {
    let mut found = Vec::new();
    collect_playlists(tree, 0, &mut found);
    found
}

fn collect_playlists(node: &Value, depth: usize, found: &mut Vec<PlaylistInfo>) {
    if depth > MAX_FOLDER_DEPTH {
        return;
    }
    for playlist in node.get("playlists").and_then(Value::as_array).into_iter().flatten() {
        if found.len() >= MAX_PLAYLISTS {
            return;
        }
        // Names go into the bot's URL, so a name is used whole or not at all.
        let Some(name) = playlist.get("name").and_then(Value::as_str).filter(|n| !n.is_empty() && n.chars().count() <= MAX_FIELD_CHARS) else {
            continue;
        };
        let folder = playlist.get("folder").and_then(Value::as_str).filter(|f| !f.is_empty()).map(str::to_string);
        let track_count = playlist.get("trackCount").and_then(Value::as_u64).unwrap_or(0).min(u64::from(u32::MAX)) as u32;
        found.push(PlaylistInfo { name: name.to_string(), folder, track_count });
    }
    for folder in node.get("folders").and_then(Value::as_array).into_iter().flatten() {
        collect_playlists(folder, depth + 1, found);
    }
}

/// The bot's `/api/playlists/tree` answer → every playlist, sorted like the panel does (folder, then name,
/// ignoring case; the top level first), at most 500.
///
/// Names and folders go back to the bot to say which playlist is meant (and a folder would be created
/// if it didn't exist), so one is used whole or the entry is left out; it is never cut.
pub fn playlists_v2(tree: &Value) -> Vec<PlaylistEntry> {
    let mut found: Vec<PlaylistEntry> = tree
        .get("playlists")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|playlist| {
            let name = playlist.get("name").and_then(Value::as_str).filter(|n| !n.is_empty() && n.chars().count() <= MAX_FIELD_CHARS)?;
            let folder = match playlist.get("folder").and_then(Value::as_str) {
                Some(f) if f.chars().count() > MAX_FOLDER_CHARS => return None,
                Some(f) if !f.is_empty() => Some(f.to_string()),
                _ => None,
            };
            let song_count = playlist.get("songCount").and_then(Value::as_u64).unwrap_or(0).min(u64::from(u32::MAX)) as u32;
            let legacy = playlist.get("legacy").and_then(Value::as_bool).unwrap_or(false);
            Some(PlaylistEntry { name: name.to_string(), folder, song_count, legacy })
        })
        .collect();
    found.sort_by_cached_key(|p| {
        let folder = p.folder.clone().unwrap_or_default();
        (folder.to_lowercase(), p.name.to_lowercase(), folder, p.name.clone())
    });
    found.truncate(MAX_PLAYLIST_ENTRIES);
    found
}

/// The signed-in person's liked songs, as the ♥s need them: each liked uri once (newest first) and how many there are.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Likes {
    pub uris: Vec<String>,
    pub count: u32,
}

/// The bot's `/api/likes/mine` answer (`{uris, count, max}`) → the like state, or None when the answer is something else
/// (an error, or a bot from before likes). A uri decides which ♥ fills, so one is used whole or left out, never cut.
pub fn likes(data: &Value) -> Option<Likes> {
    let listed = data.get("uris")?.as_array()?;
    let mut uris: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for uri in listed.iter().filter_map(Value::as_str) {
        if uris.len() >= MAX_LIKED_URIS {
            break;
        }
        if !uri.trim().is_empty() && uri.chars().count() <= MAX_LIKED_URI_CHARS && seen.insert(uri) {
            uris.push(uri.to_string());
        }
    }
    let count = match data.get("count").and_then(Value::as_u64) {
        Some(n) => n.min(u64::from(u32::MAX)) as u32,
        None => uris.len() as u32,
    };
    Some(Likes { uris, count })
}

/// ♥ `song`; refused here when there is no uri to like.
pub fn like_command(song: PlaylistSong) -> Result<BotCommand, String> {
    if song.uri.trim().is_empty() {
        return Err("That song can't be liked".to_string());
    }
    Ok(BotCommand::Like { song })
}

/// Takes the ♥ off the song with this uri.
pub fn unlike_command(uri: String) -> Result<BotCommand, String> {
    if uri.trim().is_empty() {
        return Err("No song to unlike".to_string());
    }
    Ok(BotCommand::Unlike { uri })
}

/// Plays your own likes: the list is named by the signed-in person's Discord id (1–20 digits, as the bot checks it),
/// from the panel's login; the UI never names it.
pub fn play_likes_command(me: Option<&Me>) -> Result<BotCommand, String> {
    let id = me
        .map(|me| me.id.as_str())
        .filter(|id| (1..=20).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_digit()))
        .ok_or_else(|| "Sign in from the panel to play your likes".to_string())?;
    Ok(BotCommand::PlayLikes { user_id: id.to_string() })
}

/// True when the page's error for a fetch says the bot has no such route ("HTTP 404" is how it reports a
/// plain 404): a bot from before playlists v2.
pub fn is_missing_route(error: &str) -> bool {
    error == "HTTP 404"
}

/// Adds `song` to the playlist `name`; refused here when there is nowhere to add it or nothing to add.
pub fn add_command(name: String, folder: Option<String>, song: PlaylistSong) -> Result<BotCommand, String> {
    if name.trim().is_empty() {
        return Err("Pick a playlist".to_string());
    }
    if song.uri.trim().is_empty() {
        return Err("That song can't be added to a playlist".to_string());
    }
    Ok(BotCommand::AddToPlaylist { name, folder: folder.filter(|f| !f.is_empty()), song })
}

/// A new, empty playlist; the bot trims the name and refuses duplicates and names the disk can't hold.
pub fn create_command(name: String, folder: Option<String>) -> Result<BotCommand, String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("Give the playlist a name".to_string());
    }
    Ok(BotCommand::CreatePlaylist { name, folder: folder.filter(|f| !f.is_empty()) })
}

/// Lyrics for this exact song, so a song change mid-request can't show the next song's words.
pub fn lyrics_command(track: Option<&Track>) -> Result<BotCommand, String> {
    let track = track.ok_or_else(|| "Nothing playing".to_string())?;
    Ok(BotCommand::Lyrics { title: track.title.clone(), author: track.author.clone(), duration_ms: track.duration_ms })
}

pub const NOT_A_LINK: &str = "That's not a link";
pub const LINK_TOO_LONG: &str = "That link is too long";
/// No link the bot plays is anywhere near this long; one that is goes no further than here.
const MAX_LINK_BYTES: usize = 4 * 1024;

/// What to send for a pasted link: Spotify links in the form the bot resolves, else the first web link.
pub fn link_command(text: &str) -> Result<BotCommand, String> {
    let checked = |link: &str| if link.len() > MAX_LINK_BYTES { Err(LINK_TOO_LONG.to_string()) } else { Ok(()) };
    if let Some(link) = parse_spotify_link(text) {
        checked(&link)?;
        return Ok(BotCommand::PlaySpotifyLink(link));
    }
    let url = text
        .split_whitespace()
        .find(|word| word.starts_with("http://") || word.starts_with("https://"))
        .ok_or_else(|| NOT_A_LINK.to_string())?;
    checked(url)?;
    Ok(BotCommand::PlayQuery(url.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::{BotCommand, PlaylistSong};
    use serde_json::json;

    #[test]
    fn search_maps_bot_fields() {
        let data = json!([{"title":"T","author":"A","uri":"https://y/1","duration":214000,"isStream":false,"source":"youtube"}]);
        assert_eq!(
            search_results(&data),
            vec![SearchResult {
                title: "T".into(),
                author: "A".into(),
                uri: "https://y/1".into(),
                duration_ms: 214_000,
                is_stream: false,
                source: "youtube".into(),
            }]
        );
    }

    #[test]
    fn search_drops_items_without_uri_and_caps_at_16() {
        let mut items: Vec<_> = (0..18).map(|i| json!({"title": format!("t{i}"), "uri": format!("u{i}")})).collect();
        items.insert(3, json!({"title": "no uri"}));
        items.insert(7, json!({"title": "empty uri", "uri": ""}));
        let results = search_results(&serde_json::Value::Array(items));
        assert_eq!(results.len(), 16);
        assert!(results.iter().all(|r| !r.uri.is_empty()));
        assert!(search_results(&json!({"error": "nope"})).is_empty());
    }

    #[test]
    fn search_cuts_long_fields() {
        let data = json!([{"title": "🎵".repeat(250), "uri": "u"}]);
        let title = &search_results(&data)[0].title;
        assert_eq!(title.chars().count(), 200);
        assert!(title.ends_with('…'));
    }

    #[test]
    fn lyrics_map_synced_lines() {
        let l = lyrics_from(&json!({"synced": true, "lines": [{"ms": 1000, "text": "a"}]}));
        assert!(l.synced && !l.instrumental && l.error.is_none());
        assert_eq!(l.lines, vec![LyricLine { ms: 1000, text: "a".into() }]);

        let l = lyrics_from(&json!({"synced": false, "lines": [], "error": "No lyrics found"}));
        assert_eq!(l.error.as_deref(), Some("No lyrics found"));
        assert!(l.lines.is_empty());

        assert!(lyrics_from(&json!({"instrumental": true})).instrumental);

        let many: Vec<_> = (0..600).map(|i| json!({"ms": i, "text": "x"})).collect();
        assert_eq!(lyrics_from(&json!({"lines": many})).lines.len(), 500);

        let long = lyrics_from(&json!({"lines": [{"ms": 0, "text": "y".repeat(400)}]}));
        assert_eq!(long.lines[0].text.chars().count(), 300);
        assert!(long.lines[0].text.ends_with('…'));
    }

    #[test]
    fn track_list_maps_and_caps() {
        let items: Vec<_> = (0..60).map(|i| json!({"title": format!("t{i}"), "author": "A", "uri": format!("u{i}"), "duration": 1000})).collect();
        let list = track_list(&json!({ "tracks": items, "size": 60 }));
        assert_eq!(list.len(), 50);
        assert_eq!(list[0].title, "t0");
        assert_eq!(list[0].duration_ms, 1000);
        assert!(track_list(&json!([])).is_empty());
    }

    #[test]
    fn playlists_flatten_folders() {
        let tree = json!({
            "folder": "",
            "playlists": [{"name": "Chill", "folder": "", "trackCount": 3, "items": ["a"]}],
            "folders": [{
                "folder": "Fred", "name": "Fred",
                "playlists": [{"name": "Rap", "folder": "Fred", "trackCount": 12}],
                "folders": [{"folder": "Fred/Old", "name": "Old", "playlists": [{"name": "2010", "folder": "Fred/Old", "trackCount": 1}], "folders": []}]
            }]
        });
        assert_eq!(
            playlists(&tree),
            vec![
                PlaylistInfo { name: "Chill".into(), folder: None, track_count: 3 },
                PlaylistInfo { name: "Rap".into(), folder: Some("Fred".into()), track_count: 12 },
                PlaylistInfo { name: "2010".into(), folder: Some("Fred/Old".into()), track_count: 1 },
            ]
        );
        let many: Vec<_> = (0..150).map(|i| json!({"name": format!("p{i}"), "trackCount": 1})).collect();
        assert_eq!(playlists(&json!({ "playlists": many })).len(), 100);
    }

    #[test]
    fn lyrics_command_carries_the_song() {
        let track = crate::state::Track { title: "T".into(), author: "A".into(), duration_ms: 1000, ..Default::default() };
        let cmd = lyrics_command(Some(&track)).unwrap();
        assert_eq!(
            serde_json::to_value(&cmd).unwrap(),
            json!({"type": "Lyrics", "value": {"title": "T", "author": "A", "durationMs": 1000}})
        );
        assert_eq!(lyrics_command(None), Err("Nothing playing".to_string()));
        assert_eq!(
            serde_json::to_value(BotCommand::RemoveFromQueue { index: 2, uri: "u".into() }).unwrap(),
            json!({"type": "RemoveFromQueue", "value": {"index": 2, "uri": "u"}})
        );
    }

    fn v2_names(entries: &[PlaylistEntry]) -> Vec<(String, String)> {
        entries.iter().map(|e| (e.folder.clone().unwrap_or_default(), e.name.clone())).collect()
    }

    #[test]
    fn v2_tree_maps_and_sorts_like_the_panel() {
        let tree = json!({
            "folders": ["Fred", "Fred/Old"],
            "playlists": [
                {"name": "rap", "folder": "Fred", "songCount": 12, "durationMs": 1, "createdBy": null, "updatedAt": "x", "legacy": false},
                {"name": "Zen", "folder": "", "songCount": 3, "legacy": false},
                {"name": "2010", "folder": "Fred/Old", "songCount": 1, "legacy": true},
                {"name": "Chill", "folder": "", "songCount": 0},
                {"name": "Beats", "folder": "fred", "songCount": 5},
                {"name": "apple", "folder": "Fred", "songCount": 2}
            ],
            "deleted": [{"id": "d1", "name": "Gone", "originalFolder": ""}]
        });
        let found = playlists_v2(&tree);
        assert_eq!(
            v2_names(&found),
            vec![
                ("".into(), "Chill".into()),
                ("".into(), "Zen".into()),
                ("Fred".into(), "apple".into()),
                ("fred".into(), "Beats".into()),
                ("Fred".into(), "rap".into()),
                ("Fred/Old".into(), "2010".into()),
            ],
            "top level first, then by folder, then by name, ignoring case; the Deleted section is not a playlist list"
        );
        assert_eq!(found[0], PlaylistEntry { name: "Chill".into(), folder: None, song_count: 0, legacy: false });
        assert_eq!(found[5], PlaylistEntry { name: "2010".into(), folder: Some("Fred/Old".into()), song_count: 1, legacy: true });
        assert_eq!(found[4].song_count, 12);
    }

    #[test]
    fn v2_tree_caps_at_500_keeping_the_first_in_order() {
        let many: Vec<_> = (0..600).rev().map(|i| json!({"name": format!("p{i:03}"), "folder": "", "songCount": 1})).collect();
        let found = playlists_v2(&json!({ "playlists": many }));
        assert_eq!(found.len(), 500);
        assert_eq!(found[0].name, "p000");
        assert_eq!(found[499].name, "p499");
    }

    #[test]
    fn v2_tree_defaults_and_bounds() {
        let found = playlists_v2(&json!({"playlists": [
            {"name": "A"},
            {"name": "B", "songCount": 1_000_000_000_000u64, "legacy": "yes"},
            {"name": "C", "songCount": -4, "folder": 7},
            {"name": "D", "legacy": true}
        ]}));
        assert_eq!(found[0], PlaylistEntry { name: "A".into(), folder: None, song_count: 0, legacy: false });
        assert_eq!(found[1].song_count, u32::MAX);
        assert!(!found[1].legacy, "only a real true marks a playlist as being upgraded");
        assert_eq!(found[2].song_count, 0);
        assert_eq!(found[2].folder, None);
        assert!(found[3].legacy);
    }

    #[test]
    fn v2_tree_never_cuts_what_addresses_a_playlist() {
        // A cut name or folder would name some other playlist (or make a new folder), so such entries are skipped.
        let ok_name = "n".repeat(200);
        let found = playlists_v2(&json!({"playlists": [
            {"name": ok_name, "folder": "f".repeat(1000)},
            {"name": "n".repeat(201), "folder": ""},
            {"name": "fine", "folder": "f".repeat(1001)},
            {"name": "", "folder": ""},
            {"name": 5, "folder": ""},
            {"folder": "Fred"},
            "not an object"
        ]}));
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name.chars().count(), 200);
        assert_eq!(found[0].folder.as_ref().map(|f| f.chars().count()), Some(1000));
    }

    #[test]
    fn v2_tree_of_something_else_is_empty() {
        assert!(playlists_v2(&json!({})).is_empty());
        assert!(playlists_v2(&json!([])).is_empty());
        assert!(playlists_v2(&json!({"error": "nope"})).is_empty());
        assert!(playlists_v2(&json!({"playlists": "x"})).is_empty());
    }

    #[test]
    fn old_tree_entries_convert_for_the_fallback() {
        let info = PlaylistInfo { name: "Rap".into(), folder: Some("Fred".into()), track_count: 12 };
        assert_eq!(PlaylistEntry::from(info), PlaylistEntry { name: "Rap".into(), folder: Some("Fred".into()), song_count: 12, legacy: false });
        // The page reports a failed fetch as "HTTP <status>"; a bot from before playlists v2 has no such route.
        assert!(is_missing_route("HTTP 404"));
        assert!(!is_missing_route("HTTP 500"));
        assert!(!is_missing_route("Could not reach the bot"));
    }

    #[test]
    fn playlist_entries_reach_the_ui_in_camel_case() {
        let entry = PlaylistEntry { name: "Rap".into(), folder: None, song_count: 3, legacy: true };
        assert_eq!(serde_json::to_value(&entry).unwrap(), json!({"name": "Rap", "folder": null, "songCount": 3, "legacy": true}));
    }

    fn song() -> PlaylistSong {
        PlaylistSong {
            uri: "https://youtu.be/abc".into(),
            title: "Ke$ha \"Tik\" Tok".into(),
            author: "Kesha".into(),
            duration_ms: 200_000,
            artwork_url: Some("https://i.ytimg.com/vi/abc/hqdefault.jpg".into()),
        }
    }

    #[test]
    fn playlist_commands_have_the_wire_shape_the_page_reads() {
        assert_eq!(serde_json::to_value(BotCommand::PlaylistsTreeV2).unwrap(), json!({"type": "PlaylistsTreeV2"}));
        assert_eq!(
            serde_json::to_value(BotCommand::AddToPlaylist { name: "Chill".into(), folder: Some("Fred".into()), song: song() }).unwrap(),
            json!({"type": "AddToPlaylist", "value": {
                "name": "Chill", "folder": "Fred",
                "song": {"uri": "https://youtu.be/abc", "title": "Ke$ha \"Tik\" Tok", "author": "Kesha", "durationMs": 200000,
                         "artworkUrl": "https://i.ytimg.com/vi/abc/hqdefault.jpg"}
            }})
        );
        assert_eq!(
            serde_json::to_value(BotCommand::CreatePlaylist { name: "Road trip".into(), folder: None }).unwrap(),
            json!({"type": "CreatePlaylist", "value": {"name": "Road trip", "folder": null}})
        );
    }

    #[test]
    fn a_song_from_the_ui_may_lack_artwork() {
        let song: PlaylistSong =
            serde_json::from_value(json!({"uri": "u", "title": "T", "author": "A", "durationMs": 5000, "artworkUrl": null})).unwrap();
        assert_eq!(song, PlaylistSong { uri: "u".into(), title: "T".into(), author: "A".into(), duration_ms: 5000, artwork_url: None });
        let bare: PlaylistSong = serde_json::from_value(json!({"uri": "u"})).unwrap();
        assert_eq!(bare, PlaylistSong { uri: "u".into(), ..Default::default() });
    }

    #[test]
    fn add_and_create_are_checked_before_they_are_sent() {
        assert_eq!(
            add_command("Chill".into(), Some(String::new()), song()),
            Ok(BotCommand::AddToPlaylist { name: "Chill".into(), folder: None, song: song() }),
            "a top-level playlist has no folder"
        );
        assert_eq!(add_command("  ".into(), None, song()), Err("Pick a playlist".to_string()));
        assert_eq!(
            add_command("Chill".into(), None, PlaylistSong { uri: " ".into(), ..song() }),
            Err("That song can't be added to a playlist".to_string())
        );
        assert_eq!(
            create_command("  Road trip ".into(), Some("Fred".into())),
            Ok(BotCommand::CreatePlaylist { name: "Road trip".into(), folder: Some("Fred".into()) })
        );
        assert_eq!(create_command("   ".into(), None), Err("Give the playlist a name".to_string()));
    }

    #[test]
    fn likes_map_the_mine_answer() {
        let likes = likes(&json!({"uris": ["https://y/2", "https://y/1"], "count": 2, "max": 2000})).unwrap();
        assert_eq!(likes, Likes { uris: vec!["https://y/2".into(), "https://y/1".into()], count: 2 }, "newest first, as the bot sends them");
        assert_eq!(serde_json::to_value(&likes).unwrap(), json!({"uris": ["https://y/2", "https://y/1"], "count": 2}));
        // Nobody signed in on a bot with the login off: an empty list, which is still a list.
        assert_eq!(likes_of(json!({"uris": [], "count": 0, "max": 2000})), Some(Likes { uris: vec![], count: 0 }));
    }

    fn likes_of(data: serde_json::Value) -> Option<Likes> {
        likes(&data)
    }

    #[test]
    fn likes_keep_each_usable_uri_once_and_whole() {
        let found = likes_of(json!({"uris": ["a", 5, null, "", "   ", "b", "a", {"uri": "c"}, "b"], "count": 4})).unwrap();
        assert_eq!(found.uris, vec!["a".to_string(), "b".to_string()]);
        assert_eq!(found.count, 4, "the bot's own count");
        let long = "u".repeat(2000);
        let found = likes_of(json!({"uris": [long.clone(), "x".repeat(2001)]})).unwrap();
        assert_eq!(found.uris, vec![long], "a uri is used whole or not at all");
        assert_eq!(found.count, 1, "no count: as many as listed");
        let many: Vec<_> = (0..6000).map(|i| format!("u{i}")).collect();
        let found = likes_of(json!({ "uris": many, "count": 6000 })).unwrap();
        assert_eq!(found.uris.len(), 5000);
        assert_eq!(found.uris[0], "u0");
        assert_eq!(found.count, 6000);
    }

    #[test]
    fn likes_count_is_a_sane_number() {
        assert_eq!(likes_of(json!({"uris": ["a"], "count": -3})).unwrap().count, 1);
        assert_eq!(likes_of(json!({"uris": ["a"], "count": "7"})).unwrap().count, 1);
        assert_eq!(likes_of(json!({"uris": ["a"], "count": 2.5})).unwrap().count, 1);
        assert_eq!(likes_of(json!({"uris": [], "count": 1_000_000_000_000u64})).unwrap().count, u32::MAX);
    }

    #[test]
    fn likes_of_something_else_is_unknown() {
        for data in [json!({}), json!([]), json!(null), json!({"error": "Log in with Discord to like songs"}), json!({"uris": "a"}), json!("x")] {
            assert_eq!(likes(&data), None, "{data}");
        }
    }

    #[test]
    fn like_changes_and_play_are_checked_before_they_are_sent() {
        assert_eq!(like_command(song()), Ok(BotCommand::Like { song: song() }));
        assert_eq!(like_command(PlaylistSong { uri: "  ".into(), ..song() }), Err("That song can't be liked".to_string()));
        assert_eq!(unlike_command("https://youtu.be/abc".into()), Ok(BotCommand::Unlike { uri: "https://youtu.be/abc".into() }));
        assert_eq!(unlike_command(" ".into()), Err("No song to unlike".to_string()));
        let me = |id: &str| crate::state::Me { id: id.into(), name: "fred".into(), avatar_url: None };
        assert_eq!(play_likes_command(Some(&me("123456789012345678"))), Ok(BotCommand::PlayLikes { user_id: "123456789012345678".into() }));
        for odd in ["", "abc", "12a", "everyone", "../1", "123456789012345678901", " 1"] {
            assert_eq!(
                play_likes_command(Some(&me(odd))),
                Err("Sign in from the panel to play your likes".to_string()),
                "only a Discord id names your own list: {odd:?}"
            );
        }
        assert_eq!(play_likes_command(None), Err("Sign in from the panel to play your likes".to_string()));
    }

    #[test]
    fn links_route_by_kind() {
        assert_eq!(
            link_command("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"),
            Ok(BotCommand::PlaySpotifyLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M".into()))
        );
        assert_eq!(
            link_command("spotify:track:4uLU6hMCjMI75M1A2tKUQC"),
            Ok(BotCommand::PlaySpotifyLink("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC".into()))
        );
        assert_eq!(link_command("watch https://youtu.be/abc now"), Ok(BotCommand::PlayQuery("https://youtu.be/abc".into())));
        assert_eq!(link_command("just words"), Err("That's not a link".to_string()));
    }

    #[test]
    fn a_link_over_4_kib_is_too_long() {
        let base = "https://example.com/";
        let fits = format!("{base}{}", "a".repeat(4096 - base.len()));
        assert_eq!(fits.len(), 4096);
        assert_eq!(link_command(&fits), Ok(BotCommand::PlayQuery(fits.clone())));
        let long = format!("{fits}a");
        assert_eq!(link_command(&long), Err("That link is too long".to_string()));
        assert_eq!(link_command(&format!("play {long} now")), Err("That link is too long".to_string()));
        let spotify = format!("https://open.spotify.com/track/{}", "A".repeat(5000));
        assert_eq!(link_command(&spotify), Err("That link is too long".to_string()));
    }
}
