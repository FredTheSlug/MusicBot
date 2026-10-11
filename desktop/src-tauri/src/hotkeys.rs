//! Global hotkeys: control the bot from anywhere, even in a game where media keys don't reach.
//! Off by default (Ctrl+Alt is AltGr on many keyboards).

use crate::bridge::{self, BotCommand};
use crate::{mini_player, spotify, tray, AppState};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::str::FromStr;
use std::sync::Mutex;
use tauri::{AppHandle, Manager};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutEvent, ShortcutState};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum HotkeyAction {
    PlayPause,
    Skip,
    Restart,
    SendSpotify,
    ToggleMiniPlayer,
}

pub const ALL_ACTIONS: [HotkeyAction; 5] = [
    HotkeyAction::PlayPause,
    HotkeyAction::Skip,
    HotkeyAction::Restart,
    HotkeyAction::SendSpotify,
    HotkeyAction::ToggleMiniPlayer,
];

impl HotkeyAction {
    fn default_binding(self) -> &'static str {
        match self {
            HotkeyAction::PlayPause => "Ctrl+Alt+P",
            HotkeyAction::Skip => "Ctrl+Alt+N",
            HotkeyAction::Restart => "Ctrl+Alt+B",
            HotkeyAction::SendSpotify => "Ctrl+Alt+S",
            HotkeyAction::ToggleMiniPlayer => "Ctrl+Alt+M",
        }
    }

    fn label(self) -> &'static str {
        match self {
            HotkeyAction::PlayPause => "Play/pause",
            HotkeyAction::Skip => "Skip",
            HotkeyAction::Restart => "Restart song",
            HotkeyAction::SendSpotify => "Send my Spotify song",
            HotkeyAction::ToggleMiniPlayer => "Show/hide mini-player",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Hotkeys {
    pub enabled: bool,
    /// Only bindings the user changed need to be here; the rest use the defaults.
    pub bindings: BTreeMap<HotkeyAction, String>,
}

impl Default for Hotkeys {
    fn default() -> Self {
        Hotkeys { enabled: false, bindings: BTreeMap::new() }
    }
}

impl Hotkeys {
    pub fn binding(&self, action: HotkeyAction) -> String {
        self.bindings.get(&action).cloned().unwrap_or_else(|| action.default_binding().to_string())
    }
}

pub fn parse(accel: &str) -> Result<Shortcut, String> {
    Shortcut::from_str(accel).map_err(|_| format!("“{accel}” isn't a key combination"))
}

fn is_function_key(shortcut: &Shortcut) -> bool {
    use Code::*;
    matches!(
        shortcut.key,
        F1 | F2 | F3 | F4 | F5 | F6 | F7 | F8 | F9 | F10 | F11 | F12 | F13 | F14 | F15 | F16 | F17 | F18 | F19 | F20 | F21 | F22 | F23 | F24
    )
}

/// A binding must parse, use a modifier (except F-keys) and not already belong to another action.
/// Returns the binding to save.
pub fn check_binding(hotkeys: &Hotkeys, action: HotkeyAction, accel: &str) -> Result<String, String> {
    let shortcut = parse(accel)?;
    let modifiers = Modifiers::CONTROL | Modifiers::ALT | Modifiers::SHIFT | Modifiers::SUPER;
    if !shortcut.mods.intersects(modifiers) && !is_function_key(&shortcut) {
        return Err("Use Ctrl, Alt or Shift with that key".to_string());
    }
    for other in ALL_ACTIONS.into_iter().filter(|&a| a != action) {
        if parse(&hotkeys.binding(other)).is_ok_and(|s| s.id() == shortcut.id()) {
            return Err(format!("{accel} is already {}", other.label()));
        }
    }
    Ok(accel.to_string())
}

/// What the mini-player's settings show.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HotkeysView {
    pub enabled: bool,
    pub bindings: BTreeMap<HotkeyAction, String>,
    /// Bindings Windows refused (another app owns the combination).
    pub failed: Vec<HotkeyAction>,
}

static FAILED: Mutex<Vec<HotkeyAction>> = Mutex::new(Vec::new());

pub fn view(app: &AppHandle) -> HotkeysView {
    let hotkeys = app.state::<AppState>().settings.lock().unwrap().hotkeys.clone();
    HotkeysView {
        enabled: hotkeys.enabled,
        bindings: ALL_ACTIONS.iter().map(|&a| (a, hotkeys.binding(a))).collect(),
        failed: FAILED.lock().unwrap().clone(),
    }
}

/// Registers the saved bindings (or none when off); remembers which ones Windows refused.
pub fn apply(app: &AppHandle) -> Vec<HotkeyAction> {
    let shortcuts = app.global_shortcut();
    if let Err(e) = shortcuts.unregister_all() {
        log::warn!("could not clear hotkeys: {e}");
    }
    let hotkeys = app.state::<AppState>().settings.lock().unwrap().hotkeys.clone();
    let mut failed = Vec::new();
    if hotkeys.enabled {
        for action in ALL_ACTIONS {
            let accel = hotkeys.binding(action);
            if let Err(e) = parse(&accel).and_then(|s| shortcuts.register(s).map_err(|e| e.to_string())) {
                log::warn!("hotkey {accel} for {action:?} not registered: {e}");
                failed.push(action);
            }
        }
    }
    *FAILED.lock().unwrap() = failed.clone();
    failed
}

/// Releases every combination while the user records a new one (or puts them back).
pub fn suspend(app: &AppHandle, suspended: bool) {
    if suspended {
        if let Err(e) = app.global_shortcut().unregister_all() {
            log::warn!("could not pause hotkeys: {e}");
        }
    } else {
        apply(app);
    }
}

/// The plugin's handler: runs the action bound to the pressed combination.
pub fn on_shortcut(app: &AppHandle, shortcut: &Shortcut, event: ShortcutEvent) {
    if event.state != ShortcutState::Pressed {
        return;
    }
    let hotkeys = app.state::<AppState>().settings.lock().unwrap().hotkeys.clone();
    let Some(action) = ALL_ACTIONS.into_iter().find(|&a| parse(&hotkeys.binding(a)).is_ok_and(|s| s.id() == shortcut.id())) else {
        return;
    };
    let command = match action {
        HotkeyAction::PlayPause => Some(BotCommand::TogglePause),
        HotkeyAction::Skip => Some(BotCommand::Skip),
        HotkeyAction::Restart => Some(BotCommand::RestartTrack),
        HotkeyAction::SendSpotify => {
            tray::run_spotify_action(app, spotify::send_current);
            None
        }
        HotkeyAction::ToggleMiniPlayer => {
            mini_player::toggle(app);
            None
        }
    };
    if let Some(command) = command {
        if let Err(e) = bridge::send_command(app, command) {
            crate::dispatch_notice(app, "error", &e);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hotkey_defaults() {
        let hotkeys = Hotkeys::default();
        assert!(!hotkeys.enabled);
        assert_eq!(hotkeys.binding(HotkeyAction::PlayPause), "Ctrl+Alt+P");
        assert_eq!(hotkeys.binding(HotkeyAction::Skip), "Ctrl+Alt+N");
        assert_eq!(hotkeys.binding(HotkeyAction::Restart), "Ctrl+Alt+B");
        assert_eq!(hotkeys.binding(HotkeyAction::SendSpotify), "Ctrl+Alt+S");
        assert_eq!(hotkeys.binding(HotkeyAction::ToggleMiniPlayer), "Ctrl+Alt+M");
        // Missing entries (an older settings file) fall back to the defaults.
        let partial: Hotkeys = serde_json::from_str(r#"{"enabled": true, "bindings": {"skip": "Ctrl+Shift+Right"}}"#).unwrap();
        assert!(partial.enabled);
        assert_eq!(partial.binding(HotkeyAction::Skip), "Ctrl+Shift+Right");
        assert_eq!(partial.binding(HotkeyAction::PlayPause), "Ctrl+Alt+P");
    }

    #[test]
    fn parses_bindings() {
        for action in ALL_ACTIONS {
            assert!(parse(&Hotkeys::default().binding(action)).is_ok(), "{action:?}");
        }
        assert!(parse("Ctrl+Shift+ArrowRight").is_ok());
        assert!(parse("F9").is_ok());
        assert!(parse("Ctrl+Alt+").is_err());
    }

    #[test]
    fn rejects_duplicate_binding() {
        let hotkeys = Hotkeys::default();
        assert!(check_binding(&hotkeys, HotkeyAction::Skip, "ctrl+alt+p").unwrap_err().contains("Play/pause"));
        assert_eq!(check_binding(&hotkeys, HotkeyAction::PlayPause, "Ctrl+Alt+P"), Ok("Ctrl+Alt+P".to_string()));
        assert!(check_binding(&hotkeys, HotkeyAction::Skip, "Ctrl+Alt+").is_err());
        assert_eq!(check_binding(&hotkeys, HotkeyAction::Skip, "P"), Err("Use Ctrl, Alt or Shift with that key".to_string()));
        assert_eq!(check_binding(&hotkeys, HotkeyAction::Skip, "F9"), Ok("F9".to_string()));
    }
}
