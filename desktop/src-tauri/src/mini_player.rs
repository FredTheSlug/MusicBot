//! The always-on-top mini-player window and the commands its bundled UI may call.

use crate::bridge::{self, BotCommand};
use crate::lookup::{self, Lyrics, PlaylistInfo, SearchResult};
use crate::spotify::{self, SpotifyStatus};
use crate::settings::Settings;
use crate::sleep::{self, SleepInfo};
use crate::state::BotState;
use crate::theme::{self, PanelTheme};
use crate::{settings, show_main, AppState};
use serde::Serialize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, LogicalSize, Manager, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindowBuilder};

pub const LABEL: &str = "mini-player";
const WIDTH: f64 = 360.0;
const HEIGHT: f64 = 170.0;
const MARGIN: i32 = 12;
/// Height added above (or below) the player while the menu or lyrics are open.
const EXTRA_HEIGHT: f64 = 250.0;
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

/// Which way the window grew to make room for the menu or lyrics.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Direction {
    Up,
    Down,
}

/// The open window: grown upward when the space above fits, else downward, kept on the monitor.
pub fn expanded_rect(collapsed: Rect, extra: u32, monitor: Rect) -> (Rect, Direction) {
    let h = collapsed.h + extra;
    if collapsed.y - extra as i32 >= monitor.y {
        return (Rect { y: collapsed.y - extra as i32, h, ..collapsed }, Direction::Up);
    }
    let bottom = monitor.y + monitor.h as i32;
    let y = collapsed.y.min(bottom - h as i32).max(monitor.y);
    (Rect { y, h, ..collapsed }, Direction::Down)
}

/// Where the closed player sits, given the open window's position and how far the player was
/// pushed (`offset` = closed y − open y, in logical px so a move to a monitor with a different
/// scale keeps it right): this is what gets saved, never the open window's spot.
pub fn collapsed_origin(window: (i32, i32), offset: f64, scale: f64) -> (i32, i32) {
    (window.0, window.1 + (offset * scale).round() as i32)
}

/// The closed player's size in physical px on a monitor with this scale factor.
pub fn closed_size(scale: f64) -> (u32, u32) {
    ((WIDTH * scale).round() as u32, (HEIGHT * scale).round() as u32)
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
        .transparent(true)
        .shadow(false)
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
        hide(app);
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

fn sleep_info(app: &AppHandle) -> SleepInfo {
    sleep::info(&app.state::<AppState>().sleep.lock().unwrap(), Instant::now(), epoch_ms())
}

pub fn emit_sleep(app: &AppHandle) {
    let _ = app.emit_to(LABEL, "sleep-changed", sleep_info(app));
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

#[derive(Debug, Clone, Copy)]
struct Open {
    direction: Direction,
    /// Logical px between the closed player's top and the open window's top.
    offset: f64,
}

/// Set while the menu or lyrics are open.
static OPEN: Mutex<Option<Open>> = Mutex::new(None);

fn work_area(monitor: &tauri::Monitor) -> Rect {
    let area = monitor.work_area();
    Rect { x: area.position.x, y: area.position.y, w: area.size.width, h: area.size.height }
}

fn expand(app: &AppHandle) -> Result<Direction, String> {
    if let Some(open) = *OPEN.lock().unwrap() {
        return Ok(open.direction);
    }
    let window = app.get_webview_window(LABEL).ok_or("The mini-player isn't open")?;
    let position = window.outer_position().map_err(|e| e.to_string())?;
    let scale = window.scale_factor().map_err(|e| e.to_string())?;
    let monitor = window
        .current_monitor()
        .ok()
        .flatten()
        .or_else(|| window.primary_monitor().ok().flatten())
        .ok_or("No monitor found")?;
    // From the fixed logical size, not outer_size(), so a window mis-sized by a DPI change heals.
    let (w, h) = closed_size(scale);
    let collapsed = Rect { x: position.x, y: position.y, w, h };
    let extra = (EXTRA_HEIGHT * scale).round() as u32;
    let (rect, direction) = expanded_rect(collapsed, extra, work_area(&monitor));
    // Recorded before moving so the Moved event saves the closed spot.
    *OPEN.lock().unwrap() = Some(Open { direction, offset: f64::from(collapsed.y - rect.y) / scale });
    let _ = window.set_position(PhysicalPosition::new(rect.x, rect.y));
    let _ = window.set_size(PhysicalSize::new(rect.w, rect.h));
    Ok(direction)
}

/// Closes the menu/lyrics space; the UI hears `panel-reset` so it can drop its panel too.
pub fn collapse(app: &AppHandle) {
    let Some(open) = OPEN.lock().unwrap().take() else { return };
    let Some(window) = app.get_webview_window(LABEL) else { return };
    let Ok(current) = window.outer_position() else { return };
    let scale = window.scale_factor().unwrap_or(1.0);
    let (x, y) = collapsed_origin((current.x, current.y), open.offset, scale);
    let _ = window.set_size(LogicalSize::new(WIDTH, HEIGHT));
    let _ = window.set_position(PhysicalPosition::new(x, y));
    let _ = app.emit_to(LABEL, "panel-reset", ());
}

/// Hides the mini-player; it always comes back closed.
pub fn hide(app: &AppHandle) {
    collapse(app);
    if let Some(window) = app.get_webview_window(LABEL) {
        let _ = window.hide();
    }
}

static LAST_MOVE: Mutex<Option<(i32, i32, Instant)>> = Mutex::new(None);
static SAVER_RUNNING: AtomicBool = AtomicBool::new(false);

/// Remembers where the user dragged the mini-player; writes settings at most once a second.
pub fn on_moved(app: &AppHandle, position: PhysicalPosition<i32>) {
    let offset = OPEN.lock().unwrap().map_or(0.0, |open| open.offset);
    let scale = app.get_webview_window(LABEL).and_then(|w| w.scale_factor().ok()).unwrap_or(1.0);
    let (x, y) = collapsed_origin((position.x, position.y), offset, scale);
    *LAST_MOVE.lock().unwrap() = Some((x, y, Instant::now()));
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
    hide(&app);
}

/// `panel` is "menu", "lyrics" or "queue" (grow; returns which way) or "none" (shrink back).
#[tauri::command]
pub fn mini_set_panel(app: AppHandle, panel: String) -> Result<Option<Direction>, String> {
    match panel.as_str() {
        "none" => {
            collapse(&app);
            Ok(None)
        }
        "menu" | "lyrics" | "queue" => expand(&app).map(Some),
        _ => Err(format!("Unknown panel: {panel}")),
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

/// `choice`: "off", "15", "30", "60" (minutes) or "end" (the song playing now).
#[tauri::command]
pub fn mini_set_sleep(app: AppHandle, choice: String) -> Result<SleepInfo, String> {
    let playing = sleep::playing_key(&app);
    let mode = sleep::parse_choice(&choice, Instant::now(), playing.as_deref()).ok_or("Unknown sleep timer choice")?;
    *app.state::<AppState>().sleep.lock().unwrap() = mode;
    emit_sleep(&app);
    Ok(sleep_info(&app))
}

#[tauri::command]
pub fn mini_get_sleep(app: AppHandle) -> SleepInfo {
    sleep_info(&app)
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

/// Lyrics for the song playing on the selected server, asked for by name.
#[tauri::command]
pub async fn mini_lyrics(app: AppHandle) -> Result<Lyrics, String> {
    let cmd = {
        let app_state = app.state::<AppState>();
        let store = app_state.store.lock().unwrap();
        lookup::lyrics_command(store.current().and_then(|s| s.track.as_ref()))?
    };
    blocking(move || bridge::send_command_fetch(&app, cmd, LYRICS_TIMEOUT))
        .await
        .map(|data| lookup::lyrics_from(&data))
}

async fn fetch_tracks(app: AppHandle, cmd: BotCommand) -> Result<Vec<SearchResult>, String> {
    blocking(move || bridge::send_command_fetch(&app, cmd, SEARCH_TIMEOUT))
        .await
        .map(|data| lookup::track_list(&data))
}

/// Recently played on the selected server.
#[tauri::command]
pub async fn mini_history(app: AppHandle) -> Result<Vec<SearchResult>, String> {
    fetch_tracks(app, BotCommand::History).await
}

/// Most played on the selected server.
#[tauri::command]
pub async fn mini_frequent(app: AppHandle) -> Result<Vec<SearchResult>, String> {
    fetch_tracks(app, BotCommand::Frequent).await
}

#[tauri::command]
pub async fn mini_playlists(app: AppHandle) -> Result<Vec<PlaylistInfo>, String> {
    blocking(move || bridge::send_command_fetch(&app, BotCommand::Playlists, SEARCH_TIMEOUT))
        .await
        .map(|data| lookup::playlists(&data))
}

#[tauri::command]
pub async fn mini_play_playlist(app: AppHandle, name: String, folder: Option<String>) -> Result<String, String> {
    blocking(move || {
        let message = format!("Playing “{name}”");
        bridge::send_command_await(&app, BotCommand::PlayPlaylist { name, folder }, COMMAND_TIMEOUT)?;
        Ok(message)
    })
    .await
}

#[tauri::command]
pub async fn mini_play_link(app: AppHandle, text: String) -> Result<String, String> {
    blocking(move || {
        bridge::send_command_await(&app, lookup::link_command(&text)?, COMMAND_TIMEOUT)?;
        Ok("Playing the link".to_string())
    })
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCREEN: Rect = Rect { x: 0, y: 0, w: 1920, h: 1040 };

    #[test]
    fn grows_up_when_room_above() {
        let closed = Rect { x: 1548, y: 858, w: 360, h: 170 };
        assert_eq!(expanded_rect(closed, 250, SCREEN), (Rect { x: 1548, y: 608, w: 360, h: 420 }, Direction::Up));
    }

    #[test]
    fn grows_down_at_top_edge() {
        let closed = Rect { x: 100, y: 20, w: 360, h: 170 };
        assert_eq!(expanded_rect(closed, 250, SCREEN), (Rect { x: 100, y: 20, w: 360, h: 420 }, Direction::Down));
    }

    #[test]
    fn grows_down_on_monitor_above_primary() {
        let above = Rect { x: -1920, y: -1080, w: 1920, h: 1040 };
        let closed = Rect { x: -400, y: -1070, w: 360, h: 170 };
        assert_eq!(expanded_rect(closed, 250, above), (Rect { x: -400, y: -1070, w: 360, h: 420 }, Direction::Down));
    }

    #[test]
    fn clamps_when_neither_fits() {
        let short = Rect { x: 0, y: 0, w: 1280, h: 400 };
        let closed = Rect { x: 0, y: 100, w: 360, h: 170 };
        assert_eq!(expanded_rect(closed, 250, short), (Rect { x: 0, y: 0, w: 360, h: 420 }, Direction::Down));
    }

    #[test]
    fn saved_position_is_the_closed_one() {
        assert_eq!(collapsed_origin((1548, 608), 250.0, 1.0), (1548, 858));
        assert_eq!(collapsed_origin((5, 5), 0.0, 1.0), (5, 5));
        // Dragged onto a 150% monitor while open: the 250 px gap is 375 physical px there.
        assert_eq!(collapsed_origin((100, 100), 250.0, 1.5), (100, 475));
    }

    #[test]
    fn closed_size_follows_the_monitor_scale() {
        assert_eq!(closed_size(1.0), (360, 170));
        assert_eq!(closed_size(1.5), (540, 255));
        assert_eq!(closed_size(1.25), (450, 213));
    }

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
