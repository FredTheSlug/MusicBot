//! Decides whether the app has just been updated, so it can show its window once for the panel's
//! "What's new" dialog.

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
}
