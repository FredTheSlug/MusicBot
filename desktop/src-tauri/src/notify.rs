//! Windows toasts: "Now playing" when the song changes and "Your song is up next".

use crate::settings::Settings;
use crate::state::{is_my_song_next, BotState, Change, Track};
use tauri::AppHandle;
use tauri_plugin_notification::NotificationExt;

#[derive(Debug, Clone, PartialEq)]
pub struct Toast {
    pub title: String,
    pub body: String,
}

fn now_playing_body(track: &Track) -> String {
    match (track.author.is_empty(), track.requested_by.as_deref()) {
        (false, Some(by)) => format!("{} · requested by {by}", track.author),
        (true, Some(by)) => format!("requested by {by}"),
        _ => track.author.clone(),
    }
}

/// Which toasts a batch of state changes should produce.
/// "Now playing" stays quiet for the song already playing at launch/reconnect and while the
/// panel or mini-player is in view; "up next" is personal, so it always shows.
pub fn toasts_for(changes: &[Change], state: Option<&BotState>, settings: &Settings, main_focused: bool, mini_visible: bool) -> Vec<Toast> {
    let me = state.and_then(|s| s.me.as_ref());
    changes
        .iter()
        .filter_map(|change| match change {
            Change::TrackChanged { track, initial: false } if settings.notify_now_playing && !main_focused && !mini_visible => {
                Some(Toast { title: track.title.clone(), body: now_playing_body(track) })
            }
            Change::QueueHeadChanged(Some(head)) if settings.notify_up_next && is_my_song_next(Some(head), me) => {
                Some(Toast { title: "Your song is up next".into(), body: head.title.clone() })
            }
            _ => None,
        })
        .collect()
}

pub fn show(app: &AppHandle, toast: &Toast) {
    if let Err(e) = app.notification().builder().title(&toast.title).body(&toast.body).show() {
        log::warn!("could not show notification: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::{Me, QueueHead, Track};

    fn track(requested_by: Option<&str>) -> Track {
        Track { title: "Small Worlds".into(), author: "Mac Miller".into(), requested_by: requested_by.map(String::from), ..Track::default() }
    }

    fn state_with_me() -> BotState {
        BotState { connected: true, signed_in: true, me: Some(Me { id: "1".into(), name: "fred".into(), avatar_url: None }), ..BotState::default() }
    }

    fn head(id: Option<&str>) -> QueueHead {
        QueueHead { title: "Locket".into(), requested_by_id: id.map(String::from) }
    }

    #[test]
    fn track_change_toasts_title_and_artist_with_requester() {
        let changes = [Change::TrackChanged { track: track(Some("gunner")), initial: false }];
        assert_eq!(
            toasts_for(&changes, Some(&state_with_me()), &Settings::default(), false, false),
            vec![Toast { title: "Small Worlds".into(), body: "Mac Miller · requested by gunner".into() }]
        );
    }

    #[test]
    fn requester_missing_body_is_artist_only() {
        let changes = [Change::TrackChanged { track: track(None), initial: false }];
        let toasts = toasts_for(&changes, Some(&state_with_me()), &Settings::default(), false, false);
        assert_eq!(toasts[0].body, "Mac Miller");
    }

    #[test]
    fn initial_track_change_does_not_toast() {
        let changes = [Change::TrackChanged { track: track(Some("gunner")), initial: true }];
        assert_eq!(toasts_for(&changes, Some(&state_with_me()), &Settings::default(), false, false), vec![]);
    }

    #[test]
    fn focus_or_mini_visible_suppresses_now_playing() {
        let changes = [Change::TrackChanged { track: track(None), initial: false }];
        assert_eq!(toasts_for(&changes, Some(&state_with_me()), &Settings::default(), true, false), vec![]);
        assert_eq!(toasts_for(&changes, Some(&state_with_me()), &Settings::default(), false, true), vec![]);
    }

    #[test]
    fn now_playing_toggle_off() {
        let changes = [Change::TrackChanged { track: track(None), initial: false }];
        let off = Settings { notify_now_playing: false, ..Settings::default() };
        assert_eq!(toasts_for(&changes, Some(&state_with_me()), &off, false, false), vec![]);
    }

    #[test]
    fn my_song_up_next_toasts() {
        let changes = [Change::QueueHeadChanged(Some(head(Some("1"))))];
        let expected = vec![Toast { title: "Your song is up next".into(), body: "Locket".into() }];
        assert_eq!(toasts_for(&changes, Some(&state_with_me()), &Settings::default(), false, false), expected);
        assert_eq!(toasts_for(&changes, Some(&state_with_me()), &Settings::default(), true, false), expected);
    }

    #[test]
    fn up_next_not_mine_or_toggle_off_or_no_me() {
        let not_mine = [Change::QueueHeadChanged(Some(head(Some("2"))))];
        assert_eq!(toasts_for(&not_mine, Some(&state_with_me()), &Settings::default(), false, false), vec![]);
        let mine = [Change::QueueHeadChanged(Some(head(Some("1"))))];
        let off = Settings { notify_up_next: false, ..Settings::default() };
        assert_eq!(toasts_for(&mine, Some(&state_with_me()), &off, false, false), vec![]);
        let no_me = BotState { me: None, ..state_with_me() };
        assert_eq!(toasts_for(&mine, Some(&no_me), &Settings::default(), false, false), vec![]);
        assert_eq!(toasts_for(&[Change::QueueHeadChanged(None)], Some(&state_with_me()), &Settings::default(), false, false), vec![]);
    }
}
