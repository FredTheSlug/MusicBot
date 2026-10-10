// No console window in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod settings;
mod bridge;
mod media_keys;
mod mini_player;
mod state;
mod tray;

use settings::Settings;
use std::collections::HashMap;
use state::{Change, StateStore};
use std::path::PathBuf;
use std::sync::mpsc::Sender;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::ipc::CapabilityBuilder;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_log::{RotationStrategy, Target, TargetKind};
use tauri_plugin_opener::OpenerExt;

pub struct AppState {
    pub settings: Mutex<Settings>,
    pub settings_path: PathBuf,
    pub store: Mutex<StateStore>,
    /// Commands awaiting a `Result` report from the page, by id.
    pub pending: Mutex<HashMap<u64, Sender<Result<(), String>>>>,
    /// None when media controls failed to start; the app works without them.
    pub media: Mutex<Option<media_keys::MediaKeys>>,
}

/// Fans state changes out to the tray, mini-player, media keys and notifier.
fn dispatch(app: &AppHandle, changes: Vec<Change>) {
    for change in &changes {
        log::info!("state change: {}", describe(change));
    }
    tray::refresh(app);
    mini_player::emit_state(app);
    update_media_keys(app);
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

/// Messages from the bot (e.g. "No results found"). Tasks 7 and 10 surface these to the user.
fn dispatch_notice(app: &AppHandle, level: &str, message: &str) {
    if mini_player::is_visible(app) {
        mini_player::emit_notice(app, level, message);
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
    });
}

/// The panel window may only navigate within the bot's site and Discord's OAuth pages.
fn allowed_navigation(url: &url::Url, origin: &str) -> bool {
    if url.origin().ascii_serialization() == origin {
        return true;
    }
    url.scheme() == "https" && url.host_str() == Some("discord.com")
}

pub(crate) fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn create_main_window(app: &AppHandle, panel_url: url::Url, origin: String) -> tauri::Result<()> {
    let handle = app.clone();
    WebviewWindowBuilder::new(app, "main", WebviewUrl::External(panel_url))
        .title("Clout Kings Music Bot")
        .initialization_script(bridge::init_script(&origin))
        .inner_size(1280.0, 860.0)
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
        .setup(|app| {
            let settings_path = app.path().app_config_dir()?.join("settings.json");
            let settings = settings::load(&settings_path);
            let (Some(panel_url), Some(origin)) = (settings.panel_url(), settings.server_origin()) else {
                return Err(format!("serverUrl in {} is not a valid URL", settings_path.display()).into());
            };
            app.manage(AppState {
                settings: Mutex::new(settings),
                settings_path,
                store: Mutex::new(StateStore::new()),
                pending: Mutex::new(HashMap::new()),
                media: Mutex::new(None),
            });
            // The remote panel page may call exactly one command: bridge_report.
            app.add_capability(
                CapabilityBuilder::new("panel")
                    .remote(format!("{origin}/*"))
                    .window("main")
                    .permission("allow-bridge-report"),
            )?;
            create_main_window(app.handle(), panel_url, origin)?;
            start_media_keys(app.handle());
            tray::build(app.handle())?;
            mini_player::create(app.handle())?;
            spawn_ticker(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the panel hides it to the tray; Quit in the tray exits.
            match event {
                WindowEvent::CloseRequested { api, .. } if window.label() == "main" => {
                    api.prevent_close();
                    let _ = window.hide();
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
            mini_player::mini_hide
        ])
        .run(tauri::generate_context!())
        .expect("error while running the app");
}

#[cfg(test)]
mod tests {
    use super::*;

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
