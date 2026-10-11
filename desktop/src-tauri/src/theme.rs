//! The panel's theme as the mini-player uses it. Values come from the remote page, so only known
//! variable names and plain CSS values (colors, gradients, font lists) get through.

use crate::settings::Settings;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The panel's themes, in the order its theme picker lists them.
pub const THEME_IDS: [&str; 17] = [
    "nebula", "aurora", "ember", "crimson", "dawn", "dawn-dark", "steel", "verdant", "grove", "grapevine", "sumi", "rasta",
    "cipher", "signal", "victory", "neon", "revival",
];

pub const THEME_VARS: [&str; 16] = [
    "--bg-primary", "--bg-secondary", "--bg-card", "--bg-elevated", "--bg-hover", "--accent", "--accent-light",
    "--accent-glow", "--accent-gradient", "--text-primary", "--text-secondary", "--text-tertiary", "--border",
    "--border-light", "--success", "--font-family",
];

const MAX_VALUE_CHARS: usize = 200;
const VALUE_PUNCTUATION: &str = " #(),.%'\"_+-";

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct PanelTheme {
    pub id: String,
    pub vars: BTreeMap<String, String>,
}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 32 && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// Colors, gradients and font lists only: no `;` `{}` `<>` `/` `:` that could end the value or
/// start another rule, and no `url(` that could load something remote.
fn valid_value(value: &str) -> bool {
    value.chars().count() <= MAX_VALUE_CHARS
        && value.chars().all(|c| c.is_ascii_alphanumeric() || VALUE_PUNCTUATION.contains(c))
        && !value.to_ascii_lowercase().contains("url(")
}

/// None when the id is unusable; otherwise the theme with every unknown name or unsafe value dropped.
pub fn sanitize(raw: PanelTheme) -> Option<PanelTheme> {
    if !valid_id(&raw.id) {
        return None;
    }
    let vars = raw
        .vars
        .into_iter()
        .filter(|(name, value)| THEME_VARS.contains(&name.as_str()) && valid_value(value))
        .collect();
    Some(PanelTheme { id: raw.id, vars })
}

/// What the mini-player shows: the panel's theme when syncing (Nebula until one is seen), else the
/// user's own pick with no live colors (the mini-player's built-in ones apply).
pub fn effective(settings: &Settings) -> PanelTheme {
    if settings.mini_player_match_panel {
        settings.panel_theme.clone().unwrap_or_else(|| PanelTheme { id: THEME_IDS[0].into(), vars: BTreeMap::new() })
    } else {
        PanelTheme { id: settings.mini_player_theme.clone(), vars: BTreeMap::new() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::Settings;

    fn theme(id: &str, vars: &[(&str, &str)]) -> PanelTheme {
        PanelTheme { id: id.into(), vars: vars.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect() }
    }

    #[test]
    fn sanitize_keeps_colors_gradients_and_fonts() {
        let raw = theme(
            "cipher",
            &[
                ("--accent", "#7c5cfc"),
                ("--accent-glow", "rgba(124, 92, 252, 0.3)"),
                ("--accent-gradient", "linear-gradient(135deg, #7c5cfc, #e04cf0)"),
                ("--font-family", "'IBM Plex Mono', ui-monospace, monospace"),
            ],
        );
        assert_eq!(sanitize(raw.clone()), Some(raw));
    }

    #[test]
    fn sanitize_rejects_injection() {
        let long = "a".repeat(201);
        let raw = theme(
            "neon",
            &[
                ("--bg-card", "url(https://x/y.png)"),
                ("--bg-primary", "URL(x)"),
                ("--accent", "red; background: url(x)"),
                ("--accent-light", "red}"),
                ("--text-primary", "<b>"),
                ("--text-secondary", &long),
                ("--evil", "#fff"),
                ("--success", "#34d399"),
            ],
        );
        assert_eq!(sanitize(raw), Some(theme("neon", &[("--success", "#34d399")])));
    }

    #[test]
    fn sanitize_rejects_bad_ids() {
        for id in ["Neon", "neon theme", "", &"a".repeat(33)] {
            assert_eq!(sanitize(theme(id, &[])), None, "{id:?}");
        }
        assert!(sanitize(theme("dawn-dark", &[])).is_some());
    }

    #[test]
    fn effective_follows_match_setting() {
        let seen = theme("neon", &[("--accent", "#0ff")]);
        let synced = Settings { panel_theme: Some(seen.clone()), ..Settings::default() };
        assert_eq!(effective(&synced), seen);
        assert_eq!(effective(&Settings::default()), theme("nebula", &[]));
        let own = Settings { mini_player_match_panel: false, mini_player_theme: "ember".into(), ..synced };
        assert_eq!(effective(&own), theme("ember", &[]));
    }

    #[test]
    fn theme_lists_match_the_panel() {
        assert_eq!(THEME_IDS.len(), 17);
        assert_eq!(THEME_IDS[0], "nebula");
        assert_eq!(THEME_IDS[16], "revival");
        assert_eq!(THEME_VARS.len(), 16);
    }
}
