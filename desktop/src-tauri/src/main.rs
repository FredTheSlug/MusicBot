// No console window in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod bridge;
mod fullscreen;
mod hotkeys;
mod lookup;
mod media_keys;
mod mini_player;
mod notify;
mod settings;
mod sleep;
mod spotify;
mod state;
mod theme;
mod tray;
mod updates;
mod whats_new;

use settings::{LastView, Settings};
use std::collections::HashMap;
use state::{Change, StateStore};
use std::path::PathBuf;
use std::sync::mpsc::Sender;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::ipc::CapabilityBuilder;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_log::{RotationStrategy, Target, TargetKind};
use tauri_plugin_opener::OpenerExt;

pub struct AppState {
    pub settings: Mutex<Settings>,
    pub settings_path: PathBuf,
    pub store: Mutex<StateStore>,
    /// Commands awaiting a `Result` report from the page, by id.
    pub pending: Mutex<HashMap<u64, Sender<Result<Option<serde_json::Value>, String>>>>,
    /// None when media controls failed to start; the app works without them.
    pub media: Mutex<Option<media_keys::MediaKeys>>,
    /// Last Spotify status seen by the ticker.
    pub spotify: Mutex<spotify::SpotifyStatus>,
    /// Sleep timer for this session.
    pub sleep: Mutex<sleep::SleepMode>,
    /// The custom themes that have a mini-player version, as last fetched from the bot (empty until then).
    pub customs: Mutex<Vec<theme::CustomMini>>,
    /// The signed-in person's liked songs, as last fetched from the bot (unknown until then).
    pub likes: Mutex<mini_player::LikesState>,
}

/// Fans state changes out to the tray, mini-player, media keys and notifier.
fn dispatch(app: &AppHandle, changes: Vec<Change>) {
    for change in &changes {
        log::info!("state change: {}", describe(change));
    }
    show_toasts(app, &changes);
    tray::refresh(app);
    mini_player::emit_state(app);
    mini_player::on_state_changes(app, &changes);
    update_media_keys(app);
}

/// True when the panel window is visible and has focus (toasts would be redundant).
fn main_window_in_front(app: &AppHandle) -> bool {
    app.get_webview_window("main")
        .is_some_and(|w| w.is_visible().unwrap_or(false) && w.is_focused().unwrap_or(false))
}

fn show_toasts(app: &AppHandle, changes: &[Change]) {
    if changes.is_empty() {
        return;
    }
    let app_state = app.state::<AppState>();
    let state = app_state.store.lock().unwrap().current().cloned();
    let settings = app_state.settings.lock().unwrap().clone();
    let toasts = notify::toasts_for(changes, state.as_ref(), &settings, main_window_in_front(app), mini_player::is_visible(app));
    for toast in &toasts {
        notify::show(app, toast);
    }
}

fn update_media_keys(app: &AppHandle) {
    let app_state = app.state::<AppState>();
    let (state, position) = {
        let store = app_state.store.lock().unwrap();
        (store.current().cloned(), store.position_ms(Instant::now()))
    };
    let mut media = app_state.media.lock().unwrap();
    if let Some(media) = media.as_mut() {
        media.update(state.as_ref(), position);
    }
}

fn start_media_keys(app: &AppHandle) {
    let Some(main) = app.get_webview_window("main") else { return };
    let media = main
        .hwnd()
        .map_err(|e| e.to_string())
        .and_then(|hwnd| media_keys::MediaKeys::new(hwnd.0 as *mut std::ffi::c_void, app.clone()));
    match media {
        Ok(media) => *app.state::<AppState>().media.lock().unwrap() = Some(media),
        Err(e) => log::warn!("media keys unavailable: {e}"),
    }
}

/// Messages for the user (bot errors like "No results found", Spotify action results).
/// Shown in the mini-player when it's open, otherwise as a toast unless the panel is in front.
pub(crate) fn dispatch_notice(app: &AppHandle, level: &str, message: &str) {
    if mini_player::is_visible(app) {
        mini_player::emit_notice(app, level, message);
    } else if !main_window_in_front(app) {
        notify::show(app, &notify::Toast { title: "Clout Kings Music Bot".into(), body: message.into() });
    }
    let message = bridge::redact_urls(message);
    match level {
        "error" => log::warn!("bot notice: {message}"),
        _ => log::info!("bot notice: {message}"),
    }
}

/// Log-safe summary of a change (titles only, no URLs).
fn describe(change: &Change) -> String {
    match change {
        Change::TrackChanged { track, initial } => format!("track \"{}\" (initial: {initial})", track.title),
        Change::PlaybackChanged { paused } => format!("paused: {paused}"),
        Change::Cleared => "nothing playing".into(),
        Change::QueueHeadChanged(head) => format!("queue head: {:?}", head.as_ref().map(|h| &h.title)),
        Change::ConnectionChanged { connected, signed_in } => format!("connected: {connected}, signed in: {signed_in}"),
    }
}

/// Marks the bot unreachable when the bridge goes quiet for `state::STALE_AFTER`.
fn spawn_ticker(app: AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(5));
        let changes = app.state::<AppState>().store.lock().unwrap().check_stale(Instant::now());
        if !changes.is_empty() {
            dispatch(&app, changes);
        }
        poll_spotify(&app);
    });
}

/// Keeps the tray item and mini-player label in step with what Spotify is playing.
fn poll_spotify(app: &AppHandle) {
    let status = spotify::current(&spotify::own_app_id(app));
    let changed = {
        let app_state = app.state::<AppState>();
        let mut last = app_state.spotify.lock().unwrap();
        let changed = *last != status;
        *last = status.clone();
        changed
    };
    if changed {
        match &status.now {
            Some(now) => log::info!("spotify: \"{}\" by {} (playing: {})", now.title, now.artist, now.playing),
            None => log::info!("spotify: not open (media sessions available: {})", status.available),
        }
        tray::set_spotify(app, &status);
        mini_player::emit_spotify(app, &status);
    }
}

/// The panel window may only navigate within the bot's site and Discord's OAuth pages.
fn allowed_navigation(url: &url::Url, origin: &str) -> bool {
    if url.origin().ascii_serialization() == origin {
        return true;
    }
    url.scheme() == "https" && url.host_str() == Some("discord.com")
}

/// Windows whose close request (X button, Alt+F4) hides them instead of destroying them.
fn hides_on_close(label: &str) -> bool {
    label == "main" || label == mini_player::LABEL
}

/// Launched by "Start with Windows": stay in the tray instead of opening the panel.
fn start_hidden(args: &[String]) -> bool {
    args.iter().any(|a| a == "--hidden")
}

/// Which windows a start shows, as (panel, mini-player): the ones open when the person last showed or hid one.
/// Started by hand with everything in the tray, the panel opens anyway, so a launch never looks like nothing happened.
/// Started with Windows (`hidden`), the panel never opens; the mini-player does if it was open.
fn launch_view(last: LastView, hidden: bool) -> (bool, bool) {
    if hidden {
        return (false, last.mini);
    }
    (last.panel || !last.mini, last.mini)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ViewWindow {
    Panel,
    Mini,
}

fn remembered(last: LastView, window: ViewWindow, shown: bool) -> LastView {
    match window {
        ViewWindow::Panel => LastView { panel: shown, ..last },
        ViewWindow::Mini => LastView { mini: shown, ..last },
    }
}

/// The person showed or hid a window (tray, hotkey, close to the tray, a button): remembered for the next start.
/// Only the user's paths call this, never the fullscreen auto-hide, which is temporary.
pub(crate) fn remember_view(app: &AppHandle, window: ViewWindow, shown: bool) {
    let state = app.state::<AppState>();
    let mut settings = state.settings.lock().unwrap();
    let next = remembered(settings.last_view, window, shown);
    if next == settings.last_view {
        return;
    }
    settings.last_view = next;
    if let Err(e) = settings::save(&state.settings_path, &settings) {
        log::warn!("could not save the open windows: {e}");
    }
}

pub(crate) fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
        remember_view(app, ViewWindow::Panel, true);
    }
}

/// Created hidden: the page loads in the background either way (the mini-player works through it).
fn create_main_window(app: &AppHandle, panel_url: url::Url, origin: String) -> tauri::Result<()> {
    let handle = app.clone();
    WebviewWindowBuilder::new(app, "main", WebviewUrl::External(panel_url))
        .title("Clout Kings Music Bot")
        .initialization_script(bridge::init_script(&origin))
        .inner_size(1280.0, 860.0)
        .visible(false)
        // The panel uses HTML5 drag and drop (moving panes, dropping audio files to upload); on
        // Windows that only reaches the page when Tauri's own drop handler is off.
        .disable_drag_drop_handler()
        .on_navigation(move |url| {
            if allowed_navigation(url, &origin) {
                return true;
            }
            if let Err(e) = handle.opener().open_url(url.as_str(), None::<&str>) {
                log::warn!("could not open external link: {e}");
            }
            false
        })
        .build()?;
    Ok(())
}

fn main() {
    tauri::Builder::default()
        // Must be first: a second launch just focuses the running app.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(
            tauri_plugin_log::Builder::new()
                .targets([
                    Target::new(TargetKind::Stdout),
                    Target::new(TargetKind::LogDir { file_name: None }),
                ])
                .max_file_size(1_000_000)
                .rotation_strategy(RotationStrategy::KeepSome(5))
                .level(log::LevelFilter::Info)
                .build(),
        )
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_global_shortcut::Builder::new().with_handler(hotkeys::on_shortcut).build())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .setup(|app| {
            let settings_path = app.path().app_config_dir()?.join("settings.json");
            // Read before anything below writes the file: a fresh install has none yet.
            let settings_existed = settings_path.exists();
            let mut settings = settings::load(&settings_path);
            let (Some(panel_url), Some(origin)) = (settings.panel_url(), settings.server_origin()) else {
                return Err(format!("serverUrl in {} is not a valid URL", settings_path.display()).into());
            };
            // The same version the updater compares against.
            let version = app.package_info().version.to_string();
            let updated = whats_new::just_updated(settings.last_run_version.as_deref(), &version, settings_existed);
            if settings.last_run_version.as_deref() != Some(version.as_str()) {
                settings.last_run_version = Some(version.clone());
                if let Err(e) = settings::save(&settings_path, &settings) {
                    log::warn!("could not save the app version: {e}");
                }
            }
            let start_with_windows = settings.start_with_windows;
            let hidden = start_hidden(&std::env::args().collect::<Vec<_>>());
            let (show_panel, show_mini) = launch_view(settings.last_view, hidden);
            app.manage(AppState {
                settings: Mutex::new(settings),
                settings_path,
                store: Mutex::new(StateStore::new()),
                pending: Mutex::new(HashMap::new()),
                media: Mutex::new(None),
                spotify: Mutex::new(spotify::SpotifyStatus::default()),
                sleep: Mutex::new(sleep::SleepMode::Off),
                customs: Mutex::new(Vec::new()),
                likes: Mutex::new(mini_player::LikesState::default()),
            });
            // The remote panel page may call exactly one command: bridge_report.
            app.add_capability(
                CapabilityBuilder::new("panel")
                    .remote(format!("{origin}/*"))
                    .window("main")
                    .permission("allow-bridge-report"),
            )?;
            create_main_window(app.handle(), panel_url, origin)?;
            if start_with_windows {
                // Re-register so the entry follows the app if an update moved it.
                if let Err(e) = app.autolaunch().enable() {
                    log::warn!("could not register Start with Windows: {e}");
                }
            }
            start_media_keys(app.handle());
            tray::build(app.handle())?;
            mini_player::create(app.handle())?;
            log::info!("start: panel {show_panel}, mini-player {show_mini} (with Windows: {hidden}, just updated: {updated})");
            if show_mini {
                mini_player::show_at_start(app.handle());
            }
            // After the mini-player, so the panel ends up in front.
            if show_panel {
                show_main(app.handle());
            }
            if whats_new::after_launch(updated, show_panel) == whats_new::Announce::Toast {
                notify::show(app.handle(), &whats_new::update_toast(&version));
            }
            spawn_ticker(app.handle().clone());
            sleep::spawn_worker(app.handle().clone());
            fullscreen::spawn_worker(app.handle().clone());
            hotkeys::apply(app.handle());
            updates::check_for_updates(app.handle(), false);
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the panel or the mini-player hides it to the tray; Quit in the tray exits.
            match event {
                WindowEvent::CloseRequested { api, .. } if hides_on_close(window.label()) => {
                    api.prevent_close();
                    if window.label() == mini_player::LABEL {
                        mini_player::hide(window.app_handle());
                    } else {
                        let _ = window.hide();
                        remember_view(window.app_handle(), ViewWindow::Panel, false);
                    }
                }
                WindowEvent::Moved(position) if window.label() == mini_player::LABEL => {
                    mini_player::on_moved(window.app_handle(), *position);
                }
                _ => {}
            }
        })
        .invoke_handler(tauri::generate_handler![
            bridge::bridge_report,
            mini_player::mini_get_state,
            mini_player::mini_command,
            mini_player::mini_open_panel,
            mini_player::mini_show_whats_new,
            mini_player::mini_hide,
            mini_player::mini_spotify_now,
            mini_player::mini_send_spotify,
            mini_player::mini_play_spotify_text,
            mini_player::mini_search,
            mini_player::mini_lyrics,
            mini_player::mini_play_link,
            mini_player::mini_play_clipboard,
            mini_player::mini_get_prefs,
            mini_player::mini_set_opacity,
            mini_player::mini_set_theme,
            mini_player::mini_refresh_themes,
            mini_player::mini_set_panel,
            mini_player::mini_history,
            mini_player::mini_frequent,
            mini_player::mini_playlists,
            mini_player::mini_playlists_v2,
            mini_player::mini_add_to_playlist,
            mini_player::mini_create_playlist,
            mini_player::mini_play_playlist,
            mini_player::mini_likes,
            mini_player::mini_like,
            mini_player::mini_unlike,
            mini_player::mini_play_likes,
            mini_player::mini_set_sleep,
            mini_player::mini_get_sleep,
            mini_player::mini_set_hide_in_fullscreen,
            mini_player::mini_get_hotkeys,
            mini_player::mini_set_hotkeys_enabled,
            mini_player::mini_set_hotkey,
            mini_player::mini_set_compact,
            mini_player::mini_hotkeys_suspend
        ])
        .run(tauri::generate_context!())
        .expect("error while running the app");
}

/// Reading the app's own sources in tests, for wiring no unit test can reach (windows, the tray, startup).
#[cfg(test)]
pub(crate) mod source {
    /// The code of a source file, without its tests.
    pub fn code(source: &str) -> &str {
        source.split("#[cfg(test)]").next().unwrap()
    }

    /// A top-level item, from where `start` is found to its closing brace at the start of a line (either line ending).
    pub fn item<'a>(code: &'a str, start: &str) -> &'a str {
        let from = &code[code.find(start).unwrap_or_else(|| panic!("{start} not found"))..];
        &from[..from.find("\n}").unwrap_or(from.len())]
    }

    /// Every run of whitespace as one space, so a check doesn't depend on line breaks or indentation.
    pub fn squash(code: &str) -> String {
        code.split_whitespace().collect::<Vec<_>>().join(" ")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn close_hides_main_and_mini_player() {
        assert!(hides_on_close("main"));
        // Alt+F4 on the focused mini-player must not destroy it: the tray could never reopen it.
        assert!(hides_on_close(mini_player::LABEL));
        assert!(!hides_on_close("anything-else"));
    }

    #[test]
    fn start_hidden_flag() {
        assert!(start_hidden(&["app.exe".to_string(), "--hidden".to_string()]));
        assert!(!start_hidden(&["app.exe".to_string()]));
    }

    fn last(panel: bool, mini: bool) -> LastView {
        LastView { panel, mini }
    }

    #[test]
    fn a_normal_launch_reopens_the_last_view() {
        assert_eq!(launch_view(last(true, false), false), (true, false), "the panel, as before");
        assert_eq!(launch_view(last(false, true), false), (false, true), "the mini-player alone");
        assert_eq!(launch_view(last(true, true), false), (true, true), "both");
    }

    #[test]
    fn a_normal_launch_with_everything_in_the_tray_opens_the_panel() {
        // Starting the app by hand must never look like nothing happened.
        assert_eq!(launch_view(last(false, false), false), (true, false));
    }

    #[test]
    fn start_with_windows_never_opens_the_panel() {
        assert_eq!(launch_view(last(true, false), true), (false, false));
        assert_eq!(launch_view(last(false, true), true), (false, true));
        assert_eq!(launch_view(last(true, true), true), (false, true));
        assert_eq!(launch_view(last(false, false), true), (false, false));
    }

    #[test]
    fn an_older_settings_file_launches_as_before() {
        assert_eq!(launch_view(LastView::default(), false), (true, false));
        assert_eq!(launch_view(LastView::default(), true), (false, false));
    }

    #[test]
    fn a_shown_or_hidden_window_changes_only_its_own_half_of_the_last_view() {
        assert_eq!(remembered(last(true, false), ViewWindow::Mini, true), last(true, true));
        assert_eq!(remembered(last(true, true), ViewWindow::Panel, false), last(false, true));
        assert_eq!(remembered(last(false, true), ViewWindow::Mini, false), last(false, false));
        assert_eq!(remembered(last(false, false), ViewWindow::Panel, true), last(true, false));
        assert_eq!(remembered(last(true, false), ViewWindow::Panel, true), last(true, false));
    }

    #[test]
    fn fullscreen_and_sleep_hiding_is_never_remembered() {
        // The fullscreen hide is temporary: it goes straight to Windows and must not pass through the user's
        // show/hide paths, which remember the view. The sleep timer only pauses the music.
        let user_paths = ["remember_view", "mini_player::hide", "mini_player::toggle", "mini_player::show", "show_main", "settings::save"];
        for (name, text) in [("fullscreen.rs", include_str!("fullscreen.rs")), ("sleep.rs", include_str!("sleep.rs"))] {
            let code = source::code(text);
            assert!(code.len() > 1000, "{name} was read");
            for path in user_paths {
                assert!(!code.contains(path), "{name} calls {path}");
            }
        }
        // ...and the user's paths do remember it.
        let mini = source::code(include_str!("mini_player.rs"));
        for path in ["pub fn show(", "pub fn hide("] {
            assert!(source::item(mini, path).contains("remember_view("), "{path} remembers the view");
        }
        let main = source::code(include_str!("main.rs"));
        assert!(source::item(main, "pub(crate) fn show_main(").contains("remember_view("), "show_main remembers the view");
        let close = &main[main.find("WindowEvent::CloseRequested").unwrap()..];
        assert!(close[..close.find("WindowEvent::Moved").unwrap()].contains("remember_view("), "closing the panel to the tray is remembered");
    }

    #[test]
    fn the_panel_window_is_created_hidden_and_loads_in_the_background() {
        let create = source::item(source::code(include_str!("main.rs")), "fn create_main_window(");
        assert!(create.contains(".visible(false)"), "{create}");
        assert_eq!(create.matches(".visible(").count(), 1, "{create}");
    }

    /// Everything the app does at start, squashed.
    fn startup() -> String {
        let main = source::code(include_str!("main.rs"));
        source::squash(&main[main.find(".setup(|app|").unwrap()..main.find(".on_window_event(").unwrap()])
    }

    #[test]
    fn startup_shows_the_restored_view_only() {
        let setup = startup();
        assert_eq!(setup.matches("show_main(").count(), 1, "{setup}");
        assert!(setup.contains("if show_panel { show_main(app.handle()); }"), "{setup}");
        assert!(setup.contains("if show_mini { mini_player::show_at_start(app.handle()); }"), "{setup}");
        for user_path in ["mini_player::show(", "mini_player::toggle(", "set_focus"] {
            assert!(!setup.contains(user_path), "{user_path}");
        }
    }

    #[test]
    fn an_update_never_pops_the_panel_up_when_it_isnt_in_the_restored_view() {
        let setup = startup();
        // The update decides the toast only; whether the panel shows is the restored view's call alone.
        assert!(
            setup.contains(
                "if whats_new::after_launch(updated, show_panel) == whats_new::Announce::Toast { notify::show(app.handle(), &whats_new::update_toast(&version)); }"
            ),
            "{setup}"
        );
        for gate in ["if updated", "updated &&", "|| updated", "if show_whats_new"] {
            assert!(!setup.contains(gate), "{gate}");
        }
    }

    #[test]
    fn navigation_allowlist() {
        let o = "https://cloutkings.duckdns.org";
        let u = |s: &str| url::Url::parse(s).unwrap();
        assert!(allowed_navigation(&u("https://cloutkings.duckdns.org/api/auth/discord"), o));
        assert!(allowed_navigation(&u("https://discord.com/oauth2/authorize?client_id=1"), o));
        assert!(!allowed_navigation(&u("https://www.youtube.com/watch?v=x"), o));
        assert!(!allowed_navigation(&u("https://cloutkings.duckdns.org.evil.com/"), o));
        assert!(!allowed_navigation(&u("http://cloutkings.duckdns.org/"), o));
        assert!(!allowed_navigation(&u("https://discord.com.evil.io/"), o));
    }
}
