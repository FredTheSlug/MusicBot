//! Search results, lyrics and links for the mini-player. The bot's JSON comes from the panel page,
//! so everything here is validated and capped before it reaches the UI.

use crate::bridge::BotCommand;
use crate::spotify::parse_spotify_link;
use crate::state::Track;
use serde::Serialize;
use serde_json::Value;

const MAX_RESULTS: usize = 16;
/// Recently played / most played lists.
const MAX_LIST: usize = 50;
const MAX_PLAYLISTS: usize = 100;
const MAX_FOLDER_DEPTH: usize = 8;
const MAX_FIELD_CHARS: usize = 200;
const MAX_LYRIC_LINES: usize = 500;
const MAX_LINE_CHARS: usize = 300;

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

/// Lyrics for this exact song, so a song change mid-request can't show the next song's words.
pub fn lyrics_command(track: Option<&Track>) -> Result<BotCommand, String> {
    let track = track.ok_or_else(|| "Nothing playing".to_string())?;
    Ok(BotCommand::Lyrics { title: track.title.clone(), author: track.author.clone(), duration_ms: track.duration_ms })
}

/// What to send for a pasted link: Spotify links in the form the bot resolves, else the first web link.
pub fn link_command(text: &str) -> Result<BotCommand, String> {
    if let Some(link) = parse_spotify_link(text) {
        return Ok(BotCommand::PlaySpotifyLink(link));
    }
    text.split_whitespace()
        .find(|word| word.starts_with("http://") || word.starts_with("https://"))
        .map(|url| BotCommand::PlayQuery(url.to_string()))
        .ok_or_else(|| "That's not a link".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::BotCommand;
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
}
