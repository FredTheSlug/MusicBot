//! Sleep timer: pause the bot after a while or at the end of the current song. Session only.

use crate::bridge::{self, BotCommand};
use crate::state::Track;
use crate::{dispatch_notice, mini_player, AppState};
use serde::Serialize;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager};

/// "End of song" pauses within this much of the end, so the next song never gets going.
const END_WINDOW_MS: u64 = 1_500;
const MINUTE_CHOICES: [u64; 3] = [15, 30, 60];
const CHECK_EVERY: Duration = Duration::from_millis(500);

#[derive(Debug, Clone, PartialEq)]
pub enum SleepMode {
    Off,
    At(Instant),
    /// `song` is the song playing when it was set; once a different one plays, that song is over.
    EndOfSong { song: Option<String> },
}

/// The song playing now, as the timer needs it.
#[derive(Debug, Clone, Copy)]
pub struct SongNow<'a> {
    pub key: &'a str,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub is_stream: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SleepStep {
    Wait,
    /// Pause the bot, then switch the timer off.
    Pause,
    /// Switch the timer off without sending anything (already paused, or nothing playing).
    Reset,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SleepInfo {
    pub mode: &'static str,
    pub ends_at_ms: Option<u64>,
}

pub fn song_key(track: &Track) -> String {
    format!("{}\n{}", track.title, track.uri)
}

pub fn sleep_step(mode: &SleepMode, now: Instant, song: Option<SongNow>, paused: bool) -> SleepStep {
    let fire = |due: bool| match (due, paused) {
        (false, _) => SleepStep::Wait,
        (true, true) => SleepStep::Reset,
        (true, false) => SleepStep::Pause,
    };
    match mode {
        SleepMode::Off => SleepStep::Wait,
        SleepMode::At(deadline) => fire(now >= *deadline),
        SleepMode::EndOfSong { song: armed } => {
            let Some(song) = song else { return SleepStep::Reset };
            if armed.as_deref().is_some_and(|key| key != song.key) {
                return fire(true);
            }
            let near_end = !song.is_stream && song.duration_ms > 0 && song.position_ms + END_WINDOW_MS >= song.duration_ms;
            if near_end && !paused { SleepStep::Pause } else { SleepStep::Wait }
        }
    }
}

/// "off", "15"/"30"/"60" (minutes) or "end" (this song); None for anything else.
pub fn parse_choice(choice: &str, now: Instant, playing: Option<&str>) -> Option<SleepMode> {
    match choice {
        "off" => Some(SleepMode::Off),
        "end" => Some(SleepMode::EndOfSong { song: playing.map(str::to_string) }),
        minutes => minutes
            .parse::<u64>()
            .ok()
            .filter(|m| MINUTE_CHOICES.contains(m))
            .map(|m| SleepMode::At(now + Duration::from_secs(m * 60))),
    }
}

pub fn info(mode: &SleepMode, now: Instant, now_epoch_ms: u64) -> SleepInfo {
    match mode {
        SleepMode::Off => SleepInfo { mode: "off", ends_at_ms: None },
        SleepMode::EndOfSong { .. } => SleepInfo { mode: "end", ends_at_ms: None },
        SleepMode::At(deadline) => SleepInfo {
            mode: "timer",
            ends_at_ms: Some(now_epoch_ms + deadline.saturating_duration_since(now).as_millis() as u64),
        },
    }
}

/// The key of the song playing now, if any.
pub fn playing_key(app: &AppHandle) -> Option<String> {
    let app_state = app.state::<AppState>();
    let store = app_state.store.lock().unwrap();
    store.current().and_then(|s| s.track.as_ref()).map(song_key)
}

fn next_step(app: &AppHandle) -> SleepStep {
    let app_state = app.state::<AppState>();
    let mode = app_state.sleep.lock().unwrap().clone();
    if mode == SleepMode::Off {
        return SleepStep::Wait;
    }
    let store = app_state.store.lock().unwrap();
    let now = Instant::now();
    let state = store.current();
    let paused = state.is_some_and(|s| s.paused);
    let track = state.and_then(|s| s.track.as_ref());
    let key = track.map(song_key);
    let song = track.zip(key.as_deref()).map(|(t, key)| SongNow {
        key,
        position_ms: store.position_ms(now).unwrap_or(t.position_ms),
        duration_ms: t.duration_ms,
        is_stream: t.is_stream,
    });
    sleep_step(&mode, now, song, paused)
}

/// Checks twice a second; pauses the bot when the timer is due.
pub fn spawn_worker(app: AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(CHECK_EVERY);
        let step = next_step(&app);
        if step == SleepStep::Wait {
            continue;
        }
        if step == SleepStep::Pause {
            if let Err(e) = bridge::send_command(&app, BotCommand::Pause) {
                log::warn!("sleep timer could not pause: {e}");
            }
            dispatch_notice(&app, "info", "Sleep timer paused the music");
        }
        *app.state::<AppState>().sleep.lock().unwrap() = SleepMode::Off;
        mini_player::emit_sleep(&app);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    fn song(key: &str, position_ms: u64) -> SongNow<'_> {
        SongNow { key, position_ms, duration_ms: 200_000, is_stream: false }
    }

    #[test]
    fn timer_fires_at_deadline() {
        let t0 = Instant::now();
        let mode = SleepMode::At(t0 + Duration::from_secs(900));
        assert_eq!(sleep_step(&mode, t0 + Duration::from_secs(899), Some(song("a", 0)), false), SleepStep::Wait);
        assert_eq!(sleep_step(&mode, t0 + Duration::from_secs(900), Some(song("a", 0)), false), SleepStep::Pause);
        // Already paused: nothing to send, the timer just ends.
        assert_eq!(sleep_step(&mode, t0 + Duration::from_secs(901), Some(song("a", 0)), true), SleepStep::Reset);
        assert_eq!(sleep_step(&SleepMode::Off, t0, Some(song("a", 0)), false), SleepStep::Wait);
    }

    #[test]
    fn end_of_song_fires_near_the_end_only() {
        let t0 = Instant::now();
        let mode = SleepMode::EndOfSong { song: Some("a".into()) };
        assert_eq!(sleep_step(&mode, t0, Some(song("a", 100_000)), false), SleepStep::Wait);
        assert_eq!(sleep_step(&mode, t0, Some(song("a", 198_499)), false), SleepStep::Wait);
        assert_eq!(sleep_step(&mode, t0, Some(song("a", 198_500)), false), SleepStep::Pause);
        // Paused near the end: wait, it hasn't ended.
        assert_eq!(sleep_step(&mode, t0, Some(song("a", 199_000)), true), SleepStep::Wait);
        // Streams and unknown lengths never "end"; only a song change does.
        let stream = SongNow { key: "a", position_ms: 9_999_999, duration_ms: 0, is_stream: true };
        assert_eq!(sleep_step(&mode, t0, Some(stream), false), SleepStep::Wait);
    }

    #[test]
    fn end_of_song_stops_the_next_song_if_the_end_was_missed() {
        let t0 = Instant::now();
        let mode = SleepMode::EndOfSong { song: Some("a".into()) };
        // Skipped, or the 1.5 s window was missed: the song it was set on is over.
        assert_eq!(sleep_step(&mode, t0, Some(song("b", 300)), false), SleepStep::Pause);
        // The queue ran out: the music already stopped.
        assert_eq!(sleep_step(&mode, t0, None, false), SleepStep::Reset);
    }

    #[test]
    fn choices_parse() {
        let t0 = Instant::now();
        assert_eq!(parse_choice("off", t0, None), Some(SleepMode::Off));
        assert_eq!(parse_choice("30", t0, None), Some(SleepMode::At(t0 + Duration::from_secs(1800))));
        assert_eq!(parse_choice("end", t0, Some("a")), Some(SleepMode::EndOfSong { song: Some("a".into()) }));
        assert_eq!(parse_choice("7", t0, None), None);
        assert_eq!(info(&SleepMode::At(t0 + Duration::from_secs(60)), t0, 1_000), SleepInfo { mode: "timer", ends_at_ms: Some(61_000) });
        assert_eq!(info(&SleepMode::Off, t0, 1_000), SleepInfo { mode: "off", ends_at_ms: None });
    }
}
