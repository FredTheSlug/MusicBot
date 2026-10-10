//! Spotify desktop integration: read the song Spotify is playing (Windows media sessions,
//! no Spotify login) and turn Spotify links into something the bot can play.

use crate::bridge::{self, BotCommand};
use crate::AppState;
use serde::Serialize;
use std::time::Duration;
use tauri::{AppHandle, Manager};
use tauri_plugin_clipboard_manager::ClipboardExt;

const LINK_KINDS: [&str; 4] = ["playlist", "album", "track", "artist"];
const LINK_HOSTS: [&str; 2] = ["open.spotify.com", "play.spotify.com"];

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpotifyNow {
    pub title: String,
    pub artist: String,
    pub playing: bool,
}

/// One entry from Windows' list of media sessions (every app that shows in the media flyout).
#[derive(Debug, Clone, PartialEq)]
pub struct MediaSessionInfo {
    pub app_id: String,
    pub title: String,
    pub artist: String,
    pub playing: bool,
}

fn canonical(kind: &str, id: &str) -> Option<String> {
    let valid = LINK_KINDS.contains(&kind) && !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric());
    valid.then(|| format!("https://open.spotify.com/{kind}/{id}"))
}

fn parse_token(token: &str) -> Option<String> {
    if let Some(rest) = token.strip_prefix("spotify:") {
        let mut parts = rest.split(':');
        return canonical(parts.next()?, parts.next()?);
    }
    let url = url::Url::parse(token).ok()?;
    if !matches!(url.scheme(), "https" | "http") || !LINK_HOSTS.contains(&url.host_str()?) {
        return None;
    }
    let mut segments = url.path_segments()?.filter(|s| !s.is_empty()).peekable();
    if segments.peek().is_some_and(|s| s.starts_with("intl-")) {
        segments.next();
    }
    canonical(segments.next()?, segments.next()?)
}

/// First Spotify playlist/album/track/artist link in `text` (pasted or dropped), as a clean
/// `https://open.spotify.com/{kind}/{id}` without tracking parameters.
pub fn parse_spotify_link(text: &str) -> Option<String> {
    text.split_whitespace().find_map(parse_token)
}

/// Spotify's session (desktop or Store install), preferring one that's playing.
pub fn pick_spotify_session<'a>(sessions: &'a [MediaSessionInfo], own_app_id: &str) -> Option<&'a MediaSessionInfo> {
    let mut candidates = sessions.iter().filter(|s| s.app_id != own_app_id && s.app_id.to_lowercase().contains("spotify"));
    let first = candidates.next()?;
    Some(if first.playing { first } else { candidates.find(|s| s.playing).unwrap_or(first) })
}

/// What the bot searches for: it plays Spotify songs by finding them on YouTube.
pub fn query_for(now: &SpotifyNow) -> String {
    if now.artist.is_empty() { now.title.clone() } else { format!("{} - {}", now.artist, now.title) }
}

/// `available` is false when Windows' media-session API can't be used at all.
#[derive(Debug, Clone, PartialEq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpotifyStatus {
    pub available: bool,
    pub now: Option<SpotifyNow>,
}

#[cfg(windows)]
mod platform {
    use super::MediaSessionInfo;
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession as Session,
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };

    /// Blocking: call off the main thread.
    pub fn sessions() -> windows::core::Result<Vec<(MediaSessionInfo, Session)>> {
        let manager = Manager::RequestAsync()?.join()?;
        let mut out = Vec::new();
        for session in manager.GetSessions()? {
            let Ok(app_id) = session.SourceAppUserModelId() else { continue };
            let Ok(props) = session.TryGetMediaPropertiesAsync().and_then(|op| op.join()) else { continue };
            let playing = session
                .GetPlaybackInfo()
                .and_then(|info| info.PlaybackStatus())
                .is_ok_and(|status| status == Status::Playing);
            let info = MediaSessionInfo {
                app_id: app_id.to_string(),
                title: props.Title().map(|t| t.to_string()).unwrap_or_default(),
                artist: props.Artist().map(|a| a.to_string()).unwrap_or_default(),
                playing,
            };
            out.push((info, session));
        }
        Ok(out)
    }

    pub fn pause(session: &Session) -> windows::core::Result<bool> {
        session.TryPauseAsync()?.join()
    }
}

/// What Spotify is playing right now. Blocking: call off the main thread.
pub fn current(own_app_id: &str) -> SpotifyStatus {
    #[cfg(windows)]
    {
        match platform::sessions() {
            Ok(sessions) => {
                let infos: Vec<MediaSessionInfo> = sessions.into_iter().map(|(info, _)| info).collect();
                let now = pick_spotify_session(&infos, own_app_id)
                    .map(|s| SpotifyNow { title: s.title.clone(), artist: s.artist.clone(), playing: s.playing });
                SpotifyStatus { available: true, now }
            }
            Err(e) => {
                log::warn!("Windows media sessions unavailable: {e}");
                SpotifyStatus::default()
            }
        }
    }
    #[cfg(not(windows))]
    {
        let _ = own_app_id;
        SpotifyStatus::default()
    }
}

/// Pauses the Spotify session `current` would pick. Blocking: call off the main thread.
pub fn pause_spotify(own_app_id: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        let sessions = platform::sessions().map_err(|e| e.to_string())?;
        let infos: Vec<MediaSessionInfo> = sessions.iter().map(|(info, _)| info.clone()).collect();
        let picked = pick_spotify_session(&infos, own_app_id).ok_or("Spotify isn't open")?;
        let (_, session) = sessions.iter().find(|(info, _)| info.app_id == picked.app_id).ok_or("Spotify isn't open")?;
        platform::pause(session).map(|_| ()).map_err(|e| e.to_string())
    }
    #[cfg(not(windows))]
    {
        let _ = own_app_id;
        Err("Only supported on Windows".into())
    }
}

// ---- Actions shared by the tray and the mini-player (all blocking; run off the main thread) ----

const SEND_TIMEOUT: Duration = Duration::from_secs(10);

pub fn own_app_id(app: &AppHandle) -> String {
    app.config().identifier.clone()
}

/// Queues Spotify's current song on the bot; pauses Spotify afterwards if the setting is on.
pub fn send_current(app: &AppHandle) -> Result<String, String> {
    let own = own_app_id(app);
    let now = current(&own).now.filter(|n| n.playing).ok_or("Spotify isn't playing anything")?;
    let query = query_for(&now);
    bridge::send_command_await(app, BotCommand::PlayQuery(query.clone()), SEND_TIMEOUT)?;
    if app.state::<AppState>().settings.lock().unwrap().pause_spotify_after_send {
        if let Err(e) = pause_spotify(&own) {
            log::warn!("could not pause Spotify: {e}");
        }
    }
    Ok(format!("Sent “{query}” to the bot"))
}

/// Plays the first Spotify link found in pasted or dropped text.
pub fn play_link_text(app: &AppHandle, text: &str) -> Result<String, String> {
    let preview: String = bridge::redact_urls(text.trim()).chars().take(120).collect();
    log::info!("Spotify link input: {preview}");
    let link = parse_spotify_link(text).ok_or("That's not a Spotify link")?;
    bridge::send_command_await(app, BotCommand::PlaySpotifyLink(link), SEND_TIMEOUT)?;
    Ok("Sent the Spotify link to the bot".into())
}

pub fn play_clipboard(app: &AppHandle) -> Result<String, String> {
    let text = app.clipboard().read_text().map_err(|_| "The clipboard doesn't contain a link".to_string())?;
    play_link_text(app, &text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_accepted_link_forms() {
        assert_eq!(parse_spotify_link("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc").as_deref(), Some("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"));
        assert_eq!(parse_spotify_link("https://open.spotify.com/intl-de/album/1ATL5GLyefJaxhQzSPVrLX").as_deref(), Some("https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX"));
        assert_eq!(parse_spotify_link("spotify:track:4uLU6hMCjMI75M1A2tKUQC").as_deref(), Some("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"));
        assert_eq!(parse_spotify_link("  https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUXeCF  ").as_deref(), Some("https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUXeCF"));
        assert_eq!(parse_spotify_link("https://open.spotify.com/track/AAA\nhttps://open.spotify.com/track/BBB").as_deref(), Some("https://open.spotify.com/track/AAA"));
    }

    #[test]
    fn rejects_non_spotify_and_unsupported() {
        for t in ["hello", "https://example.com/playlist/x", "https://open.spotify.com/episode/abc", "https://open.spotify.com/playlist/", "https://open.spotify.com/track/ab$cd", "spotify:user:x", "https://open.spotify.com.evil.io/track/abc"] {
            assert_eq!(parse_spotify_link(t), None, "{t}");
        }
    }

    #[test]
    fn picks_spotify_session() {
        let s = |app: &str, playing| MediaSessionInfo { app_id: app.into(), title: "T".into(), artist: "A".into(), playing };
        let list = [s("chrome", true), s("Spotify.exe", false), s("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", true)];
        assert_eq!(pick_spotify_session(&list, "com.fredtheslug.musicbot.desktop").unwrap().app_id, "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify");
        assert_eq!(pick_spotify_session(&list[..2], "x").unwrap().app_id, "Spotify.exe");
        assert!(pick_spotify_session(&list[..1], "x").is_none());
        assert!(pick_spotify_session(&[s("Spotify.exe", true)], "Spotify.exe").is_none());
    }

    #[test]
    fn query_is_artist_dash_title() {
        assert_eq!(query_for(&SpotifyNow { title: "Small Worlds".into(), artist: "Mac Miller".into(), playing: true }), "Mac Miller - Small Worlds");
        assert_eq!(query_for(&SpotifyNow { title: "Untitled".into(), artist: "".into(), playing: true }), "Untitled");
    }
}
