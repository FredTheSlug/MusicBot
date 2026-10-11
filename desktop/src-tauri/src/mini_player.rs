//! The always-on-top mini-player window and the commands its bundled UI may call.

use crate::bridge::{self, BotCommand};
use crate::lookup::{self, Lyrics, SearchResult};
use crate::spotify::{self, SpotifyStatus};
use crate::settings::Settings;
use crate::state::BotState;
use crate::theme::{self, PanelTheme};
use crate::{settings, show_main, AppState};
use serde::Serialize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager, PhysicalPosition, WebviewUrl, WebviewWindowBuilder};

pub const LABEL: &str = "mini-player";
const WIDTH: f64 = 360.0;
const HEIGHT: f64 = 170.0;
const MARGIN: i32 = 12;
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const SEARCH_TIMEOUT: Duration = Duration::from_secs(10);
const LYRICS_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: u32,
    pub h: u32,
}

impl Rect {
    fn contains(&self, x: i32, y: i32) -> bool {
        x >= self.x && y >= self.y && x < self.x + self.w as i32 && y < self.y + self.h as i32
    }
}

/// What the mini-player UI renders. `position_at_ms` lets it advance the progress bar locally.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UiState {
    pub state: Option<BotState>,
    pub position_ms: Option<u64>,
    pub position_at_ms: u64,
    pub stale: bool,
}

/// The mini-player's own settings, as its UI needs them on load.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MiniPrefs {
    pub opacity: f64,
    pub match_panel: bool,
    pub theme: String,
    pub effective: PanelTheme,
}

#[derive(Debug, Clone, Serialize)]
pub struct Notice {
    pub level: String,
    pub message: String,
}

/// Bottom-right corner of a monitor's work area, clear of the taskbar.
pub fn default_position(work_area: Rect, size: (u32, u32)) -> (i32, i32) {
    (
        work_area.x + work_area.w as i32 - size.0 as i32 - MARGIN,
        work_area.y + work_area.h as i32 - size.1 as i32 - MARGIN,
    )
}

/// Uses the saved spot if the window's center still lands on a connected monitor.
pub fn resolve_position(saved: (Option<i32>, Option<i32>), monitors: &[Rect], size: (u32, u32)) -> (i32, i32) {
    if let (Some(x), Some(y)) = saved {
        let center = (x + size.0 as i32 / 2, y + size.1 as i32 / 2);
        if monitors.is_empty() || monitors.iter().any(|m| m.contains(center.0, center.1)) {
            return (x, y);
        }
    }
    monitors.first().map_or((0, 0), |m| default_position(*m, size))
}

pub fn create(app: &AppHandle) -> tauri::Result<()> {
    WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("mini-player/index.html".into()))
        .title("Clout Kings Mini-player")
        .inner_size(WIDTH, HEIGHT)
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .visible(false)
        .disable_drag_drop_handler()
        .build()?;
    Ok(())
}

pub fn is_visible(app: &AppHandle) -> bool {
    app.get_webview_window(LABEL).is_some_and(|w| w.is_visible().unwrap_or(false))
}

pub fn toggle(app: &AppHandle) {
    let Some(window) = app.get_webview_window(LABEL) else { return };
    if is_visible(app) {
        let _ = window.hide();
        return;
    }
    let monitors: Vec<Rect> = window
        .available_monitors()
        .unwrap_or_default()
        .iter()
        .map(|m| {
            let area = m.work_area();
            Rect { x: area.position.x, y: area.position.y, w: area.size.width, h: area.size.height }
        })
        .collect();
    let size = window.outer_size().map(|s| (s.width, s.height)).unwrap_or((WIDTH as u32, HEIGHT as u32));
    let app_state = app.state::<AppState>();
    let saved = {
        let settings = app_state.settings.lock().unwrap();
        (settings.mini_player.x, settings.mini_player.y)
    };
    let (x, y) = resolve_position(saved, &monitors, size);
    let _ = window.set_position(PhysicalPosition::new(x, y));
    let _ = window.show();
    let _ = window.set_focus();
    emit_state(app);
}

fn epoch_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

pub fn ui_state(app: &AppHandle) -> UiState {
    let app_state = app.state::<AppState>();
    let store = app_state.store.lock().unwrap();
    UiState {
        state: store.current().cloned(),
        position_ms: store.position_ms(Instant::now()),
        position_at_ms: epoch_ms(),
        stale: store.is_stale(),
    }
}

pub fn emit_state(app: &AppHandle) {
    let _ = app.emit_to(LABEL, "state-changed", ui_state(app));
}

pub fn emit_spotify(app: &AppHandle, status: &SpotifyStatus) {
    let _ = app.emit_to(LABEL, "spotify-changed", status);
}

pub fn emit_notice(app: &AppHandle, level: &str, message: &str) {
    let _ = app.emit_to(LABEL, "notice", Notice { level: level.into(), message: message.into() });
}

pub fn emit_theme(app: &AppHandle) {
    let effective = theme::effective(&app.state::<AppState>().settings.lock().unwrap());
    let _ = app.emit_to(LABEL, "theme-changed", effective);
}

fn update_settings(app: &AppHandle, change: impl FnOnce(&mut Settings)) {
    let state = app.state::<AppState>();
    let mut settings = state.settings.lock().unwrap();
    change(&mut settings);
    if let Err(e) = settings::save(&state.settings_path, &settings) {
        log::warn!("could not save settings: {e}");
    }
}

/// The panel switched themes (or loaded): remember it and restyle the mini-player if it follows.
pub fn on_panel_theme(app: &AppHandle, raw: PanelTheme) {
    let Some(clean) = theme::sanitize(raw) else { return };
    if app.state::<AppState>().settings.lock().unwrap().panel_theme.as_ref() == Some(&clean) {
        return;
    }
    update_settings(app, |s| s.panel_theme = Some(clean));
    emit_theme(app);
}

static LAST_MOVE: Mutex<Option<(i32, i32, Instant)>> = Mutex::new(None);
static SAVER_RUNNING: AtomicBool = AtomicBool::new(false);

/// Remembers where the user dragged the mini-player; writes settings at most once a second.
pub fn on_moved(app: &AppHandle, position: PhysicalPosition<i32>) {
    *LAST_MOVE.lock().unwrap() = Some((position.x, position.y, Instant::now()));
    if SAVER_RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(1));
        let Some((x, y, at)) = *LAST_MOVE.lock().unwrap() else { break };
        if at.elapsed() < Duration::from_secs(1) {
            continue;
        }
        let state = app.state::<AppState>();
        let mut settings = state.settings.lock().unwrap();
        settings.mini_player.x = Some(x);
        settings.mini_player.y = Some(y);
        if let Err(e) = settings::save(&state.settings_path, &settings) {
            log::warn!("could not save mini-player position: {e}");
        }
        SAVER_RUNNING.store(false, Ordering::SeqCst);
        break;
    });
}

#[tauri::command]
pub fn mini_get_state(app: AppHandle) -> UiState {
    ui_state(&app)
}

/// Async: waiting for the bot's answer must not block the main thread.
#[tauri::command]
pub async fn mini_command(app: AppHandle, cmd: BotCommand) -> Result<(), String> {
    bridge::send_command_await(&app, cmd, COMMAND_TIMEOUT)
}

#[tauri::command]
pub fn mini_open_panel(app: AppHandle) {
    show_main(&app);
}

#[tauri::command]
pub fn mini_hide(app: AppHandle) {
    if let Some(window) = app.get_webview_window(LABEL) {
        let _ = window.hide();
    }
}

/// Runs a blocking Spotify action on a worker thread.
async fn blocking<T: Send + 'static>(work: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    tauri::async_runtime::spawn_blocking(work).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn mini_spotify_now(app: AppHandle) -> SpotifyStatus {
    let own = spotify::own_app_id(&app);
    blocking(move || Ok(spotify::current(&own))).await.unwrap_or_default()
}

#[tauri::command]
pub async fn mini_send_spotify(app: AppHandle) -> Result<String, String> {
    blocking(move || spotify::send_current(&app)).await
}

#[tauri::command]
pub async fn mini_play_spotify_text(app: AppHandle, text: String) -> Result<String, String> {
    blocking(move || spotify::play_link_text(&app, &text)).await
}

#[tauri::command]
pub fn mini_get_prefs(app: AppHandle) -> MiniPrefs {
    let state = app.state::<AppState>();
    let settings = state.settings.lock().unwrap();
    MiniPrefs {
        opacity: settings.opacity(),
        match_panel: settings.mini_player_match_panel,
        theme: settings.mini_player_theme.clone(),
        effective: theme::effective(&settings),
    }
}

#[tauri::command]
pub fn mini_set_opacity(app: AppHandle, value: f64) {
    update_settings(&app, |s| s.mini_player_opacity = settings::clamp_opacity(value));
}

/// An unknown theme id keeps the current pick.
#[tauri::command]
pub fn mini_set_theme(app: AppHandle, match_panel: bool, theme: String) {
    update_settings(&app, |s| {
        s.mini_player_match_panel = match_panel;
        if theme::THEME_IDS.contains(&theme.as_str()) {
            s.mini_player_theme = theme;
        }
    });
    emit_theme(&app);
}

#[tauri::command]
pub async fn mini_search(app: AppHandle, query: String) -> Result<Vec<SearchResult>, String> {
    blocking(move || bridge::send_command_fetch(&app, BotCommand::Search(query), SEARCH_TIMEOUT))
        .await
        .map(|data| lookup::search_results(&data))
}

/// Lyrics for the song playing on the selected server.
#[tauri::command]
pub async fn mini_lyrics(app: AppHandle) -> Result<Lyrics, String> {
    blocking(move || bridge::send_command_fetch(&app, BotCommand::Lyrics, LYRICS_TIMEOUT))
        .await
        .map(|data| lookup::lyrics_from(&data))
}

#[tauri::command]
pub async fn mini_play_link(app: AppHandle, text: String) -> Result<String, String> {
    blocking(move || {
        bridge::send_command_await(&app, lookup::link_command(&text)?, COMMAND_TIMEOUT)?;
        Ok("Playing the link".to_string())
    })
    .await
}

#[tauri::command]
pub async fn mini_play_spotify_clipboard(app: AppHandle) -> Result<String, String> {
    blocking(move || spotify::play_clipboard(&app)).await
}

#[cfg(test)]
mod tests {
    use super::*;

    const SIZE: (u32, u32) = (360, 140);

    #[test]
    fn default_position_is_bottom_right_with_margin() {
        let work_area = Rect { x: 0, y: 0, w: 1920, h: 1040 };
        assert_eq!(default_position(work_area, SIZE), (1920 - 360 - 12, 1040 - 140 - 12));
    }

    #[test]
    fn default_position_respects_monitor_offset() {
        let second = Rect { x: 1920, y: -200, w: 2560, h: 1400 };
        assert_eq!(default_position(second, SIZE), (1920 + 2560 - 360 - 12, -200 + 1400 - 140 - 12));
    }

    #[test]
    fn saved_position_on_screen_is_kept() {
        let monitors = [Rect { x: 0, y: 0, w: 1920, h: 1080 }];
        assert_eq!(resolve_position((Some(100), Some(100)), &monitors, SIZE), (100, 100));
    }

    #[test]
    fn saved_position_off_screen_falls_back_to_default() {
        let monitors = [Rect { x: 0, y: 0, w: 1920, h: 1080 }];
        assert_eq!(resolve_position((Some(3000), Some(100)), &monitors, SIZE), default_position(monitors[0], SIZE));
    }

    #[test]
    fn missing_saved_position_uses_default() {
        let monitors = [Rect { x: 0, y: 0, w: 1920, h: 1080 }];
        assert_eq!(resolve_position((None, Some(100)), &monitors, SIZE), default_position(monitors[0], SIZE));
        assert_eq!(resolve_position((Some(5), Some(5)), &[], SIZE), (5, 5));
    }
}
