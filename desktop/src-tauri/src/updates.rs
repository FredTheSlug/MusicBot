//! Auto-update from GitHub Releases (signed with the Tauri updater key). Always asks first.

use tauri::AppHandle;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::UpdaterExt;

fn tell(app: &AppHandle, kind: MessageDialogKind, message: String) {
    app.dialog().message(message).title("Clout Kings Music Bot").kind(kind).show(|_| {});
}

/// `interactive` = the user clicked "Check for updates": show "up to date" and errors too.
/// The startup check stays silent unless there is an update to offer.
pub fn check_for_updates(app: &AppHandle, interactive: bool) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let checked = match app.updater() {
            Ok(updater) => updater.check().await,
            Err(e) => Err(e),
        };
        match checked {
            Ok(Some(update)) => {
                let question = format!(
                    "Version {} is available (you have {}).\n\nInstall it now? The app will restart.",
                    update.version, update.current_version
                );
                let app_for_install = app.clone();
                app.dialog()
                    .message(question)
                    .title("Update available")
                    .buttons(MessageDialogButtons::OkCancelCustom("Install".into(), "Later".into()))
                    .show(move |install| {
                        if !install {
                            return;
                        }
                        tauri::async_runtime::spawn(async move {
                            match update.download_and_install(|_, _| {}, || {}).await {
                                Ok(()) => app_for_install.restart(),
                                Err(e) => tell(&app_for_install, MessageDialogKind::Error, format!("The update failed: {e}")),
                            }
                        });
                    });
            }
            Ok(None) => {
                if interactive {
                    let version = app.package_info().version.to_string();
                    tell(&app, MessageDialogKind::Info, format!("You're up to date (version {version})."));
                }
            }
            Err(e) => {
                log::warn!("update check failed: {e}");
                if interactive {
                    tell(&app, MessageDialogKind::Error, format!("Couldn't check for updates: {e}"));
                }
            }
        }
    });
}
