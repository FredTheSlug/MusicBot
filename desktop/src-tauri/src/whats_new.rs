//! Decides whether the app has just been updated, and how the person hears of it: the panel's own
//! "What's new" dialog, or a toast when the start doesn't show the panel.

use crate::notify::Toast;

/// How an update is announced at start.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Announce {
    Nothing,
    /// The panel is shown, and its page opens the patch notes it hasn't shown yet by itself.
    Panel,
    /// The panel stays in the tray (the mini-player alone, or Start with Windows): a toast says where What's new is
    /// instead of popping the panel up. The panel's page still has the notes waiting for when it is opened.
    Toast,
}

pub fn after_launch(updated: bool, panel_shown: bool) -> Announce {
    match (updated, panel_shown) {
        (false, _) => Announce::Nothing,
        (true, true) => Announce::Panel,
        (true, false) => Announce::Toast,
    }
}

pub fn update_toast(version: &str) -> Toast {
    Toast {
        title: format!("Clout Kings Music Bot updated to {version}"),
        body: "See What's new in the mini-player's \u{2630} menu or in the panel.".into(),
    }
}

/// `last_run` is the version stored in the settings, `current` the running app's version, and
/// `settings_existed` whether a settings file was already there before this start.
/// True when the version changed, or when none is stored although settings were (they come from an
/// app version older than this feature); false on a fresh install.
pub fn just_updated(last_run: Option<&str>, current: &str, settings_existed: bool) -> bool {
    match last_run {
        Some(last) => last != current,
        None => settings_existed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_version_is_not_an_update() {
        assert!(!just_updated(Some("0.4.0"), "0.4.0", true));
    }

    #[test]
    fn different_version_is_an_update() {
        assert!(just_updated(Some("0.4.0"), "0.5.0", true));
        // A downgrade changes the version too.
        assert!(just_updated(Some("0.5.0"), "0.4.0", true));
    }

    #[test]
    fn missing_with_existing_settings_is_an_update() {
        // Settings written by an app version from before lastRunVersion existed.
        assert!(just_updated(None, "0.5.0", true));
    }

    #[test]
    fn fresh_install_is_not() {
        assert!(!just_updated(None, "0.5.0", false));
    }

    #[test]
    fn after_an_update_the_panel_shows_its_own_whats_new() {
        // The panel's page opens the patch notes it hasn't shown yet by itself; showing it is enough.
        assert_eq!(after_launch(true, true), Announce::Panel);
    }

    #[test]
    fn after_an_update_without_the_panel_a_toast_tells_instead_of_popping_it_up() {
        assert_eq!(after_launch(true, false), Announce::Toast);
    }

    #[test]
    fn no_update_announces_nothing() {
        assert_eq!(after_launch(false, true), Announce::Nothing);
        assert_eq!(after_launch(false, false), Announce::Nothing);
    }

    #[test]
    fn the_toast_names_the_version_and_where_whats_new_is() {
        let toast = update_toast("0.6.1");
        assert!(toast.title.contains("0.6.1"), "{toast:?}");
        assert!(toast.body.contains("What's new"), "{toast:?}");
    }
}
