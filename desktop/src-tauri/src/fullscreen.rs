//! Hide the mini-player while a fullscreen game, video or presentation runs (Windows' own
//! "busy" notification state), and bring it back afterwards.

use crate::{mini_player, AppState};
use std::time::Duration;
use tauri::{AppHandle, Manager};

const CHECK_EVERY: Duration = Duration::from_secs(1);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fullscreen {
    Started,
    Ended,
}

/// QUNS_BUSY (a fullscreen app), QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE.
pub fn is_fullscreen(state: i32) -> bool {
    matches!(state, 2..=4)
}

/// Only the moment fullscreen starts or ends counts, so the app never fights a user who shows the
/// mini-player again in the middle of a game.
pub fn transition(was: bool, now: bool) -> Option<Fullscreen> {
    match (was, now) {
        (false, true) => Some(Fullscreen::Started),
        (true, false) => Some(Fullscreen::Ended),
        _ => None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Hide,
    Restore,
    Nothing,
}

/// Hide only a visible mini-player on the game's monitor; restore only a hide the app did itself.
pub fn on_change(change: Fullscreen, visible: bool, same_monitor: bool, hid_it: bool) -> Action {
    match change {
        Fullscreen::Started if visible && same_monitor => Action::Hide,
        Fullscreen::Ended if hid_it => Action::Restore,
        _ => Action::Nothing,
    }
}

/// Whether the fullscreen (foreground) window is on the same monitor as the mini-player.
#[cfg(windows)]
fn same_monitor(app: &AppHandle) -> bool {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Gdi::{MonitorFromWindow, MONITOR_DEFAULTTONEAREST};
    use windows::Win32::UI::WindowsAndMessaging::GetForegroundWindow;
    let Some(window) = app.get_webview_window(mini_player::LABEL) else { return false };
    let Ok(mini) = window.hwnd() else { return true };
    // SAFETY: plain window/monitor handle queries.
    unsafe {
        let game = MonitorFromWindow(GetForegroundWindow(), MONITOR_DEFAULTTONEAREST);
        let ours = MonitorFromWindow(HWND(mini.0 as *mut std::ffi::c_void), MONITOR_DEFAULTTONEAREST);
        game == ours
    }
}

#[cfg(not(windows))]
fn same_monitor(_app: &AppHandle) -> bool {
    true
}

#[cfg(windows)]
fn fullscreen_now() -> bool {
    use windows::Win32::UI::Shell::SHQueryUserNotificationState;
    // SAFETY: no arguments; returns a plain enum value.
    unsafe { SHQueryUserNotificationState() }.is_ok_and(|state| is_fullscreen(state.0))
}

#[cfg(not(windows))]
fn fullscreen_now() -> bool {
    false
}

/// Shows the mini-player without taking focus from whatever the user went back to.
#[cfg(windows)]
fn show_quietly(app: &AppHandle) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{ShowWindow, SW_SHOWNOACTIVATE};
    let Some(window) = app.get_webview_window(mini_player::LABEL) else { return };
    if let Ok(hwnd) = window.hwnd() {
        // SAFETY: the handle belongs to our own live window.
        let _ = unsafe { ShowWindow(HWND(hwnd.0 as *mut std::ffi::c_void), SW_SHOWNOACTIVATE) };
    }
}

#[cfg(not(windows))]
fn show_quietly(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(mini_player::LABEL) {
        let _ = window.show();
    }
}

/// Polls once a second while the setting is on.
pub fn spawn_worker(app: AppHandle) {
    std::thread::spawn(move || {
        let mut was = false;
        let mut hid_it = false;
        loop {
            std::thread::sleep(CHECK_EVERY);
            let enabled = app.state::<AppState>().settings.lock().unwrap().hide_in_fullscreen;
            let now = enabled && fullscreen_now();
            if let Some(change) = transition(was, now) {
                let visible = mini_player::is_visible(&app);
                let same = change == Fullscreen::Started && visible && same_monitor(&app);
                match on_change(change, visible, same, hid_it) {
                    Action::Hide => {
                        mini_player::hide(&app);
                        hid_it = true;
                    }
                    Action::Restore => {
                        show_quietly(&app);
                        hid_it = false;
                    }
                    Action::Nothing => {
                        if change == Fullscreen::Ended {
                            hid_it = false;
                        }
                    }
                }
            }
            was = now;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fullscreen_states() {
        // QUNS_BUSY, QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE
        for state in [2, 3, 4] {
            assert!(is_fullscreen(state), "{state}");
        }
        // not present, not reachable, accepts notifications, quiet time, app (Store app)
        for state in [0, 1, 5, 6, 7] {
            assert!(!is_fullscreen(state), "{state}");
        }
    }

    #[test]
    fn hides_only_on_the_same_monitor_and_restores_only_its_own_hide() {
        use Action::*;
        assert_eq!(on_change(Fullscreen::Started, true, true, false), Hide);
        assert_eq!(on_change(Fullscreen::Started, true, false, false), Nothing, "game on the other monitor");
        assert_eq!(on_change(Fullscreen::Started, false, true, false), Nothing, "already hidden by the user");
        assert_eq!(on_change(Fullscreen::Ended, false, true, true), Restore);
        assert_eq!(on_change(Fullscreen::Ended, false, true, false), Nothing, "the user hid it, not us");
    }

    #[test]
    fn transition_is_edge_triggered() {
        assert_eq!(transition(false, true), Some(Fullscreen::Started));
        assert_eq!(transition(true, true), None);
        assert_eq!(transition(true, false), Some(Fullscreen::Ended));
        assert_eq!(transition(false, false), None);
    }
}
