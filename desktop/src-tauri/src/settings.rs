//! User settings, stored as JSON in the app config dir. Missing fields take defaults;
//! an unreadable file is replaced with defaults.

use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::Path;

pub const DEFAULT_SERVER_URL: &str = "https://cloutkings.duckdns.org";
const PANEL_PATH: &str = "/web/index.html";

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct MiniPos {
    pub x: Option<i32>,
    pub y: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub server_url: String,
    pub notify_now_playing: bool,
    pub notify_up_next: bool,
    pub pause_spotify_after_send: bool,
    pub start_with_windows: bool,
    pub mini_player: MiniPos,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            server_url: DEFAULT_SERVER_URL.to_string(),
            notify_now_playing: true,
            notify_up_next: true,
            pause_spotify_after_send: true,
            start_with_windows: false,
            mini_player: MiniPos::default(),
        }
    }
}

impl Settings {
    /// `scheme://host[:port]` of the configured server, or None if `serverUrl` isn't a usable URL.
    pub fn server_origin(&self) -> Option<String> {
        let url = url::Url::parse(&self.server_url).ok()?;
        let origin = url.origin();
        origin.is_tuple().then(|| origin.ascii_serialization())
    }

    pub fn panel_url(&self) -> Option<url::Url> {
        url::Url::parse(&format!("{}{}", self.server_origin()?, PANEL_PATH)).ok()
    }
}

pub fn load(path: &Path) -> Settings {
    let Ok(text) = fs::read_to_string(path) else {
        return Settings::default();
    };
    match serde_json::from_str(&text) {
        Ok(settings) => settings,
        Err(e) => {
            log::warn!("settings file is unreadable ({e}); using defaults");
            let defaults = Settings::default();
            if let Err(e) = save(path, &defaults) {
                log::warn!("could not rewrite settings file: {e}");
            }
            defaults
        }
    }
}

pub fn save(path: &Path, settings: &Settings) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    fs::write(path, serde_json::to_string_pretty(settings).map_err(io::Error::other)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_file_gives_defaults() {
        let d = tempfile::tempdir().unwrap();
        let s = load(&d.path().join("settings.json"));
        assert_eq!(s, Settings::default());
        assert_eq!(s.server_url, "https://cloutkings.duckdns.org");
        assert!(s.notify_now_playing && s.notify_up_next && s.pause_spotify_after_send && !s.start_with_windows);
        assert_eq!((s.mini_player.x, s.mini_player.y), (None, None));
    }

    #[test]
    fn partial_file_fills_defaults() {
        let d = tempfile::tempdir().unwrap();
        let path = d.path().join("settings.json");
        fs::write(&path, r#"{"notifyUpNext": false}"#).unwrap();
        let s = load(&path);
        assert!(!s.notify_up_next);
        assert_eq!(Settings { notify_up_next: true, ..s }, Settings::default());
    }

    #[test]
    fn corrupt_file_gives_defaults_and_is_rewritten() {
        let d = tempfile::tempdir().unwrap();
        let path = d.path().join("settings.json");
        fs::write(&path, "{not json").unwrap();
        assert_eq!(load(&path), Settings::default());
        let rewritten = fs::read_to_string(&path).unwrap();
        assert!(serde_json::from_str::<Settings>(&rewritten).is_ok());
    }

    #[test]
    fn save_then_load_round_trips() {
        let d = tempfile::tempdir().unwrap();
        let path = d.path().join("nested").join("settings.json");
        let s = Settings {
            notify_now_playing: false,
            mini_player: MiniPos { x: Some(100), y: Some(200) },
            ..Settings::default()
        };
        save(&path, &s).unwrap();
        assert_eq!(load(&path), s);
    }

    #[test]
    fn origin_and_panel_url() {
        let mut s = Settings::default();
        assert_eq!(s.server_origin().as_deref(), Some("https://cloutkings.duckdns.org"));
        assert_eq!(s.panel_url().unwrap().as_str(), "https://cloutkings.duckdns.org/web/index.html");
        s.server_url = "http://10.0.0.43:8888/web/".into();
        assert_eq!(s.server_origin().as_deref(), Some("http://10.0.0.43:8888"));
        s.server_url = "not a url".into();
        assert_eq!(s.server_origin(), None);
    }
}
