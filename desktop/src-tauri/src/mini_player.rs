//! The always-on-top mini-player window and the commands its bundled UI may call.

use crate::bridge::{self, BotCommand, PlaylistSong};
use crate::lookup::{self, Likes, Lyrics, PlaylistEntry, PlaylistInfo, SearchResult};
use crate::spotify::{self, SpotifyStatus};
use crate::hotkeys;
use crate::settings::Settings;
use crate::sleep::{self, SleepInfo};
use crate::state::{BotState, Change};
use crate::theme::{self, CustomMini, PanelTheme, ThemeMenu, UiTheme};
use crate::{settings, show_main, AppState};
use serde::Serialize;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, LogicalSize, Manager, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_clipboard_manager::ClipboardExt;

pub const LABEL: &str = "mini-player";
const WIDTH: f64 = 360.0;
const HEIGHT: f64 = 170.0;
/// The slim bar of compact mode.
const COMPACT_HEIGHT: f64 = 64.0;
const MARGIN: i32 = 12;
/// Height added above (or below) the player while the menu or lyrics are open.
const EXTRA_HEIGHT: f64 = 250.0;
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const SEARCH_TIMEOUT: Duration = Duration::from_secs(10);
const LYRICS_TIMEOUT: Duration = Duration::from_secs(15);
const THEMES_TIMEOUT: Duration = Duration::from_secs(10);
const LIKES_TIMEOUT: Duration = Duration::from_secs(10);

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

pub fn closed_height(compact: bool) -> f64 {
    if compact { COMPACT_HEIGHT } else { HEIGHT }
}

/// The closed player's size in physical px on a monitor with this scale factor.
pub fn closed_size(scale: f64, compact: bool) -> (u32, u32) {
    ((WIDTH * scale).round() as u32, (closed_height(compact) * scale).round() as u32)
}

/// Where the player goes when its height changes (compact ↔ full): the bottom edge stays put,
/// so a player parked above the taskbar stays there, and it never leaves the monitor.
pub fn resized_origin(origin: (i32, i32), old_h: u32, new_h: u32, monitor: Rect) -> (i32, i32) {
    let y = origin.1 + old_h as i32 - new_h as i32;
    let lowest = monitor.y + monitor.h as i32 - new_h as i32;
    (origin.0, y.min(lowest).max(monitor.y))
}

/// The new origin for switching to `on`, or None when the player is already in that mode.
pub fn compact_origin(current: bool, on: bool, origin: (i32, i32), scale: f64, monitor: Rect) -> Option<(i32, i32)> {
    if current == on {
        return None;
    }
    let (_, old_h) = closed_size(scale, current);
    let (_, new_h) = closed_size(scale, on);
    Some(resized_origin(origin, old_h, new_h, monitor))
}

fn is_compact(app: &AppHandle) -> bool {
    app.state::<AppState>().settings.lock().unwrap().mini_player_compact
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
    pub compact: bool,
    pub opacity: f64,
    pub match_panel: bool,
    /// What the theme menu selects (see `theme::menu_selection`).
    pub theme: String,
    /// The custom themes the theme menu offers.
    pub custom_themes: Vec<CustomMini>,
    pub effective: UiTheme,
    pub hide_in_fullscreen: bool,
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
    let height = closed_height(is_compact(app));
    WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("mini-player/index.html".into()))
        .title("Clout Kings Mini-player")
        .inner_size(WIDTH, height)
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
    crate::fullscreen::forget_auto_hide();
    // The fullscreen hide bypasses tao, which then still thinks the window is shown; hiding first
    // brings its record back in line so the show below actually happens.
    let _ = window.hide();
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
    refresh_themes_in_background(app);
    refresh_likes_in_background(app);
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

/// The custom themes with a mini-player version, as last fetched (empty until the first answer).
fn custom_themes(app: &AppHandle) -> Vec<CustomMini> {
    app.state::<AppState>().customs.lock().unwrap().clone()
}

/// What the mini-player wears now, for its UI.
fn ui_theme(app: &AppHandle) -> UiTheme {
    let customs = custom_themes(app);
    let settings = app.state::<AppState>().settings.lock().unwrap().clone();
    theme::effective(&settings, &customs).into_ui(settings.server_origin().as_deref())
}

pub fn emit_theme(app: &AppHandle) {
    let _ = app.emit_to(LABEL, "theme-changed", ui_theme(app));
}

fn theme_menu(app: &AppHandle) -> ThemeMenu {
    let customs = custom_themes(app);
    theme::theme_menu(&app.state::<AppState>().settings.lock().unwrap(), &customs)
}

fn emit_theme_menu(app: &AppHandle) {
    let _ = app.emit_to(LABEL, "themes-changed", theme_menu(app));
}

static THEMES_ASKED: AtomicU64 = AtomicU64::new(0);
static THEMES_SHOWN: AtomicU64 = AtomicU64::new(0);
/// Set once the bot has answered for the first time this session (an empty list counts): until then the list is
/// asked for again whenever the panel reports its theme (see `theme::needs_refresh`).
static THEMES_LOADED: AtomicBool = AtomicBool::new(false);

/// Asks the bot for its custom themes and, if the list changed, restyles the mini-player and its theme menu.
/// Blocks while it waits for the panel's answer: call it from a worker thread or an async command, never the main thread.
pub fn refresh_themes(app: &AppHandle) -> Result<ThemeMenu, String> {
    let ticket = THEMES_ASKED.fetch_add(1, Ordering::SeqCst) + 1;
    let data = bridge::send_command_fetch(app, BotCommand::Themes, THEMES_TIMEOUT)?;
    THEMES_LOADED.store(true, Ordering::SeqCst);
    let fresh = theme::custom_minis(&data);
    // Two asks can be out at once; an older answer arriving late must not undo a newer one.
    if THEMES_SHOWN.fetch_max(ticket, Ordering::SeqCst) < ticket {
        let changed = {
            let state = app.state::<AppState>();
            let mut customs = state.customs.lock().unwrap();
            let changed = *customs != fresh;
            *customs = fresh;
            changed
        };
        if changed {
            emit_theme_menu(app);
            emit_theme(app);
        }
    }
    Ok(theme_menu(app))
}

/// `refresh_themes` without waiting, for the places that run on the main or the bridge's thread.
fn refresh_themes_in_background(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        if let Err(e) = refresh_themes(&app) {
            log::info!("could not refresh the custom themes: {e}");
        }
    });
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
    let changed = app.state::<AppState>().settings.lock().unwrap().panel_theme.as_ref() != Some(&clean);
    if changed {
        update_settings(app, |s| s.panel_theme = Some(clean));
        emit_theme(app);
    }
    // A changed theme may have been edited, or made, or taken away. And the report of an unchanged one still means the
    // panel is up now: a first ask that came before it (the mini-player shown at startup) got no answer.
    if theme::needs_refresh(changed, THEMES_LOADED.load(Ordering::SeqCst)) {
        refresh_themes_in_background(app);
    }
}

/// The bot announced a change to the custom themes (the panel's bridge heard it). The panel's own theme may be the
/// same as before while its mini-player version is not, so this asks for the list whatever the panel reported.
pub fn on_themes_changed(app: &AppHandle) {
    refresh_themes_in_background(app);
}

// ---- Likes: the ♥s and "My likes" ----

/// The like state the mini-player shows. `version` numbers the ask whose answer this is, so the UI can drop an older
/// copy: an event and a command's answer can cross on the way.
#[derive(Debug, Clone, PartialEq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LikesState {
    pub version: u64,
    /// None while unknown: no answer yet, signed out, or a bot from before likes (the UI then hides the ♥s).
    pub likes: Option<Likes>,
}

impl LikesState {
    /// Takes `likes`, the answer to the ask numbered `ticket`, unless a later ask's answer is already here (two can be
    /// out at once, and an older one answering late must not undo a newer one). True when what shows changed.
    pub fn offer(&mut self, ticket: u64, likes: Option<Likes>) -> bool {
        if ticket <= self.version {
            return false;
        }
        self.version = ticket;
        let changed = self.likes != likes;
        self.likes = likes;
        changed
    }
}

/// What a state report means for the like state.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum LikesAfter {
    Keep,
    /// Ask the bot again: the song changed, or the panel (re)connected signed in.
    Refresh,
    /// Signed out: these likes are not the next person's.
    Forget,
}

pub fn likes_after(changes: &[Change]) -> LikesAfter {
    if changes.iter().any(|c| matches!(c, Change::ConnectionChanged { signed_in: false, .. })) {
        return LikesAfter::Forget;
    }
    let refresh = changes
        .iter()
        .any(|c| matches!(c, Change::TrackChanged { .. } | Change::ConnectionChanged { connected: true, signed_in: true }));
    if refresh { LikesAfter::Refresh } else { LikesAfter::Keep }
}

static LIKES_ASKED: AtomicU64 = AtomicU64::new(0);

fn likes_state(app: &AppHandle) -> LikesState {
    app.state::<AppState>().likes.lock().unwrap().clone()
}

/// Keeps the answer to ask `ticket` (see `LikesState::offer`) and tells the mini-player when what it shows changed.
fn offer_likes(app: &AppHandle, ticket: u64, likes: Option<Likes>) -> LikesState {
    let (changed, now) = {
        let state = app.state::<AppState>();
        let mut current = state.likes.lock().unwrap();
        (current.offer(ticket, likes), current.clone())
    };
    if changed {
        let _ = app.emit_to(LABEL, "likes-changed", &now);
    }
    now
}

/// Asks the bot for the signed-in person's likes and keeps them. Every call asks: the answer to an ask made after a
/// change always includes it, and only an older ask's late answer is dropped.
/// Blocks while it waits for the panel's answer: call it from a worker thread or an async command, never the main thread.
pub fn refresh_likes(app: &AppHandle) -> Result<LikesState, String> {
    let ticket = LIKES_ASKED.fetch_add(1, Ordering::SeqCst) + 1;
    let data = bridge::send_command_fetch(app, BotCommand::LikesMine, LIKES_TIMEOUT)?;
    let likes = lookup::likes(&data).ok_or_else(|| "The bot sent no likes".to_string())?;
    Ok(offer_likes(app, ticket, Some(likes)))
}

fn refresh_likes_in_background(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        if let Err(e) = refresh_likes(&app) {
            log::info!("could not refresh the likes: {e}");
        }
    });
}

/// The bot said the signed-in person's likes changed (the panel's bridge heard it).
pub fn on_likes_changed(app: &AppHandle) {
    refresh_likes_in_background(app);
}

/// Keeps the like state in step with the player: asked again when the song changes or on signing in, dropped on signing out.
pub fn on_state_changes(app: &AppHandle, changes: &[Change]) {
    match likes_after(changes) {
        LikesAfter::Keep => {}
        LikesAfter::Refresh => refresh_likes_in_background(app),
        LikesAfter::Forget => {
            let ticket = LIKES_ASKED.fetch_add(1, Ordering::SeqCst) + 1;
            offer_likes(app, ticket, None);
        }
    }
}

/// Sends a ♥ change, then asks for the list again whatever the bot answered, so the ♥s show what it holds. On a yes the
/// fresh list comes back with the answer; a no is the bot's own words, and the fresh list follows as `likes-changed`.
fn change_like(app: &AppHandle, cmd: BotCommand) -> Result<LikesState, String> {
    if let Err(e) = bridge::send_command_await(app, cmd, COMMAND_TIMEOUT) {
        refresh_likes_in_background(app);
        return Err(e);
    }
    refresh_likes(app).or_else(|e| {
        log::info!("could not refresh the likes after a change: {e}");
        Ok(likes_state(app))
    })
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
    let (w, h) = closed_size(scale, is_compact(app));
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
    let _ = window.set_size(LogicalSize::new(WIDTH, closed_height(is_compact(app))));
    let _ = window.set_position(PhysicalPosition::new(x, y));
    let _ = app.emit_to(LABEL, "panel-reset", ());
}

/// Hides the mini-player at the user's request; it always comes back closed.
pub fn hide(app: &AppHandle) {
    crate::fullscreen::forget_auto_hide();
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

/// Shows the panel window and asks the page to open its "What's new" history. Showing the window is
/// the part that matters: a page that isn't loaded yet has nothing to ask, which is not an error.
#[tauri::command]
pub fn mini_show_whats_new(app: AppHandle) {
    show_main(&app);
    if let Err(e) = bridge::send_command(&app, BotCommand::ShowWhatsNew) {
        log::info!("could not ask the panel for its patch notes: {e}");
    }
}

#[tauri::command]
pub fn mini_hide(app: AppHandle) {
    hide(&app);
}

/// `panel` is "menu", "lyrics", "queue" or "picker" (grow; returns which way) or "none" (shrink back).
#[tauri::command]
pub fn mini_set_panel(app: AppHandle, panel: String) -> Result<Option<Direction>, String> {
    match panel.as_str() {
        "none" => {
            collapse(&app);
            Ok(None)
        }
        "menu" | "lyrics" | "queue" | "picker" => expand(&app).map(Some),
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
    let customs = custom_themes(&app);
    let settings = app.state::<AppState>().settings.lock().unwrap().clone();
    let menu = theme::theme_menu(&settings, &customs);
    MiniPrefs {
        compact: settings.mini_player_compact,
        opacity: settings.opacity(),
        match_panel: settings.mini_player_match_panel,
        theme: menu.theme,
        custom_themes: menu.custom_themes,
        effective: theme::effective(&settings, &customs).into_ui(settings.server_origin().as_deref()),
        hide_in_fullscreen: settings.hide_in_fullscreen,
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct CompactChanged {
    pub compact: bool,
}

/// Switches between the full player and the slim bar, keeping the bottom edge in place.
#[tauri::command]
pub fn mini_set_compact(app: AppHandle, on: bool) -> Result<(), String> {
    let current = is_compact(&app);
    if current == on {
        // A double click or both switches at once: nothing to move, just keep the UI in step.
        let _ = app.emit_to(LABEL, "compact-changed", CompactChanged { compact: on });
        return Ok(());
    }
    collapse(&app);
    update_settings(&app, |s| s.mini_player_compact = on);
    let window = app.get_webview_window(LABEL).ok_or("The mini-player isn't open")?;
    let position = window.outer_position().map_err(|e| e.to_string())?;
    let scale = window.scale_factor().map_err(|e| e.to_string())?;
    let monitor = window
        .current_monitor()
        .ok()
        .flatten()
        .or_else(|| window.primary_monitor().ok().flatten())
        .ok_or("No monitor found")?;
    let (x, y) = compact_origin(current, on, (position.x, position.y), scale, work_area(&monitor)).unwrap_or((position.x, position.y));
    let _ = window.set_position(PhysicalPosition::new(x, y));
    let _ = window.set_size(LogicalSize::new(WIDTH, closed_height(on)));
    let _ = app.emit_to(LABEL, "compact-changed", CompactChanged { compact: on });
    Ok(())
}

/// While the user records a new combination, our own registrations would swallow it.
#[tauri::command]
pub fn mini_hotkeys_suspend(app: AppHandle, suspend: bool) {
    hotkeys::suspend(&app, suspend);
}

#[tauri::command]
pub fn mini_get_hotkeys(app: AppHandle) -> hotkeys::HotkeysView {
    hotkeys::view(&app)
}

#[tauri::command]
pub fn mini_set_hotkeys_enabled(app: AppHandle, on: bool) -> hotkeys::HotkeysView {
    update_settings(&app, |s| s.hotkeys.enabled = on);
    hotkeys::apply(&app);
    hotkeys::view(&app)
}

/// Saves a new combination for `action`; one Windows refuses (another app owns it) is put back.
#[tauri::command]
pub fn mini_set_hotkey(app: AppHandle, action: hotkeys::HotkeyAction, accel: String) -> Result<hotkeys::HotkeysView, String> {
    let current = app.state::<AppState>().settings.lock().unwrap().hotkeys.clone();
    let accel = hotkeys::check_binding(&current, action, &accel)?;
    update_settings(&app, |s| {
        s.hotkeys.bindings.insert(action, accel.clone());
    });
    if hotkeys::apply(&app).contains(&action) {
        update_settings(&app, |s| s.hotkeys = current);
        hotkeys::apply(&app);
        return Err(format!("{accel} is taken by another app"));
    }
    Ok(hotkeys::view(&app))
}

#[tauri::command]
pub fn mini_set_hide_in_fullscreen(app: AppHandle, on: bool) {
    update_settings(&app, |s| s.hide_in_fullscreen = on);
}

#[tauri::command]
pub fn mini_set_opacity(app: AppHandle, value: f64) {
    update_settings(&app, |s| s.mini_player_opacity = settings::clamp_opacity(value));
}

/// `theme` is what the person picked in the menu: a built-in id or `custom:<id>` (anything else keeps the current
/// pick), or None when only the match switch was flipped (the stored pick is left exactly as it is).
#[tauri::command]
pub fn mini_set_theme(app: AppHandle, match_panel: bool, theme: Option<String>) {
    let customs = custom_themes(&app);
    update_settings(&app, |s| theme::choose(s, match_panel, theme.as_deref(), &customs));
    emit_theme(&app);
}

/// Asks the bot for its custom themes again (the settings are opening); what changed also arrives as events.
/// Async: waiting for the panel's answer must not block the main thread.
#[tauri::command]
pub async fn mini_refresh_themes(app: AppHandle) -> Result<ThemeMenu, String> {
    blocking(move || refresh_themes(&app)).await
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

/// The playlists with their real song counts (and which are still being converted by the bot).
#[tauri::command]
pub async fn mini_playlists_v2(app: AppHandle) -> Result<Vec<PlaylistEntry>, String> {
    blocking(move || match bridge::send_command_fetch(&app, BotCommand::PlaylistsTreeV2, SEARCH_TIMEOUT) {
        Ok(data) => Ok(lookup::playlists_v2(&data)),
        // A bot that isn't updated yet: its old tree still lists them (and they still play).
        Err(e) if lookup::is_missing_route(&e) => bridge::send_command_fetch(&app, BotCommand::Playlists, SEARCH_TIMEOUT)
            .map(|data| lookup::playlists(&data).into_iter().map(PlaylistEntry::from).collect()),
        Err(e) => Err(e),
    })
    .await
}

/// Adds the song the UI names (it holds the one the user pointed at, which may not be playing any more).
#[tauri::command]
pub async fn mini_add_to_playlist(app: AppHandle, name: String, folder: Option<String>, song: PlaylistSong) -> Result<String, String> {
    let cmd = lookup::add_command(name.clone(), folder, song)?;
    blocking(move || {
        bridge::send_command_await(&app, cmd, COMMAND_TIMEOUT)?;
        Ok(format!("Added to “{name}”"))
    })
    .await
}

#[tauri::command]
pub async fn mini_create_playlist(app: AppHandle, name: String, folder: Option<String>) -> Result<(), String> {
    let cmd = lookup::create_command(name, folder)?;
    blocking(move || bridge::send_command_await(&app, cmd, COMMAND_TIMEOUT)).await
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

/// The like state as last fetched (unknown until the bot first answers); what changes after arrives as `likes-changed`.
#[tauri::command]
pub fn mini_likes(app: AppHandle) -> LikesState {
    likes_state(&app)
}

/// ♥ the song the UI names (the one the user clicked, which may not be playing any more); answers the fresh list.
#[tauri::command]
pub async fn mini_like(app: AppHandle, song: PlaylistSong) -> Result<LikesState, String> {
    let cmd = lookup::like_command(song)?;
    blocking(move || change_like(&app, cmd)).await
}

#[tauri::command]
pub async fn mini_unlike(app: AppHandle, uri: String) -> Result<LikesState, String> {
    let cmd = lookup::unlike_command(uri)?;
    blocking(move || change_like(&app, cmd)).await
}

/// Plays the signed-in person's own likes on the selected server.
#[tauri::command]
pub async fn mini_play_likes(app: AppHandle) -> Result<String, String> {
    let cmd = {
        let app_state = app.state::<AppState>();
        let store = app_state.store.lock().unwrap();
        lookup::play_likes_command(store.current().and_then(|s| s.me.as_ref()))?
    };
    blocking(move || {
        bridge::send_command_await(&app, cmd, COMMAND_TIMEOUT)?;
        Ok("Playing your likes".to_string())
    })
    .await
}

const PLAYING_THE_LINK: &str = "Playing the link";

#[tauri::command]
pub async fn mini_play_link(app: AppHandle, text: String) -> Result<String, String> {
    blocking(move || {
        bridge::send_command_await(&app, lookup::link_command(&text)?, COMMAND_TIMEOUT)?;
        Ok(PLAYING_THE_LINK.to_string())
    })
    .await
}

// ---- Play link from clipboard (the bottom row's button and the tray) ----

/// What a link on the clipboard plays: exactly what the search box sends for a pasted link.
fn clipboard_command(text: &str) -> Result<BotCommand, String> {
    lookup::link_command(text).map_err(|_| "The clipboard doesn't have a link to play".to_string())
}

fn link_kind(text: &str) -> &'static str {
    match lookup::link_command(text) {
        Ok(BotCommand::PlaySpotifyLink(_)) => "spotify link",
        Ok(BotCommand::PlayQuery(url)) => {
            let host = url::Url::parse(&url).ok().and_then(|u| u.host_str().map(str::to_ascii_lowercase)).unwrap_or_default();
            let on = |domain: &str| host == domain || host.ends_with(&format!(".{domain}"));
            if on("youtube.com") || on("youtu.be") {
                "youtube link"
            } else if on("soundcloud.com") {
                "soundcloud link"
            } else {
                "other link"
            }
        }
        Ok(_) => "other link",
        Err(_) => "not a link",
    }
}

/// What gets logged about the clipboard: the kind of link and its length, never the text, which may be anything.
fn clipboard_log(text: &str) -> String {
    format!("play from clipboard: {} ({} chars)", link_kind(text), text.chars().count())
}

/// Plays the link on the clipboard. Blocking: call off the main thread.
pub fn play_clipboard(app: &AppHandle) -> Result<String, String> {
    // No text at all (an empty clipboard, a picture) is the same as text without a link.
    let text = app.clipboard().read_text().unwrap_or_default();
    log::info!("{}", clipboard_log(&text));
    bridge::send_command_await(app, clipboard_command(&text)?, COMMAND_TIMEOUT)?;
    Ok(PLAYING_THE_LINK.to_string())
}

#[tauri::command]
pub async fn mini_play_clipboard(app: AppHandle) -> Result<String, String> {
    blocking(move || play_clipboard(&app)).await
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
        assert_eq!(closed_size(1.0, false), (360, 170));
        assert_eq!(closed_size(1.5, false), (540, 255));
        assert_eq!(closed_size(1.25, false), (450, 213));
    }

    #[test]
    fn compact_sizes() {
        assert_eq!(closed_size(1.0, true), (360, 64));
        assert_eq!(closed_size(1.5, true), (540, 96));
    }

    #[test]
    fn compact_switch_to_the_current_mode_does_nothing() {
        assert_eq!(compact_origin(true, true, (100, 964), 1.0, SCREEN), None);
        assert_eq!(compact_origin(false, false, (100, 858), 1.0, SCREEN), None);
        assert_eq!(compact_origin(false, true, (100, 858), 1.0, SCREEN), Some((100, 964)));
        assert_eq!(compact_origin(true, false, (100, 964), 1.0, SCREEN), Some((100, 858)));
    }

    #[test]
    fn resize_keeps_bottom_edge() {
        assert_eq!(resized_origin((100, 858), 170, 64, SCREEN), (100, 964));
        assert_eq!(resized_origin((100, 964), 64, 170, SCREEN), (100, 858));
        // Growing back near the top of the screen stays on it.
        assert_eq!(resized_origin((100, 40), 64, 170, SCREEN), (100, 0));
    }

    fn liked(uris: &[&str]) -> Option<Likes> {
        Some(Likes { uris: uris.iter().map(|u| u.to_string()).collect(), count: uris.len() as u32 })
    }

    #[test]
    fn the_like_state_keeps_the_answer_to_the_newest_ask() {
        let mut state = LikesState::default();
        assert_eq!(state, LikesState { version: 0, likes: None }, "unknown until the bot first answers");
        assert!(state.offer(2, liked(&["a"])), "the first list changes what shows");
        assert!(!state.offer(1, liked(&[])), "an older ask answering late changes nothing");
        assert_eq!(state, LikesState { version: 2, likes: liked(&["a"]) });
        assert!(!state.offer(3, liked(&["a"])), "the same list again: nothing to show");
        assert_eq!(state.version, 3, "but it is the newest answer now");
        assert!(state.offer(4, liked(&["b", "a"])));
        assert!(state.offer(5, None), "forgotten on signing out");
        assert!(!state.offer(5, liked(&["a"])), "one ask, one answer");
        assert_eq!(state, LikesState { version: 5, likes: None });
    }

    #[test]
    fn the_like_state_reaches_the_ui_numbered() {
        let state = LikesState { version: 7, likes: liked(&["u"]) };
        assert_eq!(serde_json::to_value(&state).unwrap(), serde_json::json!({"version": 7, "likes": {"uris": ["u"], "count": 1}}));
        assert_eq!(serde_json::to_value(LikesState::default()).unwrap(), serde_json::json!({"version": 0, "likes": null}));
    }

    #[test]
    fn likes_are_asked_for_again_when_the_song_changes_or_you_sign_in_and_forgotten_when_you_sign_out() {
        use crate::state::{Change, QueueHead, Track};
        let song = || Change::TrackChanged { track: Track::default(), initial: false };
        assert_eq!(likes_after(&[song()]), LikesAfter::Refresh);
        assert_eq!(likes_after(&[Change::TrackChanged { track: Track::default(), initial: true }]), LikesAfter::Refresh);
        assert_eq!(likes_after(&[Change::ConnectionChanged { connected: true, signed_in: true }]), LikesAfter::Refresh);
        assert_eq!(likes_after(&[Change::ConnectionChanged { connected: true, signed_in: false }, Change::Cleared]), LikesAfter::Forget);
        assert_eq!(likes_after(&[Change::ConnectionChanged { connected: false, signed_in: false }]), LikesAfter::Forget, "rejected by a restarted bot");
        assert_eq!(likes_after(&[Change::ConnectionChanged { connected: false, signed_in: false }, song()]), LikesAfter::Forget);
        assert_eq!(likes_after(&[Change::ConnectionChanged { connected: false, signed_in: true }]), LikesAfter::Keep, "a dropped connection: same person");
        for quiet in [Change::PlaybackChanged { paused: true }, Change::Cleared, Change::QueueHeadChanged(Some(QueueHead::default()))] {
            assert_eq!(likes_after(&[quiet.clone()]), LikesAfter::Keep, "{quiet:?}");
        }
        assert_eq!(likes_after(&[]), LikesAfter::Keep);
    }

    // ---- Play link from clipboard ----

    #[test]
    fn the_clipboard_is_played_the_way_the_search_box_plays_a_link() {
        let links = [
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x",
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "  https://youtu.be/abc\r\n",
            "https://soundcloud.com/artist/song",
            "watch https://example.com/live.m3u8 now",
        ];
        for text in links {
            assert_eq!(clipboard_command(text), lookup::link_command(text), "{text:?}");
        }
        assert_eq!(
            clipboard_command("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"),
            Ok(BotCommand::PlaySpotifyLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M".into()))
        );
        assert_eq!(clipboard_command("  https://youtu.be/abc\r\n"), Ok(BotCommand::PlayQuery("https://youtu.be/abc".into())));
    }

    #[test]
    fn a_clipboard_without_a_link_says_so() {
        for text in ["", "   \n", "daft punk", "httpx://nope", "spotify:user:x", "www.youtube.com/watch?v=x"] {
            assert_eq!(clipboard_command(text), Err("The clipboard doesn't have a link to play".to_string()), "{text:?}");
        }
    }

    #[test]
    fn the_clipboard_log_names_the_kind_of_link_and_never_the_text() {
        let cases = [
            ("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=SECRET", "spotify link"),
            ("spotify:album:1ATL5GLyefJaxhQzSPVrLX", "spotify link"),
            ("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtube link"),
            ("https://youtu.be/dQw4w9WgXcQ?t=SECRET", "youtube link"),
            ("https://music.youtube.com/watch?v=dQw4w9WgXcQ", "youtube link"),
            ("https://soundcloud.com/artist/private-track/s-SECRET", "soundcloud link"),
            ("https://on.soundcloud.com/AbCdE", "soundcloud link"),
            ("https://example.com/song.mp3?token=SECRET", "other link"),
            ("https://youtube.com.evil.io/watch?v=SECRET", "other link"),
            ("https://notsoundcloud.com/SECRET", "other link"),
            ("my bank password is hunter2", "not a link"),
            ("", "not a link"),
        ];
        for (text, kind) in cases {
            let line = clipboard_log(text);
            assert!(line.contains(&format!("{kind} ({} chars)", text.chars().count())), "{text:?}: {line}");
            for secret in ["SECRET", "hunter2", "dQw4w9WgXcQ", "4uLU6hMCjMI75M1A2tKUQC", "1ATL5GLyefJaxhQzSPVrLX", "AbCdE", "example.com", "evil", "http"] {
                assert!(!line.contains(secret), "{text:?}: {line}");
            }
        }
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
