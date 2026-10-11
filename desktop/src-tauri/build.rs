fn main() {
    // App commands must be listed here so capabilities can grant them one by one;
    // anything not granted to a window is rejected (the remote panel gets almost nothing).
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&[
            "bridge_report",
            "mini_get_state",
            "mini_command",
            "mini_open_panel",
            "mini_hide",
            "mini_spotify_now",
            "mini_send_spotify",
            "mini_play_spotify_text",
            "mini_search",
            "mini_lyrics",
            "mini_play_link",
            "mini_get_prefs",
            "mini_set_opacity",
            "mini_set_theme",
            "mini_set_panel",
            "mini_history",
            "mini_frequent",
            "mini_playlists",
            "mini_play_playlist",
        ])),
    )
    .expect("failed to run tauri-build");
}
