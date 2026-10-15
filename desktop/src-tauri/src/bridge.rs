//! The channel between the Rust core and the panel page.
//! Page → Rust: the `bridge_report` command (the only command the remote page may call).
//! Rust → page: `webview.eval` of `window.__cksBridge.run(id, command)`; the page reports the
//! outcome back as a `Result` report with the same id.

use crate::state::BotState;
use crate::theme::PanelTheme;
use crate::{dispatch, dispatch_notice, AppState};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager};

const CORE_JS: &str = include_str!("../../bridge/core.js");
const BRIDGE_JS: &str = include_str!("../../bridge/bridge.js");

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// A song as the bot's playlists keep it: what the mini-player sends when you add one.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlaylistSong {
    pub uri: String,
    pub title: String,
    pub author: String,
    pub duration_ms: u64,
    pub artwork_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "value")]
pub enum BotCommand {
    TogglePause,
    Pause,
    Skip,
    RestartTrack,
    SetVolume(u8),
    PlayQuery(String),
    PlaySpotifyLink(String),
    ToggleShuffle,
    CycleRepeat,
    ToggleAutoplay,
    /// Milliseconds from the start of the song.
    Seek(u64),
    /// Queue edits name the song too, so the page can refuse if the queue moved underneath.
    RemoveFromQueue { index: u32, uri: String },
    MoveToTop { index: u32, uri: String },
    PlayPlaylist { name: String, folder: Option<String> },
    /// Fetches: the page answers with the bot's JSON as the result's `data`.
    Search(String),
    #[serde(rename_all = "camelCase")]
    Lyrics { title: String, author: String, duration_ms: u64 },
    History,
    Frequent,
    /// The tree from before playlists v2; kept for a bot that doesn't have `PlaylistsTreeV2` yet.
    Playlists,
    /// Playlists v2 (`folder` None = top level): the tree with real song counts, and edits by name.
    PlaylistsTreeV2,
    AddToPlaylist { name: String, folder: Option<String>, song: PlaylistSong },
    CreatePlaylist { name: String, folder: Option<String> },
    /// The page opens its "What's new" history; needs no server selected and sends nothing to the bot.
    ShowWhatsNew,
    /// Fetch: the custom themes people made (`GET /api/themes`); the same for every server, so none needs picking.
    Themes,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum BridgeReport {
    State { state: BotState },
    Theme { theme: PanelTheme },
    Notice { level: String, message: String },
    Result {
        id: u64,
        ok: bool,
        message: Option<String>,
        #[serde(default)]
        data: Option<serde_json::Value>,
    },
}

/// Runs in every page load of the main window; does nothing unless the page is the bot's site.
pub fn init_script(origin: &str) -> String {
    let origin_json = serde_json::to_string(origin).expect("a string always serializes");
    format!("window.__CKS_ORIGIN = {origin_json};\nif (location.origin === window.__CKS_ORIGIN) {{\n{CORE_JS}\n{BRIDGE_JS}\n}}\n")
}

/// JS that hands a command to the page. Command data only ever enters the script as JSON.
pub fn command_js(id: u64, cmd: &BotCommand) -> String {
    let json = serde_json::to_string(cmd).expect("BotCommand always serializes");
    format!("window.__cksBridge && window.__cksBridge.run({id}, {json});")
}

/// Drops query strings from URLs so log lines never carry tokens or tracking parameters.
pub fn redact_urls(text: &str) -> String {
    text.split(' ')
        .map(|word| {
            if word.starts_with("http://") || word.starts_with("https://") {
                word.split('?').next().unwrap_or(word)
            } else {
                word
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn eval_command(app: &AppHandle, id: u64, cmd: &BotCommand) -> Result<(), String> {
    let window = app.get_webview_window("main").ok_or("The panel window isn't open")?;
    window.eval(command_js(id, cmd)).map_err(|e| e.to_string())
}

/// Fire-and-forget: the page runs the command; failures arrive later as notices/results.
pub fn send_command(app: &AppHandle, cmd: BotCommand) -> Result<u64, String> {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    eval_command(app, id, &cmd)?;
    Ok(id)
}

/// Sends a command and waits for the page to report whether the bot accepted it.
/// Blocks the calling thread: call it from async commands or worker threads, never the main thread.
pub fn send_command_await(app: &AppHandle, cmd: BotCommand, timeout: Duration) -> Result<(), String> {
    send_and_wait(app, cmd, timeout).map(|_| ())
}

/// Like `send_command_await`, for fetch commands: returns the bot's answer.
pub fn send_command_fetch(app: &AppHandle, cmd: BotCommand, timeout: Duration) -> Result<serde_json::Value, String> {
    send_and_wait(app, cmd, timeout)?.ok_or_else(|| "The bot sent no data".to_string())
}

fn send_and_wait(app: &AppHandle, cmd: BotCommand, timeout: Duration) -> Result<Option<serde_json::Value>, String> {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let (tx, rx) = mpsc::channel();
    let state = app.state::<AppState>();
    state.pending.lock().unwrap().insert(id, tx);
    if let Err(e) = eval_command(app, id, &cmd) {
        state.pending.lock().unwrap().remove(&id);
        return Err(e);
    }
    match rx.recv_timeout(timeout) {
        Ok(result) => result,
        Err(_) => {
            state.pending.lock().unwrap().remove(&id);
            Err("The panel isn't loaded yet".into())
        }
    }
}

/// Async so it runs off the main thread: `send_command_await` may be blocking a thread
/// while it waits for exactly this report.
#[tauri::command]
pub async fn bridge_report(app: AppHandle, report: BridgeReport) {
    let state = app.state::<AppState>();
    match report {
        BridgeReport::State { state: bot } => {
            let changes = state.store.lock().unwrap().apply(bot, Instant::now());
            dispatch(&app, changes);
        }
        BridgeReport::Notice { level, message } => dispatch_notice(&app, &level, &message),
        BridgeReport::Theme { theme } => crate::mini_player::on_panel_theme(&app, theme),
        BridgeReport::Result { id, ok, message, data } => {
            if let Some(tx) = state.pending.lock().unwrap().remove(&id) {
                let outcome = if ok { Ok(data) } else { Err(message.unwrap_or_else(|| "The bot rejected the request".into())) };
                let _ = tx.send(outcome);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn init_script_embeds_origin_and_both_scripts() {
        let s = init_script("https://cloutkings.duckdns.org");
        assert!(s.contains("\"https://cloutkings.duckdns.org\""));
        assert!(s.contains("__cksCore") && s.contains("__cksBridge"));
    }

    #[test]
    fn command_js_round_trips_hostile_text() {
        let cmd = BotCommand::PlayQuery("Ke$ha \"Tik\" \\ Tok\n</script>\u{2028}🎵".into());
        let js = command_js(7, &cmd);
        let prefix = "window.__cksBridge && window.__cksBridge.run(7, ";
        assert!(js.starts_with(prefix) && js.ends_with(");"));
        let back: BotCommand = serde_json::from_str(&js[prefix.len()..js.len() - 2]).unwrap();
        assert_eq!(back, cmd);
    }

    #[test]
    fn add_to_playlist_round_trips_hostile_song_text() {
        // Song titles and uris come from YouTube: they reach the page only as JSON inside the eval'd call.
        let cmd = BotCommand::AddToPlaylist {
            name: "Chill \" vibes".into(),
            folder: Some("Fred/O'ld".into()),
            song: PlaylistSong {
                uri: "https://y/watch?v=\u{2028}</script>".into(),
                title: "'); alert(1); //\n${x}`".into(),
                author: "Ke$ha \\ 🎵".into(),
                duration_ms: 1,
                artwork_url: None,
            },
        };
        let js = command_js(9, &cmd);
        let prefix = "window.__cksBridge && window.__cksBridge.run(9, ";
        let back: BotCommand = serde_json::from_str(&js[prefix.len()..js.len() - 2]).unwrap();
        assert_eq!(back, cmd);
    }

    #[test]
    fn bot_command_json_shape() {
        assert_eq!(serde_json::to_string(&BotCommand::Skip).unwrap(), r#"{"type":"Skip"}"#);
        assert_eq!(serde_json::to_string(&BotCommand::SetVolume(80)).unwrap(), r#"{"type":"SetVolume","value":80}"#);
    }

    #[test]
    fn show_whats_new_serializes() {
        // A unit variant: no "value" key, which is what bridge.js looks for by "type" alone.
        assert_eq!(serde_json::to_string(&BotCommand::ShowWhatsNew).unwrap(), r#"{"type":"ShowWhatsNew"}"#);
        let back: BotCommand = serde_json::from_str(r#"{"type":"ShowWhatsNew"}"#).unwrap();
        assert_eq!(back, BotCommand::ShowWhatsNew);
    }

    #[test]
    fn themes_command_is_a_unit_fetch_the_bridge_maps_to_the_theme_list() {
        // A unit variant: no "value" key, which is what core.js's commandToRequest looks for by "type" alone.
        assert_eq!(serde_json::to_string(&BotCommand::Themes).unwrap(), r#"{"type":"Themes"}"#);
        let back: BotCommand = serde_json::from_str(r#"{"type":"Themes"}"#).unwrap();
        assert_eq!(back, BotCommand::Themes);
        assert_eq!(command_js(5, &BotCommand::Themes), r#"window.__cksBridge && window.__cksBridge.run(5, {"type":"Themes"});"#);
    }

    #[test]
    fn theme_report_carries_the_panels_choice() {
        let r: BridgeReport = serde_json::from_str(
            r##"{"type":"theme","theme":{"id":"ember","vars":{"--accent":"#7c5cff"},"choice":"custom:k3j9x0q2m7ab"}}"##,
        )
        .unwrap();
        let BridgeReport::Theme { theme } = r else { panic!("expected theme") };
        assert_eq!((theme.id.as_str(), theme.choice.as_deref()), ("ember", Some("custom:k3j9x0q2m7ab")));
        assert_eq!(theme.vars["--accent"], "#7c5cff");
        // the bridge before this version sent none
        let r: BridgeReport = serde_json::from_str(r#"{"type":"theme","theme":{"id":"neon","vars":{}}}"#).unwrap();
        let BridgeReport::Theme { theme } = r else { panic!("expected theme") };
        assert_eq!(theme.choice, None);
    }

    #[test]
    fn bridge_report_deserializes_notice_and_result() {
        let n: BridgeReport = serde_json::from_str(r#"{"type":"notice","level":"error","message":"No results"}"#).unwrap();
        assert!(matches!(n, BridgeReport::Notice { ref message, .. } if message == "No results"));
        let r: BridgeReport = serde_json::from_str(r#"{"type":"result","id":3,"ok":false,"message":"No voice channel available"}"#).unwrap();
        assert!(matches!(r, BridgeReport::Result { id: 3, ok: false, .. }));
    }

    #[test]
    fn result_report_carries_data() {
        let r: BridgeReport =
            serde_json::from_str(r#"{"type":"result","id":4,"ok":true,"message":null,"data":[{"title":"x"}]}"#).unwrap();
        let BridgeReport::Result { id: 4, ok: true, data: Some(data), .. } = r else { panic!("expected result with data") };
        assert_eq!(data, serde_json::json!([{ "title": "x" }]));
        let r: BridgeReport = serde_json::from_str(r#"{"type":"result","id":5,"ok":true,"message":null}"#).unwrap();
        assert!(matches!(r, BridgeReport::Result { id: 5, data: None, .. }));
    }

    #[test]
    fn search_command_round_trips() {
        let cmd = BotCommand::Search("a \"b\"
".into());
        let js = command_js(1, &cmd);
        let prefix = "window.__cksBridge && window.__cksBridge.run(1, ";
        let back: BotCommand = serde_json::from_str(&js[prefix.len()..js.len() - 2]).unwrap();
        assert_eq!(back, cmd);
        assert_eq!(serde_json::to_string(&BotCommand::History).unwrap(), r#"{"type":"History"}"#);
    }

    #[test]
    fn bridge_report_deserializes_state() {
        let r: BridgeReport = serde_json::from_str(
            r#"{"type":"state","state":{"connected":true,"signedIn":true,"me":null,"guild":{"id":"9","name":"CK"},
                "track":{"title":"T","author":"A","artworkUrl":null,"uri":"yt","durationMs":214000,"positionMs":85000,
                "isStream":false,"requestedBy":"fred","requestedById":"1"},"paused":false,"volume":80,"queueHead":null}}"#,
        )
        .unwrap();
        let BridgeReport::State { state } = r else { panic!("expected state") };
        assert_eq!(state.track.unwrap().duration_ms, 214_000);
        assert_eq!(state.volume, 80);
    }

    #[test]
    fn redact_strips_query_strings() {
        assert_eq!(
            redact_urls("Could not load https://open.spotify.com/playlist/x?si=abc&t=1 now"),
            "Could not load https://open.spotify.com/playlist/x now"
        );
        assert_eq!(redact_urls("No results found."), "No results found.");
    }
}
