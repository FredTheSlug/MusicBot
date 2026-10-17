//! Tray icon: tooltip, now-playing label, and the menu. Later tasks add their own items.

use crate::bridge::{self, BotCommand};
use crate::mini_player;
use crate::spotify::{self, SpotifyStatus};
use crate::state::BotState;
use crate::{settings, show_main, AppState};
use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Wry};
use tauri_plugin_autostart::ManagerExt;

pub const TRAY_ID: &str = "main-tray";

pub const NOW_PLAYING: &str = "now-playing";
pub const TOGGLE_PAUSE: &str = "toggle-pause";
pub const SKIP: &str = "skip";
pub const SEND_SPOTIFY: &str = "send-spotify";
pub const PLAY_CLIPBOARD: &str = "play-clipboard";
pub const SHOW_PANEL: &str = "show-panel";
pub const MINI_PLAYER: &str = "mini-player";
pub const NOTIFY_NOW_PLAYING: &str = "notify-now-playing";
pub const NOTIFY_UP_NEXT: &str = "notify-up-next";
pub const PAUSE_SPOTIFY_AFTER_SEND: &str = "pause-spotify-after-send";
pub const START_WITH_WINDOWS: &str = "start-with-windows";
pub const CHECK_UPDATES: &str = "check-updates";
pub const QUIT: &str = "quit";

/// Windows truncates tray tooltips at 127 UTF-16 units.
const TOOLTIP_MAX_UTF16: usize = 127;

/// Menu items whose text or enabled state follows the bot state.
struct TrayItems {
    now_playing: MenuItem<Wry>,
    toggle_pause: MenuItem<Wry>,
    skip: MenuItem<Wry>,
    send_spotify: MenuItem<Wry>,
    notify_now_playing: CheckMenuItem<Wry>,
    notify_up_next: CheckMenuItem<Wry>,
    pause_spotify_after_send: CheckMenuItem<Wry>,
    start_with_windows: CheckMenuItem<Wry>,
}

pub fn tooltip_text(state: Option<&BotState>, stale: bool) -> String {
    let text = match state {
        // signed_in is only false after the bot itself answered 401/4401, so it wins over `connected`.
        Some(s) if !stale && !s.signed_in => "Not signed in".to_string(),
        Some(s) if s.connected && !stale => {
            if let Some(track) = &s.track {
                if track.author.is_empty() { track.title.clone() } else { format!("{} — {}", track.title, track.author) }
            } else {
                "Nothing playing".to_string()
            }
        }
        _ => "Can't reach the bot".to_string(),
    };
    truncate_utf16(&text, TOOLTIP_MAX_UTF16)
}

fn truncate_utf16(text: &str, max: usize) -> String {
    if text.encode_utf16().count() <= max {
        return text.to_string();
    }
    let mut out = String::new();
    let mut used = 0;
    for ch in text.chars() {
        // Leave one unit for the ellipsis.
        if used + ch.len_utf16() > max - 1 {
            break;
        }
        used += ch.len_utf16();
        out.push(ch);
    }
    out.push('…');
    out
}

pub fn build(app: &AppHandle) -> tauri::Result<()> {
    let now_playing = MenuItem::with_id(app, NOW_PLAYING, "Can't reach the bot", false, None::<&str>)?;
    let toggle_pause = MenuItem::with_id(app, TOGGLE_PAUSE, "Pause", false, None::<&str>)?;
    let skip = MenuItem::with_id(app, SKIP, "Skip", false, None::<&str>)?;
    let send_spotify = MenuItem::with_id(app, SEND_SPOTIFY, "Send my Spotify song", false, None::<&str>)?;
    let play_clipboard = MenuItem::with_id(app, PLAY_CLIPBOARD, "Play link from clipboard", true, None::<&str>)?;
    let show_panel = MenuItem::with_id(app, SHOW_PANEL, "Show panel", true, None::<&str>)?;
    let mini = MenuItem::with_id(app, MINI_PLAYER, "Mini-player", true, None::<&str>)?;
    let current = app.state::<AppState>().settings.lock().unwrap().clone();
    let notify_now_playing = CheckMenuItem::with_id(app, NOTIFY_NOW_PLAYING, "Now-playing notifications", true, current.notify_now_playing, None::<&str>)?;
    let notify_up_next = CheckMenuItem::with_id(app, NOTIFY_UP_NEXT, "\u{201c}Up next\u{201d} notifications", true, current.notify_up_next, None::<&str>)?;
    let pause_spotify_after_send = CheckMenuItem::with_id(app, PAUSE_SPOTIFY_AFTER_SEND, "Pause Spotify after sending", true, current.pause_spotify_after_send, None::<&str>)?;
    let start_with_windows = CheckMenuItem::with_id(app, START_WITH_WINDOWS, "Start with Windows", true, current.start_with_windows, None::<&str>)?;
    let check_updates = MenuItem::with_id(app, CHECK_UPDATES, "Check for updates", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, QUIT, "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &now_playing,
            &toggle_pause,
            &skip,
            &send_spotify,
            &play_clipboard,
            &PredefinedMenuItem::separator(app)?,
            &show_panel,
            &mini,
            &PredefinedMenuItem::separator(app)?,
            &notify_now_playing,
            &notify_up_next,
            &pause_spotify_after_send,
            &start_with_windows,
            &PredefinedMenuItem::separator(app)?,
            &check_updates,
            &quit,
        ],
    )?;

    let mut tray = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("Can't reach the bot")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| on_menu(app, event.id().as_ref()))
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                mini_player::toggle(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    app.manage(TrayItems {
        now_playing,
        toggle_pause,
        skip,
        send_spotify,
        notify_now_playing,
        notify_up_next,
        pause_spotify_after_send,
        start_with_windows,
    });
    Ok(())
}

fn on_menu(app: &AppHandle, id: &str) {
    let command = match id {
        TOGGLE_PAUSE => Some(BotCommand::TogglePause),
        SKIP => Some(BotCommand::Skip),
        SHOW_PANEL => {
            show_main(app);
            None
        }
        MINI_PLAYER => {
            mini_player::toggle(app);
            None
        }
        SEND_SPOTIFY => {
            run_action(app, spotify::send_current);
            None
        }
        PLAY_CLIPBOARD => {
            run_action(app, mini_player::play_clipboard);
            None
        }
        NOTIFY_NOW_PLAYING | NOTIFY_UP_NEXT | PAUSE_SPOTIFY_AFTER_SEND | START_WITH_WINDOWS => {
            toggle_setting(app, id);
            None
        }
        CHECK_UPDATES => {
            crate::updates::check_for_updates(app, true);
            None
        }
        QUIT => {
            app.exit(0);
            None
        }
        _ => None,
    };
    if let Some(command) = command {
        if let Err(e) = bridge::send_command(app, command) {
            log::warn!("tray command failed: {e}");
        }
    }
}

/// The menu has already flipped the check mark; mirror it into settings and save.
fn toggle_setting(app: &AppHandle, id: &str) {
    let items = app.state::<TrayItems>();
    let item = match id {
        NOTIFY_NOW_PLAYING => &items.notify_now_playing,
        NOTIFY_UP_NEXT => &items.notify_up_next,
        PAUSE_SPOTIFY_AFTER_SEND => &items.pause_spotify_after_send,
        START_WITH_WINDOWS => &items.start_with_windows,
        _ => return,
    };
    let checked = item.is_checked().unwrap_or(false);
    if id == START_WITH_WINDOWS {
        let autolaunch = app.autolaunch();
        if let Err(e) = if checked { autolaunch.enable() } else { autolaunch.disable() } {
            let _ = item.set_checked(!checked);
            crate::dispatch_notice(app, "error", &format!("Couldn't change Start with Windows: {e}"));
            return;
        }
    }
    let app_state = app.state::<AppState>();
    let mut current = app_state.settings.lock().unwrap();
    match id {
        NOTIFY_NOW_PLAYING => current.notify_now_playing = checked,
        NOTIFY_UP_NEXT => current.notify_up_next = checked,
        PAUSE_SPOTIFY_AFTER_SEND => current.pause_spotify_after_send = checked,
        _ => current.start_with_windows = checked,
    }
    if let Err(e) = settings::save(&app_state.settings_path, &current) {
        log::warn!("could not save settings: {e}");
    }
}

/// Actions that block (Spotify's media sessions, the clipboard, waiting for the bot) run on a worker thread; their
/// answer goes to the mini-player's status line, or a toast.
pub(crate) fn run_action(app: &AppHandle, action: fn(&AppHandle) -> Result<String, String>) {
    let app = app.clone();
    std::thread::spawn(move || match action(&app) {
        Ok(message) => crate::dispatch_notice(&app, "info", &message),
        Err(message) => crate::dispatch_notice(&app, "error", &message),
    });
}

pub fn set_spotify(app: &AppHandle, status: &SpotifyStatus) {
    let items = app.state::<TrayItems>();
    let playing = status.now.as_ref().is_some_and(|n| n.playing);
    let _ = items.send_spotify.set_text(if status.available { "Send my Spotify song" } else { "Spotify not available" });
    let _ = items.send_spotify.set_enabled(status.available && playing);
}

/// Re-reads the store and updates the tooltip and menu labels.
pub fn refresh(app: &AppHandle) {
    let app_state = app.state::<AppState>();
    let (tooltip, has_track, paused) = {
        let store = app_state.store.lock().unwrap();
        let state = store.current();
        let tooltip = tooltip_text(state, store.is_stale());
        let live = state.is_some_and(|s| s.connected && s.signed_in) && !store.is_stale();
        let has_track = live && state.is_some_and(|s| s.track.is_some());
        (tooltip, has_track, state.is_some_and(|s| s.paused))
    };
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_tooltip(Some(&tooltip));
    }
    let items = app.state::<TrayItems>();
    let _ = items.now_playing.set_text(&tooltip);
    let _ = items.toggle_pause.set_text(if paused { "Play" } else { "Pause" });
    let _ = items.toggle_pause.set_enabled(has_track);
    let _ = items.skip.set_enabled(has_track);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::{BotState, Track};

    fn playing(title: &str, author: &str) -> BotState {
        BotState {
            connected: true,
            signed_in: true,
            track: Some(Track { title: title.into(), author: author.into(), ..Track::default() }),
            volume: 100,
            ..BotState::default()
        }
    }

    #[test]
    fn tooltip_shows_title_and_artist() {
        assert_eq!(tooltip_text(Some(&playing("Small Worlds", "Mac Miller")), false), "Small Worlds — Mac Miller");
    }

    #[test]
    fn tooltip_nothing_playing() {
        let idle = BotState { track: None, ..playing("x", "y") };
        assert_eq!(tooltip_text(Some(&idle), false), "Nothing playing");
    }

    #[test]
    fn tooltip_signed_out() {
        let out = BotState { signed_in: false, track: None, ..playing("x", "y") };
        assert_eq!(tooltip_text(Some(&out), false), "Not signed in");
        // After a bot restart the bot rejects the socket (4401), so the bridge reports both false.
        let rejected = BotState { connected: false, ..out };
        assert_eq!(tooltip_text(Some(&rejected), false), "Not signed in");
    }

    #[test]
    fn tooltip_unreachable() {
        assert_eq!(tooltip_text(Some(&playing("A", "B")), true), "Can't reach the bot");
        assert_eq!(tooltip_text(None, false), "Can't reach the bot");
        let down = BotState { connected: false, ..playing("A", "B") };
        assert_eq!(tooltip_text(Some(&down), false), "Can't reach the bot");
    }

    #[test]
    fn the_tray_plays_any_link_from_the_clipboard_by_the_mini_players_path() {
        use crate::source;
        let code = source::code(include_str!("tray.rs"));
        let squashed = source::squash(code);
        assert!(squashed.contains(r#"MenuItem::with_id(app, PLAY_CLIPBOARD, "Play link from clipboard", true, None::<&str>)?"#));
        assert!(squashed.contains("PLAY_CLIPBOARD => { run_action(app, mini_player::play_clipboard); None }"));
        assert!(!squashed.contains("Spotify link from clipboard"));
        // run_action only hands the answer on: what was on the clipboard is logged by clipboard_log alone.
        assert!(!source::item(code, "pub(crate) fn run_action(").contains("log::"));
    }

    #[test]
    fn tooltip_truncates_on_char_boundary() {
        let result = tooltip_text(Some(&playing(&"🎵".repeat(100), "Artist")), false);
        assert!(result.encode_utf16().count() <= 127, "{} units", result.encode_utf16().count());
        assert!(result.ends_with('…'));
    }
}
