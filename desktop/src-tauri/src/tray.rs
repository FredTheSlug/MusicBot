//! Tray icon: tooltip, now-playing label, and the menu. Later tasks add their own items.

use crate::bridge::{self, BotCommand};
use crate::mini_player;
use crate::state::BotState;
use crate::{show_main, AppState};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Wry};

pub const TRAY_ID: &str = "main-tray";

pub const NOW_PLAYING: &str = "now-playing";
pub const TOGGLE_PAUSE: &str = "toggle-pause";
pub const SKIP: &str = "skip";
pub const SEND_SPOTIFY: &str = "send-spotify";
pub const PASTE_SPOTIFY: &str = "paste-spotify";
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
}

pub fn tooltip_text(state: Option<&BotState>, stale: bool) -> String {
    let text = match state {
        Some(s) if s.connected && !stale => {
            if !s.signed_in {
                "Not signed in".to_string()
            } else if let Some(track) = &s.track {
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
    let show_panel = MenuItem::with_id(app, SHOW_PANEL, "Show panel", true, None::<&str>)?;
    let mini = MenuItem::with_id(app, MINI_PLAYER, "Mini-player", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, QUIT, "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &now_playing,
            &toggle_pause,
            &skip,
            &PredefinedMenuItem::separator(app)?,
            &show_panel,
            &mini,
            &PredefinedMenuItem::separator(app)?,
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
    app.manage(TrayItems { now_playing, toggle_pause, skip });
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
    }

    #[test]
    fn tooltip_unreachable() {
        assert_eq!(tooltip_text(Some(&playing("A", "B")), true), "Can't reach the bot");
        assert_eq!(tooltip_text(None, false), "Can't reach the bot");
        let down = BotState { connected: false, ..playing("A", "B") };
        assert_eq!(tooltip_text(Some(&down), false), "Can't reach the bot");
    }

    #[test]
    fn tooltip_truncates_on_char_boundary() {
        let result = tooltip_text(Some(&playing(&"🎵".repeat(100), "Artist")), false);
        assert!(result.encode_utf16().count() <= 127, "{} units", result.encode_utf16().count());
        assert!(result.ends_with('…'));
    }
}
