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
            "mini_play_spotify_clipboard",
            "mini_search",
            "mini_lyrics",
            "mini_play_link",
        ])),
    )
    .expect("failed to run tauri-build");
}
