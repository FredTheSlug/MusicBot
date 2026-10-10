//! The latest bot state as reported by the panel bridge, and what changed between reports.
//! Pure logic: no Tauri, Windows or network code, so it can be unit-tested with a fake clock.

use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};

/// A hidden window's timers can be throttled to once a minute, so this is deliberately lenient.
pub const STALE_AFTER: Duration = Duration::from_secs(180);

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Me {
    pub id: String,
    pub name: String,
    pub avatar_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Guild {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Track {
    pub title: String,
    pub author: String,
    pub artwork_url: Option<String>,
    pub uri: String,
    pub duration_ms: u64,
    pub position_ms: u64,
    pub is_stream: bool,
    pub requested_by: Option<String>,
    pub requested_by_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QueueHead {
    pub title: String,
    pub requested_by_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BotState {
    pub connected: bool,
    pub signed_in: bool,
    pub me: Option<Me>,
    pub guild: Option<Guild>,
    pub track: Option<Track>,
    pub paused: bool,
    pub volume: u8,
    pub queue_head: Option<QueueHead>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Change {
    /// `initial` is true for the first track seen after launch, a disconnect, or going stale,
    /// so consumers can skip "Now playing" toasts for a song that was already playing.
    TrackChanged { track: Track, initial: bool },
    PlaybackChanged { paused: bool },
    Cleared,
    QueueHeadChanged(Option<QueueHead>),
    ConnectionChanged { connected: bool, signed_in: bool },
}

fn track_key(track: &Track) -> (String, String) {
    (track.uri.clone(), track.title.clone())
}

fn head_key(head: &QueueHead) -> (String, Option<String>) {
    (head.title.clone(), head.requested_by_id.clone())
}

pub struct StateStore {
    state: Option<BotState>,
    last_report: Option<Instant>,
    stale: bool,
    initial_pending: bool,
    track_key: Option<(String, String)>,
    head_key: Option<(String, Option<String>)>,
}

impl StateStore {
    pub fn new() -> Self {
        StateStore { state: None, last_report: None, stale: false, initial_pending: true, track_key: None, head_key: None }
    }

    pub fn current(&self) -> Option<&BotState> {
        self.state.as_ref()
    }

    pub fn is_stale(&self) -> bool {
        self.stale
    }

    /// Forget the current track so the next one reported is treated as initial.
    fn lose_track(&mut self) {
        self.initial_pending = true;
        self.track_key = None;
    }

    pub fn apply(&mut self, next: BotState, now: Instant) -> Vec<Change> {
        let mut changes = Vec::new();
        let prev_connection = self.state.as_ref().map(|p| (p.connected && !self.stale, p.signed_in));
        if prev_connection != Some((next.connected, next.signed_in)) {
            changes.push(Change::ConnectionChanged { connected: next.connected, signed_in: next.signed_in });
        }

        if next.connected {
            let next_key = next.track.as_ref().map(track_key);
            if next_key != self.track_key {
                match &next.track {
                    Some(track) => {
                        changes.push(Change::TrackChanged { track: track.clone(), initial: self.initial_pending });
                        self.initial_pending = false;
                    }
                    None => changes.push(Change::Cleared),
                }
                self.track_key = next_key;
            } else if next.track.is_some() {
                let was_paused = self.state.as_ref().is_some_and(|p| p.paused);
                if was_paused != next.paused {
                    changes.push(Change::PlaybackChanged { paused: next.paused });
                }
            }

            let next_head = next.queue_head.as_ref().map(head_key);
            if next_head != self.head_key {
                changes.push(Change::QueueHeadChanged(next.queue_head.clone()));
                self.head_key = next_head;
            }
        } else {
            self.lose_track();
        }

        self.state = Some(next);
        self.last_report = Some(now);
        self.stale = false;
        changes
    }

    pub fn check_stale(&mut self, now: Instant) -> Vec<Change> {
        let Some(last) = self.last_report else { return vec![] };
        if self.stale || now.saturating_duration_since(last) <= STALE_AFTER {
            return vec![];
        }
        self.stale = true;
        self.lose_track();
        let signed_in = self.state.as_ref().is_some_and(|s| s.signed_in);
        vec![Change::ConnectionChanged { connected: false, signed_in }]
    }

    /// Current playback position, advanced from the last report while playing; never past the end.
    pub fn position_ms(&self, now: Instant) -> Option<u64> {
        let state = self.state.as_ref()?;
        let track = state.track.as_ref()?;
        if state.paused || track.is_stream || track.duration_ms == 0 {
            return Some(track.position_ms);
        }
        let elapsed = self.last_report.map_or(0, |at| now.saturating_duration_since(at).as_millis() as u64);
        Some(track.position_ms.saturating_add(elapsed).min(track.duration_ms))
    }
}

/// True when the next queued song was requested by the signed-in user.
pub fn is_my_song_next(head: Option<&QueueHead>, me: Option<&Me>) -> bool {
    match (head.and_then(|h| h.requested_by_id.as_deref()), me) {
        (Some(id), Some(me)) => id == me.id,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(title: &str, pos: u64) -> Track {
        Track {
            title: title.into(),
            author: "Artist".into(),
            artwork_url: None,
            uri: format!("yt:{title}"),
            duration_ms: 214_000,
            position_ms: pos,
            is_stream: false,
            requested_by: Some("fred".into()),
            requested_by_id: Some("1".into()),
        }
    }

    fn st(title: &str, paused: bool, pos: u64) -> BotState {
        BotState {
            connected: true,
            signed_in: true,
            me: Some(Me { id: "1".into(), name: "fred".into(), avatar_url: None }),
            guild: Some(Guild { id: "9".into(), name: "CK".into() }),
            track: Some(track(title, pos)),
            paused,
            volume: 100,
            queue_head: None,
        }
    }

    fn has_track_change(changes: &[Change], title: &str, initial: bool) -> bool {
        changes.iter().any(|c| matches!(c, Change::TrackChanged { track, initial: i } if track.title == title && *i == initial))
    }

    #[test]
    fn first_state_is_initial_track_change() {
        let mut store = StateStore::new();
        let changes = store.apply(st("A", false, 0), Instant::now());
        assert!(has_track_change(&changes, "A", true), "{changes:?}");
    }

    #[test]
    fn new_track_is_not_initial() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 0), t0);
        let changes = store.apply(st("B", false, 0), t0);
        assert!(has_track_change(&changes, "B", false), "{changes:?}");
    }

    #[test]
    fn same_track_progress_is_no_change() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 1000), t0);
        assert_eq!(store.apply(st("A", false, 9000), t0), vec![]);
    }

    #[test]
    fn pause_and_resume_emit_playback_changed() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 0), t0);
        assert_eq!(store.apply(st("A", true, 0), t0), vec![Change::PlaybackChanged { paused: true }]);
        assert_eq!(store.apply(st("A", false, 0), t0), vec![Change::PlaybackChanged { paused: false }]);
    }

    #[test]
    fn track_to_none_emits_cleared() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 0), t0);
        let idle = BotState { track: None, ..st("A", false, 0) };
        assert_eq!(store.apply(idle, t0), vec![Change::Cleared]);
    }

    #[test]
    fn sign_out_clears_track_and_emits_connection_change() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 0), t0);
        let signed_out = BotState { signed_in: false, track: None, me: None, ..st("A", false, 0) };
        let changes = store.apply(signed_out, t0);
        assert!(changes.contains(&Change::ConnectionChanged { connected: true, signed_in: false }), "{changes:?}");
        assert!(changes.contains(&Change::Cleared), "{changes:?}");
    }

    #[test]
    fn reconnect_marks_next_track_initial() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 0), t0);
        store.apply(BotState { connected: false, ..st("A", false, 0) }, t0);
        let changes = store.apply(st("B", false, 0), t0);
        assert!(has_track_change(&changes, "B", true), "{changes:?}");
    }

    #[test]
    fn queue_head_change_emitted_once_per_head() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        let head = QueueHead { title: "Next".into(), requested_by_id: Some("2".into()) };
        let with_head = || BotState { queue_head: Some(head.clone()), ..st("A", false, 0) };
        let first = store.apply(with_head(), t0);
        let second = store.apply(with_head(), t0);
        let count = |c: &[Change]| c.iter().filter(|c| matches!(c, Change::QueueHeadChanged(_))).count();
        assert_eq!(count(&first) + count(&second), 1);
    }

    #[test]
    fn stale_after_180s_marks_disconnected() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 0), t0);
        assert_eq!(store.check_stale(t0 + Duration::from_secs(179)), vec![]);
        assert!(!store.is_stale());
        assert_eq!(
            store.check_stale(t0 + Duration::from_secs(181)),
            vec![Change::ConnectionChanged { connected: false, signed_in: true }]
        );
        assert!(store.is_stale());
        assert_eq!(store.check_stale(t0 + Duration::from_secs(300)), vec![]);
        assert_eq!(STALE_AFTER, Duration::from_secs(180));
    }

    #[test]
    fn position_interpolates_and_clamps() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        store.apply(st("A", false, 85_000), t0);
        assert_eq!(store.position_ms(t0 + Duration::from_secs(10)), Some(95_000));
        assert_eq!(store.position_ms(t0 + Duration::from_secs(1000)), Some(214_000));
        store.apply(st("A", true, 85_000), t0);
        assert_eq!(store.position_ms(t0 + Duration::from_secs(10)), Some(85_000));
    }

    #[test]
    fn streams_and_zero_duration_do_not_interpolate() {
        let mut store = StateStore::new();
        let t0 = Instant::now();
        let mut live = st("Radio", false, 5_000);
        live.track.as_mut().unwrap().is_stream = true;
        store.apply(live, t0);
        assert_eq!(store.position_ms(t0 + Duration::from_secs(60)), Some(5_000));
        let mut zero = st("Zero", false, 1_000);
        zero.track.as_mut().unwrap().duration_ms = 0;
        store.apply(zero, t0);
        assert_eq!(store.position_ms(t0 + Duration::from_secs(60)), Some(1_000));
        assert_eq!(StateStore::new().position_ms(t0), None);
    }

    #[test]
    fn is_my_song_next_rules() {
        let me = Me { id: "1".into(), name: "fred".into(), avatar_url: None };
        let head = |id: Option<&str>| QueueHead { title: "Next".into(), requested_by_id: id.map(String::from) };
        assert!(is_my_song_next(Some(&head(Some("1"))), Some(&me)));
        assert!(!is_my_song_next(Some(&head(Some("1"))), None));
        assert!(!is_my_song_next(Some(&head(None)), Some(&me)));
        assert!(!is_my_song_next(Some(&head(Some("2"))), Some(&me)));
        assert!(!is_my_song_next(None, Some(&me)));
    }
}
