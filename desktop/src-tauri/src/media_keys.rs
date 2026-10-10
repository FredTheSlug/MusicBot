//! Keyboard media keys and the Windows media flyout (SMTC), via souvlaki.
//! Attached only while the bot is playing, so the keys go back to other apps when it's idle.

use crate::bridge::{self, BotCommand};
use crate::state::BotState;
use crate::AppState;
use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig};
use std::ffi::c_void;
use std::time::Duration;
use tauri::{AppHandle, Manager};

/// ⏹ pauses rather than stops: stopping would clear the queue for everyone in the channel.
pub fn key_to_command(event: &MediaControlEvent, paused: bool) -> Option<BotCommand> {
    match event {
        MediaControlEvent::Toggle => Some(BotCommand::TogglePause),
        MediaControlEvent::Play => paused.then_some(BotCommand::TogglePause),
        MediaControlEvent::Pause | MediaControlEvent::Stop => Some(BotCommand::Pause),
        MediaControlEvent::Next => Some(BotCommand::Skip),
        MediaControlEvent::Previous => Some(BotCommand::RestartTrack),
        _ => None,
    }
}

pub struct MediaKeys {
    controls: MediaControls,
    app: AppHandle,
    attached: bool,
    metadata_key: Option<(String, String)>,
}

impl MediaKeys {
    pub fn new(hwnd: *mut c_void, app: AppHandle) -> Result<Self, String> {
        let config = PlatformConfig { display_name: "Clout Kings Music Bot", dbus_name: "cloutkings_musicbot", hwnd: Some(hwnd) };
        let controls = MediaControls::new(config).map_err(|e| format!("{e:?}"))?;
        Ok(MediaKeys { controls, app, attached: false, metadata_key: None })
    }

    fn attach(&mut self) {
        let app = self.app.clone();
        let result = self.controls.attach(move |event| {
            let paused = app.state::<AppState>().store.lock().unwrap().current().is_some_and(|s| s.paused);
            if let Some(command) = key_to_command(&event, paused) {
                if let Err(e) = bridge::send_command(&app, command) {
                    log::warn!("media key command failed: {e}");
                }
            }
        });
        match result {
            Ok(()) => self.attached = true,
            Err(e) => log::warn!("could not attach media controls: {e:?}"),
        }
    }

    pub fn update(&mut self, state: Option<&BotState>, position_ms: Option<u64>) {
        let live = state.filter(|s| s.connected && s.signed_in);
        let Some((track, paused)) = live.and_then(|s| s.track.as_ref().map(|t| (t, s.paused))) else {
            if self.attached {
                let _ = self.controls.set_playback(MediaPlayback::Stopped);
                let _ = self.controls.detach();
                self.attached = false;
                self.metadata_key = None;
            }
            return;
        };
        if !self.attached {
            self.attach();
        }
        let key = (track.uri.clone(), track.title.clone());
        if self.metadata_key.as_ref() != Some(&key) {
            let duration = (!track.is_stream && track.duration_ms > 0).then(|| Duration::from_millis(track.duration_ms));
            let _ = self.controls.set_metadata(MediaMetadata {
                title: Some(&track.title),
                artist: Some(&track.author),
                album: Some("Clout Kings Music Bot"),
                cover_url: track.artwork_url.as_deref(),
                duration,
            });
            self.metadata_key = Some(key);
        }
        let progress = position_ms.map(|ms| MediaPosition(Duration::from_millis(ms)));
        let playback = if paused { MediaPlayback::Paused { progress } } else { MediaPlayback::Playing { progress } };
        let _ = self.controls.set_playback(playback);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use souvlaki::MediaControlEvent as E;

    #[test]
    fn keys_map_to_bot_commands() {
        assert_eq!(key_to_command(&E::Toggle, false), Some(BotCommand::TogglePause));
        assert_eq!(key_to_command(&E::Pause, false), Some(BotCommand::Pause));
        assert_eq!(key_to_command(&E::Next, false), Some(BotCommand::Skip));
        assert_eq!(key_to_command(&E::Previous, false), Some(BotCommand::RestartTrack));
    }

    #[test]
    fn play_only_resumes_when_paused() {
        assert_eq!(key_to_command(&E::Play, true), Some(BotCommand::TogglePause));
        assert_eq!(key_to_command(&E::Play, false), None);
    }

    #[test]
    fn stop_pauses_instead_of_clearing_the_queue() {
        assert_eq!(key_to_command(&E::Stop, false), Some(BotCommand::Pause));
    }

    #[test]
    fn unrelated_events_are_ignored() {
        assert_eq!(key_to_command(&E::Raise, false), None);
        assert_eq!(key_to_command(&E::Quit, false), None);
        assert_eq!(key_to_command(&E::OpenUri("https://example.com".into()), false), None);
    }
}
