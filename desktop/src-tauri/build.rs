fn main() {
    // App commands must be listed here so capabilities can grant them one by one;
    // anything not granted to a window is rejected (the remote panel gets almost nothing).
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&[])),
    )
    .expect("failed to run tauri-build");
}
