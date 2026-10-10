// No console window in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod settings;

use settings::Settings;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_log::{RotationStrategy, Target, TargetKind};
use tauri_plugin_opener::OpenerExt;

pub struct AppState {
    pub settings: Mutex<Settings>,
    pub settings_path: PathBuf,
}

/// The panel window may only navigate within the bot's site and Discord's OAuth pages.
fn allowed_navigation(url: &url::Url, origin: &str) -> bool {
    if url.origin().ascii_serialization() == origin {
        return true;
    }
    url.scheme() == "https" && url.host_str() == Some("discord.com")
}

fn show_main(app: &AppHandle) {
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

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show-panel", "Show panel", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main-tray")
        .tooltip("Clout Kings Music Bot")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show-panel" => show_main(app),
            "quit" => app.exit(0),
            _ => {}
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
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
            app.manage(AppState { settings: Mutex::new(settings), settings_path });
            create_main_window(app.handle(), panel_url, origin)?;
            build_tray(app.handle())?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the panel hides it to the tray; Quit in the tray exits.
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
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
