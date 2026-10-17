//! The panel's theme as the mini-player uses it. Values come from the remote page, so only known
//! variable names and plain CSS values (colors, gradients, font lists) get through. Custom themes
//! people made in the panel come from the bot's `GET /api/themes`: only the ones with a mini-player
//! version are kept, every color checked, and the list is the only place their values live. Which of
//! their base theme's decoration pieces are off comes from the repository's one pieces list.

use crate::settings::Settings;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, HashSet};
use std::sync::OnceLock;

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

/// The fonts a theme may use. Inter is the one the mini-player falls back to.
pub const FONTS: [&str; 8] =
    ["Inter", "DM Sans", "Outfit", "Syne", "Fraunces", "IBM Plex Mono", "Noto Sans JP", "Shippori Mincho"];

/// The colors a mini-player version needs: a theme's colors without `glass` and the extra decoration color.
pub const MINI_SLOTS: [&str; 14] = [
    "bgPrimary", "bgSecondary", "bgCard", "bgElevated", "bgHover", "accent", "accentLight", "accent2", "textPrimary",
    "textSecondary", "textTertiary", "border", "borderLight", "success",
];

/// The mini-player version's 15th color, the extra decoration color (`--deco-extra`): only when the bot sends a usable
/// one. A bot from before the pieces sends none, and then the base skin's own shows.
pub const DECO_EXTRA: &str = "decoExtra";

/// The pieces of every built-in theme's decorations, as the bot lists them for the panel, the theme creator and itself:
/// the repository's one list, built into the app.
const THEME_PIECES_JSON: &str = include_str!("../../../src/main/resources/web/theme-pieces.json");

/// One theme's entry in the pieces list (its `decoExtra` and the labels are the panel's business).
#[derive(Debug, Deserialize)]
struct ThemePieces {
    pieces: Vec<Piece>,
}

#[derive(Debug, Deserialize)]
struct Piece {
    id: String,
    /// Paints the background: off while a custom theme shows its picture.
    background: bool,
}

/// The pieces list, read once.
fn theme_pieces() -> &'static BTreeMap<String, ThemePieces> {
    static PIECES: OnceLock<BTreeMap<String, ThemePieces>> = OnceLock::new();
    PIECES.get_or_init(|| serde_json::from_str(THEME_PIECES_JSON).expect("theme-pieces.json is the repository's own list"))
}

/// A theme's pieces; none for a theme the list doesn't have.
fn pieces_of(base: &str) -> &'static [Piece] {
    theme_pieces().get(base).map_or(&[], |t| t.pieces.as_slice())
}

/// The most custom themes the mini-player lists.
pub const MAX_CUSTOM_MINIS: usize = 200;
const MAX_NAME_CHARS: usize = 40;
const MAX_VALUE_CHARS: usize = 200;
const VALUE_PUNCTUATION: &str = " #(),.%'\"_+-";
const CUSTOM_PREFIX: &str = "custom:";
const UNTITLED: &str = "Untitled theme";
const UNKNOWN_PERSON: &str = "someone";

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct PanelTheme {
    /// The skin the panel wears: the built-in theme's id, or the base theme of a custom one.
    pub id: String,
    pub vars: BTreeMap<String, String>,
    /// What was picked in the panel: the built-in id, or `custom:<id>`. None from a panel report that predates it.
    pub choice: Option<String>,
}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 32 && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// A custom theme's id: 12 of `[a-z0-9]`.
fn valid_custom_id(id: &str) -> bool {
    id.len() == 12 && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
}

/// The id in `custom:<id>`, or None for anything else (including a well-formed prefix with a bad id).
fn custom_id(choice: &str) -> Option<&str> {
    choice.strip_prefix(CUSTOM_PREFIX).filter(|id| valid_custom_id(id))
}

/// A theme the person can pick: one of the 17 built-in ids, or `custom:<12 [a-z0-9]>`.
pub fn valid_choice(choice: &str) -> bool {
    THEME_IDS.contains(&choice) || custom_id(choice).is_some()
}

/// The built-in skin to wear for this id: itself, or Nebula when this app doesn't have it.
fn known_or_nebula(id: &str) -> String {
    if THEME_IDS.contains(&id) { id.to_string() } else { THEME_IDS[0].to_string() }
}

/// Colors, gradients and font lists only: no `;` `{}` `<>` `/` `:` that could end the value or
/// start another rule, and no `url(` that could load something remote.
fn valid_value(value: &str) -> bool {
    value.chars().count() <= MAX_VALUE_CHARS
        && value.chars().all(|c| c.is_ascii_alphanumeric() || VALUE_PUNCTUATION.contains(c))
        && !value.to_ascii_lowercase().contains("url(")
}

/// None when the id is unusable; otherwise the theme with every unknown name or unsafe value dropped
/// (and a choice that isn't a real one).
pub fn sanitize(raw: PanelTheme) -> Option<PanelTheme> {
    if !valid_id(&raw.id) {
        return None;
    }
    let vars = raw
        .vars
        .into_iter()
        .filter(|(name, value)| THEME_VARS.contains(&name.as_str()) && valid_value(value))
        .collect();
    Some(PanelTheme { id: raw.id, vars, choice: raw.choice.filter(|c| valid_choice(c)) })
}

// ---- Custom themes with a mini-player version ----

/// A custom theme's mini-player version, as the bot's list gives it and the mini-player shows it.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CustomMini {
    pub id: String,
    pub name: String,
    /// The creator's name.
    pub owner: String,
    /// One of yours (My themes), else Community.
    pub mine: bool,
    /// Private, or hidden by the bot owner: only you can use it.
    pub private: bool,
    /// The starting theme: the built-in skin the version wears with its own colors (alone when they can't be used).
    pub base: String,
    pub font: String,
    /// The 14 colors, `#rrggbb` or `#rrggbbaa`, by slot name, and `decoExtra` when the bot sent a usable one.
    pub colors: BTreeMap<String, String>,
    /// How much to darken the picture, 0-90.
    pub dim: u8,
    /// The picture's path on the bot (`/theme-images/<32 hex>.<ext>`): set only when this version uses the theme's picture.
    pub picture: Option<String>,
    /// The base's pieces switched off, for the mini-player's `data-deco-off`: the theme's `decorationsOff` (only the
    /// base's own ids, once each, in order), and with a picture the base's background pieces too.
    pub decorations_off: Vec<String>,
}

/// `#rrggbb` or `#rrggbbaa`, in either case.
fn valid_color(color: &str) -> bool {
    let b = color.as_bytes();
    (b.len() == 7 || b.len() == 9) && b[0] == b'#' && b[1..].iter().all(u8::is_ascii_hexdigit)
}

/// The bot's own picture link, exactly as it makes it: `/theme-images/<32 lowercase hex>.<jpg|png|webp|gif>`.
fn valid_picture_path(path: &str) -> bool {
    let Some(name) = path.strip_prefix("/theme-images/") else { return false };
    let Some((stem, ext)) = name.split_once('.') else { return false };
    stem.len() == 32
        && stem.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        && ["jpg", "png", "webp", "gif"].contains(&ext)
}

/// Direction controls could reorder the text around a name.
fn is_bidi_control(c: char) -> bool {
    matches!(c, '\u{061C}' | '\u{200E}' | '\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}')
}

/// A name or a person's name as shown: no control or direction characters, trimmed, at most 40 characters
/// (cut between characters, never inside one), `fallback` when nothing is left.
fn clean_name(value: Option<&Value>, fallback: &str) -> String {
    let kept: String = value.and_then(Value::as_str).unwrap_or("").chars().filter(|c| !c.is_control() && !is_bidi_control(*c)).collect();
    let cut: String = kept.trim().chars().take(MAX_NAME_CHARS).collect();
    let cut = cut.trim_end();
    if cut.is_empty() { fallback.to_string() } else { cut.to_string() }
}

/// The base's pieces a theme switches off: its `decorationsOff` (the base's own ids only, once each, in order; a bot
/// from before the pieces sends none, and its `effects` is the bot's to turn into pieces), then with a picture the base's
/// background pieces.
fn decorations_off(theme: &Value, base: &str, has_picture: bool) -> Vec<String> {
    let pieces = pieces_of(base);
    let mut off: Vec<String> = Vec::new();
    let listed = theme.get("decorationsOff").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str);
    let pictured = pieces.iter().filter(|p| has_picture && p.background).map(|p| p.id.as_str());
    for id in listed.chain(pictured) {
        if pieces.iter().any(|p| p.id == id) && !off.iter().any(|o| o == id) {
            off.push(id.to_string());
        }
    }
    off
}

/// One theme from the list, or None when it has no mini-player version or anything about it is unusable.
fn parse_mini(theme: &Value) -> Option<CustomMini> {
    let id = theme.get("id")?.as_str().filter(|id| valid_custom_id(id))?;
    let mini = theme.get("mini")?.as_object()?;
    let base = theme.get("base")?.as_str().filter(|base| THEME_IDS.contains(base))?;
    let raw_colors = mini.get("colors")?.as_object()?;
    let mut colors = BTreeMap::new();
    for slot in MINI_SLOTS {
        let color = raw_colors.get(slot)?.as_str().filter(|c| valid_color(c))?;
        colors.insert(slot.to_string(), color.to_string());
    }
    if let Some(extra) = raw_colors.get(DECO_EXTRA).and_then(Value::as_str).filter(|c| valid_color(c)) {
        colors.insert(DECO_EXTRA.to_string(), extra.to_string());
    }
    let uses_picture = mini.get("useImage").and_then(Value::as_bool) == Some(true);
    let picture = if uses_picture {
        theme.get("background").and_then(|b| b.get("url")).and_then(Value::as_str).filter(|url| valid_picture_path(url)).map(str::to_string)
    } else {
        None
    };
    let flag = |key: &str| theme.get(key).and_then(Value::as_bool) == Some(true);
    Some(CustomMini {
        id: id.to_string(),
        name: clean_name(theme.get("name"), UNTITLED),
        owner: clean_name(theme.get("owner").and_then(|o| o.get("name")), UNKNOWN_PERSON),
        mine: flag("mine"),
        private: theme.get("visibility").and_then(Value::as_str) == Some("private") || flag("hiddenByOwner"),
        base: base.to_string(),
        font: theme.get("font").and_then(Value::as_str).filter(|f| FONTS.contains(f)).unwrap_or(FONTS[0]).to_string(),
        colors,
        dim: mini.get("dim").and_then(Value::as_f64).map_or(0, |d| d.round().clamp(0.0, 90.0) as u8),
        decorations_off: decorations_off(theme, base, picture.is_some()),
        picture,
    })
}

/// The themes of `GET /api/themes` that have a mini-player version: Community (other people's) first, then My themes,
/// each by name. A theme with any bad color, an id the panel wouldn't make or a base it doesn't know is left out.
/// At most 200: your own themes stay, community themes fill the rest.
pub fn custom_minis(data: &Value) -> Vec<CustomMini> {
    let Some(themes) = data.get("themes").and_then(Value::as_array) else { return Vec::new() };
    let mut seen = HashSet::new();
    let (mut mine, mut community): (Vec<CustomMini>, Vec<CustomMini>) =
        themes.iter().filter_map(parse_mini).filter(|t| seen.insert(t.id.clone())).partition(|t| t.mine);
    let by_name = |a: &CustomMini, b: &CustomMini| a.name.to_lowercase().cmp(&b.name.to_lowercase()).then_with(|| a.id.cmp(&b.id));
    mine.sort_by(by_name);
    community.sort_by(by_name);
    mine.truncate(MAX_CUSTOM_MINIS);
    community.truncate(MAX_CUSTOM_MINIS - mine.len());
    community.extend(mine);
    community
}

// ---- Which skin the mini-player wears ----

/// What the mini-player wears.
#[derive(Debug, Clone, PartialEq)]
pub enum MiniThemeChoice {
    /// A built-in skin; `vars` are the panel's live values laid over it (none when it stands in for a custom theme).
    Builtin { id: String, vars: BTreeMap<String, String> },
    /// A custom theme's mini-player version.
    Custom(CustomMini),
}

/// What the panel (or the person's own pick, shaped the same way) shows, in the skin the mini-player wears:
/// a custom theme with a mini-player version wears it; one without wears its base theme's hand-tuned skin, as does
/// one that is gone or not known yet (Nebula when the base is unknown too). A built-in theme wears its skin with the
/// panel's live colors on it.
pub fn resolve_mini_theme(panel: &PanelTheme, customs: &[CustomMini]) -> MiniThemeChoice {
    match panel.choice.as_deref().and_then(custom_id) {
        Some(id) => match customs.iter().find(|c| c.id == id) {
            Some(found) => MiniThemeChoice::Custom(found.clone()),
            None => MiniThemeChoice::Builtin { id: known_or_nebula(&panel.id), vars: BTreeMap::new() },
        },
        None => MiniThemeChoice::Builtin { id: panel.id.clone(), vars: panel.vars.clone() },
    }
}

/// The person's own pick, shaped like a report from the panel.
fn own_pick(settings: &Settings) -> PanelTheme {
    if custom_id(&settings.mini_player_theme).is_some() {
        PanelTheme { id: settings.mini_player_theme_base.clone(), vars: BTreeMap::new(), choice: Some(settings.mini_player_theme.clone()) }
    } else {
        PanelTheme { id: known_or_nebula(&settings.mini_player_theme), vars: BTreeMap::new(), choice: None }
    }
}

/// What the mini-player shows: the panel's theme when syncing (Nebula until one is seen), else the
/// user's own pick with no live colors (the mini-player's built-in ones apply).
pub fn effective(settings: &Settings, customs: &[CustomMini]) -> MiniThemeChoice {
    let followed = if settings.mini_player_match_panel {
        settings.panel_theme.clone().unwrap_or_else(|| PanelTheme { id: THEME_IDS[0].into(), ..PanelTheme::default() })
    } else {
        own_pick(settings)
    };
    resolve_mini_theme(&followed, customs)
}

/// The value the theme menu shows for the person's own pick: the pick, or for a custom theme that is no longer
/// offered the base skin that stands in for it.
pub fn menu_selection(theme: &str, base: &str, customs: &[CustomMini]) -> String {
    if THEME_IDS.contains(&theme) {
        return theme.to_string();
    }
    match custom_id(theme) {
        Some(id) if customs.iter().any(|c| c.id == id) => theme.to_string(),
        Some(_) => known_or_nebula(base),
        None => THEME_IDS[0].to_string(),
    }
}

/// Takes what the person did in the theme settings: the match switch, and `theme` when they picked one in the menu
/// (None when only the switch was flipped: the stored pick stays exactly as it is, since the menu may be showing a
/// stand-in for a custom theme that isn't in the list right now). A custom theme is remembered with its base theme,
/// for when it is gone; something that is not a theme keeps the current pick.
pub fn choose(settings: &mut Settings, match_panel: bool, theme: Option<&str>, customs: &[CustomMini]) {
    settings.mini_player_match_panel = match_panel;
    let Some(theme) = theme else { return };
    if THEME_IDS.contains(&theme) {
        settings.mini_player_theme = theme.to_string();
        settings.mini_player_theme_base = theme.to_string();
    } else if let Some(id) = custom_id(theme) {
        let base = match customs.iter().find(|c| c.id == id) {
            Some(found) => found.base.clone(),
            // Not on offer right now (not loaded yet, the bot is away): the pick that is already stored keeps its base.
            None if settings.mini_player_theme == theme => known_or_nebula(&settings.mini_player_theme_base),
            None => THEME_IDS[0].to_string(),
        };
        settings.mini_player_theme = theme.to_string();
        settings.mini_player_theme_base = base;
    }
}

/// Should the list of custom themes be asked for again after the panel reported its theme? Yes when the theme
/// changed, and also while the list has never loaded: the first ask can come before the panel's bridge exists (the
/// mini-player shown at startup), and a panel that then reports the very theme it had last session changes nothing.
pub fn needs_refresh(report_changed: bool, list_loaded: bool) -> bool {
    report_changed || !list_loaded
}

/// What the mini-player's UI is told to wear: the skin, the panel's live values for it, and a custom version.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct UiTheme {
    /// The built-in skin; for a custom version its base theme, which it wears in its own colors with its pieces (and
    /// which shows as it is if those colors can't be used).
    pub id: String,
    pub vars: BTreeMap<String, String>,
    pub custom: Option<CustomMini>,
}

impl MiniThemeChoice {
    /// For the UI. `origin` is the bot's address (`scheme://host[:port]`), which a custom picture's path is joined to;
    /// without one the picture is dropped.
    pub fn into_ui(self, origin: Option<&str>) -> UiTheme {
        match self {
            MiniThemeChoice::Builtin { id, vars } => UiTheme { id, vars, custom: None },
            MiniThemeChoice::Custom(mut custom) => {
                custom.picture = custom.picture.take().and_then(|path| origin.map(|o| format!("{o}{path}")));
                UiTheme { id: custom.base.clone(), vars: BTreeMap::new(), custom: Some(custom) }
            }
        }
    }
}

/// The theme menu: the custom themes on offer, and the value that is selected.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ThemeMenu {
    pub custom_themes: Vec<CustomMini>,
    pub theme: String,
}

pub fn theme_menu(settings: &Settings, customs: &[CustomMini]) -> ThemeMenu {
    ThemeMenu {
        custom_themes: customs.to_vec(),
        theme: menu_selection(&settings.mini_player_theme, &settings.mini_player_theme_base, customs),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::Settings;
    use serde_json::json;

    fn theme(id: &str, vars: &[(&str, &str)]) -> PanelTheme {
        PanelTheme { id: id.into(), vars: vars.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(), choice: None }
    }

    fn panel(id: &str, choice: &str, vars: &[(&str, &str)]) -> PanelTheme {
        PanelTheme { choice: Some(choice.into()), ..theme(id, vars) }
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
    fn sanitize_keeps_a_real_choice_and_drops_anything_else() {
        for choice in ["nebula", "dawn-dark", "custom:k3j9x0q2m7ab"] {
            assert_eq!(sanitize(panel("ember", choice, &[])), Some(panel("ember", choice, &[])), "{choice}");
        }
        for choice in ["custom:K3J9X0Q2M7AB", "custom:short", "custom:k3j9x0q2m7ab1", "custom:", "Neon", "a b", "url(x)", "custom:../../etc/x", ""] {
            assert_eq!(sanitize(panel("ember", choice, &[])), Some(theme("ember", &[])), "{choice:?}");
        }
        // a report from before the choice existed
        let old: PanelTheme = serde_json::from_str(r##"{"id":"neon","vars":{"--accent":"#0ff"}}"##).unwrap();
        assert_eq!(old.choice, None);
        assert_eq!(sanitize(old).unwrap().id, "neon");
    }

    #[test]
    fn theme_lists_match_the_panel() {
        assert_eq!(THEME_IDS.len(), 17);
        assert_eq!(THEME_IDS[0], "nebula");
        assert_eq!(THEME_IDS[16], "revival");
        assert_eq!(THEME_VARS.len(), 16);
        assert_eq!(FONTS.len(), 8);
        assert_eq!(FONTS[0], "Inter");
        assert_eq!(MINI_SLOTS.len(), 14);
        assert!(!MINI_SLOTS.contains(&"glass"));
    }

    // ---- custom themes with a mini-player version, from GET /api/themes ----

    fn mini_colors() -> serde_json::Value {
        json!({
            "bgPrimary": "#0b0d14", "bgSecondary": "#10131c", "bgCard": "#161a26", "bgElevated": "#1c2131", "bgHover": "#262c42",
            "accent": "#7c5cff", "accentLight": "#a08bff", "accent2": "#22d3ee", "textPrimary": "#eef0f8", "textSecondary": "#9aa0b8",
            "textTertiary": "#646a82", "border": "#ffffff12", "borderLight": "#ffffff1f", "success": "#34d399"
        })
    }

    fn api_theme(id: &str, name: &str, mine: bool) -> serde_json::Value {
        json!({
            "id": id, "name": name, "owner": { "id": "233362518045753345", "name": "fred" }, "mine": mine, "visibility": "public",
            "hiddenByOwner": false, "base": "ember", "font": "Outfit", "effects": false, "radiusScale": 100, "version": 3,
            "colors": mini_colors(), "background": null,
            "mini": { "colors": mini_colors(), "useImage": false, "dim": 45 }
        })
    }

    fn list(themes: Vec<serde_json::Value>) -> serde_json::Value {
        json!({ "themes": themes, "me": { "id": "1", "isOwner": false }, "limit": { "max": 10, "used": 1 } })
    }

    const PIC: &str = "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp";

    #[test]
    fn custom_minis_keeps_only_themes_with_a_mini_player_version() {
        let mut without = api_theme("bbbbbbbbbbbb", "No mini", false);
        without["mini"] = serde_json::Value::Null;
        let mut missing = api_theme("cccccccccccc", "Absent", false);
        missing.as_object_mut().unwrap().remove("mini");
        let got = custom_minis(&list(vec![api_theme("aaaaaaaaaaaa", "Has one", false), without, missing]));
        assert_eq!(got.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), ["aaaaaaaaaaaa"]);
        let t = &got[0];
        assert_eq!((t.name.as_str(), t.owner.as_str(), t.base.as_str(), t.font.as_str()), ("Has one", "fred", "ember", "Outfit"));
        assert_eq!(t.colors.len(), 14);
        assert_eq!(t.colors["accent"], "#7c5cff");
        assert_eq!(t.colors["border"], "#ffffff12");
        assert_eq!((t.dim, t.mine, t.private, t.picture.as_deref()), (45, false, false, None));
    }

    #[test]
    fn custom_minis_skip_a_theme_with_any_bad_color_and_keep_the_rest() {
        let bad_values = [json!("red"), json!("#fff"), json!("#12345g"), json!(""), json!(5), json!(null), json!("url(https://x/y)"),
            json!("#1234567"), json!("#7c5cff; x"), json!(" #7c5cff"), json!("#7c5cff\n"), json!("rgb(1,2,3)")];
        for slot in MINI_SLOTS {
            for bad in &bad_values {
                let mut t = api_theme("bbbbbbbbbbbb", "Bad", false);
                t["mini"]["colors"][slot] = bad.clone();
                let got = custom_minis(&list(vec![api_theme("aaaaaaaaaaaa", "Good", false), t]));
                assert_eq!(got.len(), 1, "{slot} = {bad}");
                assert_eq!(got[0].id, "aaaaaaaaaaaa");
            }
            let mut gone = api_theme("bbbbbbbbbbbb", "Missing", false);
            gone["mini"]["colors"].as_object_mut().unwrap().remove(slot);
            assert!(custom_minis(&list(vec![gone])).is_empty(), "missing {slot}");
        }
        let mut no_colors = api_theme("bbbbbbbbbbbb", "None", false);
        no_colors["mini"]["colors"] = json!(null);
        assert!(custom_minis(&list(vec![no_colors])).is_empty());
    }

    #[test]
    fn custom_minis_accept_upper_case_and_alpha_colors_and_ignore_the_themes_other_colors() {
        let mut t = api_theme("aaaaaaaaaaaa", "Loud", false);
        t["mini"]["colors"]["accent"] = json!("#7C5CFF");
        t["mini"]["colors"]["bgCard"] = json!("#161A26E0");
        // only the mini-player version's colors matter here; the panel's own are the panel's business
        t["colors"]["accent"] = json!("not a color");
        let got = custom_minis(&list(vec![t]));
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].colors["accent"], "#7C5CFF");
        assert_eq!(got[0].colors["bgCard"], "#161A26E0");
    }

    #[test]
    fn custom_minis_group_community_then_mine_each_by_name() {
        let got = custom_minis(&list(vec![
            api_theme("111111111111", "zebra", true),
            api_theme("222222222222", "Beta", false),
            api_theme("333333333333", "alpha", true),
            api_theme("444444444444", "Alpha", false),
            api_theme("555555555555", "alpha", false),
        ]));
        let order: Vec<(&str, bool)> = got.iter().map(|t| (t.id.as_str(), t.mine)).collect();
        assert_eq!(
            order,
            [("444444444444", false), ("555555555555", false), ("222222222222", false), ("333333333333", true), ("111111111111", true)],
            "community first, then mine; names compared without case, ties by id"
        );
    }

    #[test]
    fn custom_minis_mark_private_and_hidden_themes_of_yours() {
        let mut private = api_theme("aaaaaaaaaaaa", "Private", true);
        private["visibility"] = json!("private");
        let mut hidden = api_theme("bbbbbbbbbbbb", "Hidden", true);
        hidden["visibility"] = json!("private");
        hidden["hiddenByOwner"] = json!(true);
        let public = api_theme("cccccccccccc", "Public", true);
        let got = custom_minis(&list(vec![private, hidden, public]));
        let marks: Vec<(&str, bool)> = got.iter().map(|t| (t.id.as_str(), t.private)).collect();
        assert_eq!(marks, [("bbbbbbbbbbbb", true), ("aaaaaaaaaaaa", true), ("cccccccccccc", false)], "by name: Hidden, Private, Public");
    }

    #[test]
    fn custom_minis_cap_the_list_at_200_and_never_cut_an_id() {
        let many: Vec<serde_json::Value> = (0..250).map(|i| api_theme(&format!("{i:012}"), &format!("Theme {i:03}"), false)).collect();
        let got = custom_minis(&list(many));
        assert_eq!(got.len(), MAX_CUSTOM_MINIS);
        assert_eq!(MAX_CUSTOM_MINIS, 200);
        assert!(got.iter().all(|t| t.id.len() == 12 && t.id.bytes().all(|b| b.is_ascii_digit())));
        assert_eq!(got[0].id, format!("{:012}", 0));
        assert_eq!(got[199].id, format!("{:012}", 199));
        // your own themes are never what gets cut
        let mut mixed: Vec<serde_json::Value> = (0..250).map(|i| api_theme(&format!("c{i:011}"), &format!("Theme {i:03}"), false)).collect();
        mixed.push(api_theme("mine00000001", "Zzz mine", true));
        mixed.push(api_theme("mine00000002", "Aaa mine", true));
        let got = custom_minis(&list(mixed));
        assert_eq!(got.len(), 200);
        assert_eq!(got.iter().filter(|t| t.mine).count(), 2);
        assert_eq!(got.iter().filter(|t| !t.mine).count(), 198);
        assert!(got.iter().all(|t| t.id.len() == 12));
    }

    #[test]
    fn custom_minis_need_a_theme_id_like_the_panels() {
        let ids = ["short", "ABCDEFGHIJKL", "abcdefghijkl1", "abc def ghij", "../../../x/y", "abcdefghijk\n", "abcdefghijk-", "ábcdefghijk", ""];
        for id in ids {
            assert!(custom_minis(&list(vec![api_theme(id, "x", false)])).is_empty(), "{id:?}");
        }
        let mut numeric_id = api_theme("aaaaaaaaaaaa", "x", false);
        numeric_id["id"] = json!(123456789012u64);
        assert!(custom_minis(&list(vec![numeric_id])).is_empty());
        assert_eq!(custom_minis(&list(vec![api_theme("a1b2c3d4e5f6", "x", false)])).len(), 1);
    }

    #[test]
    fn custom_minis_keep_the_first_of_two_themes_with_one_id() {
        let got = custom_minis(&list(vec![api_theme("aaaaaaaaaaaa", "First", false), api_theme("aaaaaaaaaaaa", "Second", true)]));
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].name, "First");
    }

    #[test]
    fn custom_minis_show_the_picture_only_when_the_version_uses_it() {
        let with_picture = |use_image: serde_json::Value, background: serde_json::Value| {
            let mut t = api_theme("aaaaaaaaaaaa", "Pic", false);
            t["mini"]["useImage"] = use_image;
            t["background"] = background;
            custom_minis(&list(vec![t]))
        };
        let bg = json!({ "url": PIC, "dim": 10, "blur": 2, "fit": "cover" });
        assert_eq!(with_picture(json!(true), bg.clone())[0].picture.as_deref(), Some(PIC));
        assert_eq!(with_picture(json!(false), bg.clone())[0].picture, None, "not used");
        assert_eq!(with_picture(json!(true), json!(null))[0].picture, None, "useImage with no background is no picture");
        assert_eq!(with_picture(json!(true), json!({}))[0].picture, None);
        assert_eq!(with_picture(json!("true"), bg.clone())[0].picture, None, "only a real true counts");
        assert_eq!(with_picture(json!(null), bg)[0].picture, None);
    }

    #[test]
    fn custom_minis_take_only_the_bots_own_picture_links() {
        let bad = [
            "https://evil.example/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp",
            "//evil.example/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp",
            "theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp",
            "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9.webp",
            "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f0.webp",
            "/theme-images/9F2C4D6E8A0B1C3D5E7F9A1B3C5D7E9F.webp",
            "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.svg",
            "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp?x=1",
            "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp#x",
            "/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp\n",
            "/theme-images/../9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp",
            "/other/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp",
            "javascript:alert(1)",
            "",
        ];
        for url in bad {
            let mut t = api_theme("aaaaaaaaaaaa", "Pic", false);
            t["mini"]["useImage"] = json!(true);
            t["background"] = json!({ "url": url, "dim": 0, "blur": 0, "fit": "cover" });
            let got = custom_minis(&list(vec![t]));
            assert_eq!(got.len(), 1, "the theme itself is still offered: {url:?}");
            assert_eq!(got[0].picture, None, "{url:?}");
        }
        for ext in ["jpg", "png", "webp", "gif"] {
            let path = format!("/theme-images/00112233445566778899aabbccddeeff.{ext}");
            let mut t = api_theme("aaaaaaaaaaaa", "Pic", false);
            t["mini"]["useImage"] = json!(true);
            t["background"] = json!({ "url": path });
            assert_eq!(custom_minis(&list(vec![t]))[0].picture.as_deref(), Some(path.as_str()), "{ext}");
        }
    }

    #[test]
    fn custom_minis_hold_dim_to_0_90() {
        let dim = |v: serde_json::Value| {
            let mut t = api_theme("aaaaaaaaaaaa", "Dim", false);
            t["mini"]["dim"] = v;
            custom_minis(&list(vec![t]))[0].dim
        };
        assert_eq!((dim(json!(0)), dim(json!(55)), dim(json!(90))), (0, 55, 90));
        assert_eq!((dim(json!(200)), dim(json!(-4)), dim(json!(33.6))), (90, 0, 34));
        assert_eq!((dim(json!("55")), dim(json!(null)), dim(json!(true))), (0, 0, 0), "not a number: no darkening");
    }

    #[test]
    fn custom_minis_know_only_the_eight_fonts_and_the_17_bases() {
        for font in FONTS {
            let mut t = api_theme("aaaaaaaaaaaa", "F", false);
            t["font"] = json!(font);
            assert_eq!(custom_minis(&list(vec![t]))[0].font, font);
        }
        for odd in [json!("Comic Sans"), json!("inter"), json!(null), json!(5), json!("Inter; color: red")] {
            let mut t = api_theme("aaaaaaaaaaaa", "F", false);
            t["font"] = odd;
            assert_eq!(custom_minis(&list(vec![t]))[0].font, "Inter");
        }
        for base in THEME_IDS {
            let mut t = api_theme("aaaaaaaaaaaa", "B", false);
            t["base"] = json!(base);
            assert_eq!(custom_minis(&list(vec![t]))[0].base, base);
        }
        for odd in [json!("hologram"), json!("Ember"), json!(null), json!(7), json!("")] {
            let mut t = api_theme("aaaaaaaaaaaa", "B", false);
            t["base"] = odd;
            assert!(custom_minis(&list(vec![t])).is_empty(), "a base the panel doesn't have is a theme the panel can't use either");
        }
    }

    #[test]
    fn custom_minis_clean_names_and_cut_them_on_characters() {
        let bidi = |c: u32| char::from_u32(c).unwrap().to_string();
        let tricky = format!("{}Evil{} name\u{7}\u{0}{}", bidi(0x202E), bidi(0x2069), bidi(0x200F));
        let names = |name: &str, owner: &str| {
            let mut t = api_theme("aaaaaaaaaaaa", name, false);
            t["owner"]["name"] = json!(owner);
            let got = custom_minis(&list(vec![t]));
            (got[0].name.clone(), got[0].owner.clone())
        };
        assert_eq!(names(&tricky, &tricky), ("Evil name".to_string(), "Evil name".to_string()));
        assert_eq!(names("  padded  ", "  me ").0, "padded");
        assert_eq!(names("  padded  ", "  me ").1, "me");
        assert_eq!(names("", "").0, "Untitled theme");
        assert_eq!(names("   ", "\t").1, "someone");
        assert_eq!(names(&bidi(0x202E), &bidi(0x2066)), ("Untitled theme".to_string(), "someone".to_string()));
        // 60 two-byte characters: cut to 40 whole characters, never in the middle of one
        let long = "é".repeat(60);
        let (name, owner) = names(&long, &long);
        assert_eq!(name.chars().count(), 40);
        assert_eq!(owner.chars().count(), 40);
        assert!(name.chars().all(|c| c == 'é'));
        let mut no_owner = api_theme("aaaaaaaaaaaa", "x", false);
        no_owner.as_object_mut().unwrap().remove("owner");
        assert_eq!(custom_minis(&list(vec![no_owner]))[0].owner, "someone");
        let mut odd_name = api_theme("aaaaaaaaaaaa", "x", false);
        odd_name["name"] = json!(42);
        assert_eq!(custom_minis(&list(vec![odd_name]))[0].name, "Untitled theme");
    }

    #[test]
    fn custom_minis_of_nonsense_is_nothing() {
        for data in [json!(null), json!({}), json!([]), json!({ "themes": 5 }), json!({ "themes": {} }), json!({ "themes": [null, 1, "x", []] }), json!("themes")] {
            assert!(custom_minis(&data).is_empty(), "{data}");
        }
    }

    #[test]
    fn custom_minis_serialize_for_the_mini_player_in_camel_case() {
        let mut t = api_theme("aaaaaaaaaaaa", "Shape", true);
        t["mini"]["useImage"] = json!(true);
        t["background"] = json!({ "url": PIC });
        let got = custom_minis(&list(vec![t]));
        let v = serde_json::to_value(&got[0]).unwrap();
        let mut keys: Vec<&str> = v.as_object().unwrap().keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["base", "colors", "decorationsOff", "dim", "font", "id", "mine", "name", "owner", "picture", "private"]);
        assert_eq!(v["picture"], PIC);
        assert_eq!(v["colors"]["accent2"], "#22d3ee");
        assert_eq!(v["decorationsOff"], json!([]), "every piece on (Ember has no background piece for the picture to take)");
    }

    // ---- the pieces of a theme's decorations, and the extra decoration color ----

    /// A theme as the bot sends it since the pieces: its pieces switched off, and 15 mini-player colors.
    fn api_theme_now(id: &str, base: &str, off: serde_json::Value) -> serde_json::Value {
        let mut t = api_theme(id, "Now", false);
        t.as_object_mut().unwrap().remove("effects");
        t["base"] = json!(base);
        t["decorationsOff"] = off;
        t["colors"]["decoExtra"] = json!("#fde047");
        t["mini"]["colors"]["decoExtra"] = json!("#fde047");
        t
    }

    #[test]
    fn the_pieces_list_is_the_bots_own() {
        // one list for the panel, the creator, the bot and the mini-player: every theme this app knows, and nothing else
        let pieces = theme_pieces();
        assert_eq!(pieces.keys().map(String::as_str).collect::<HashSet<_>>(), THEME_IDS.iter().copied().collect::<HashSet<_>>());
        for (theme, list) in pieces {
            assert!(!list.pieces.is_empty(), "{theme}");
            for p in &list.pieces {
                assert!(!p.id.is_empty() && p.id.bytes().all(|b| b.is_ascii_lowercase()), "{theme}: {:?} fits in data-deco-off", p.id);
            }
        }
        let ids = |theme: &str| pieces[theme].pieces.iter().map(|p| (p.id.as_str(), p.background)).collect::<Vec<_>>();
        assert_eq!(ids("nebula"), [("glow", false)]);
        assert_eq!(
            ids("revival"),
            [("sunrise", true), ("rays", true), ("logo", false), ("badge", false), ("sparkles", false), ("record", false), ("glows", false)]
        );
        assert_eq!(ids("grove"), [("photo", true), ("hairlines", false), ("glows", false)]);
    }

    #[test]
    fn deco_off_reaches_the_mini() {
        let t = api_theme_now("aaaaaaaaaaaa", "revival", json!(["rays", "record"]));
        let got = custom_minis(&list(vec![t]));
        assert_eq!(got[0].decorations_off, ["rays", "record"]);
        let v = serde_json::to_value(&got[0]).unwrap();
        assert_eq!(v["decorationsOff"], json!(["rays", "record"]), "as the mini-player reads it");
        // to the mini-player's UI, as it is
        let ui = MiniThemeChoice::Custom(got[0].clone()).into_ui(Some("https://x"));
        assert_eq!(ui.id, "revival");
        assert_eq!(ui.custom.unwrap().decorations_off, ["rays", "record"]);
        // only the base's own pieces, once each, in the order given; anything else is left out
        let odd = api_theme_now("aaaaaaaaaaaa", "revival", json!(["record", "glow", "record", 5, null, "Rays", "rays", "", ["x"], "fire"]));
        assert_eq!(custom_minis(&list(vec![odd]))[0].decorations_off, ["record", "rays"]);
        let ember = api_theme_now("aaaaaaaaaaaa", "ember", json!(["rays", "fire", "flames"]));
        assert_eq!(custom_minis(&list(vec![ember]))[0].decorations_off, ["fire", "flames"]);
        for nothing in [json!([]), json!(null), json!("rays"), json!({ "0": "rays" }), json!(7)] {
            let t = api_theme_now("aaaaaaaaaaaa", "revival", nothing.clone());
            let got = custom_minis(&list(vec![t]));
            assert_eq!(got.len(), 1, "the theme itself is still offered: {nothing}");
            assert!(got[0].decorations_off.is_empty(), "{nothing}");
        }
    }

    #[test]
    fn a_picture_switches_the_bases_background_pieces_off() {
        let with_picture = |base: &str, off: serde_json::Value, use_image: bool| {
            let mut t = api_theme_now("aaaaaaaaaaaa", base, off);
            t["mini"]["useImage"] = json!(use_image);
            t["background"] = json!({ "url": PIC, "dim": 10, "blur": 0, "fit": "cover" });
            custom_minis(&list(vec![t])).remove(0).decorations_off
        };
        // its own list first, then the base's background pieces in the list's order, each once
        assert_eq!(with_picture("revival", json!(["record"]), true), ["record", "sunrise", "rays"]);
        assert_eq!(with_picture("revival", json!(["rays"]), true), ["rays", "sunrise"]);
        assert_eq!(with_picture("grove", json!([]), true), ["photo"]);
        assert_eq!(with_picture("sumi", json!(["seal"]), true), ["seal", "mist", "kanji"]);
        assert!(with_picture("nebula", json!([]), true).is_empty(), "a theme with no background piece");
        // a version that doesn't show the picture keeps its pieces as they are
        assert_eq!(with_picture("revival", json!(["record"]), false), ["record"]);
        let mut no_picture = api_theme_now("aaaaaaaaaaaa", "revival", json!([]));
        no_picture["mini"]["useImage"] = json!(true);
        assert!(custom_minis(&list(vec![no_picture]))[0].decorations_off.is_empty(), "useImage with no picture is no picture");
    }

    #[test]
    fn the_extra_decoration_color_comes_along_when_it_is_usable() {
        let got = custom_minis(&list(vec![api_theme_now("aaaaaaaaaaaa", "rasta", json!([]))]));
        assert_eq!(got[0].colors.len(), 15);
        assert_eq!(got[0].colors["decoExtra"], "#fde047");
        // a bad one is dropped (the skin's own then shows), the version itself is kept
        for bad in [json!("red"), json!("#fff"), json!("url(https://x/y)"), json!("#fde047; x"), json!(5), json!(null), json!("")] {
            let mut t = api_theme_now("aaaaaaaaaaaa", "rasta", json!([]));
            t["mini"]["colors"]["decoExtra"] = bad.clone();
            let got = custom_minis(&list(vec![t]));
            assert_eq!(got.len(), 1, "{bad}");
            assert_eq!(got[0].colors.len(), 14, "{bad}");
            assert!(!got[0].colors.contains_key("decoExtra"), "{bad}");
        }
        // the 14 are still all needed
        let mut missing = api_theme_now("aaaaaaaaaaaa", "rasta", json!([]));
        missing["mini"]["colors"].as_object_mut().unwrap().remove("accent2");
        assert!(custom_minis(&list(vec![missing])).is_empty());
        // and nothing else of the theme's colors comes along
        let mut more = api_theme_now("aaaaaaaaaaaa", "rasta", json!([]));
        more["mini"]["colors"]["glass"] = json!("#10131ce0");
        more["mini"]["colors"]["evil"] = json!("#000000");
        let got = custom_minis(&list(vec![more]));
        assert_eq!(got[0].colors.len(), 15);
    }

    #[test]
    fn old_payload_still_works() {
        // a theme from a bot before the pieces (what the 0.6.0 app read): `effects`, no decorationsOff, 14 mini-player
        // colors. Worn on its base skin with every piece on and the skin's own extra decoration color; `effects` is the
        // bot's to turn into pieces (it does, once updated), not the app's.
        for effects in [json!(false), json!(true), json!(null)] {
            let mut t = api_theme("aaaaaaaaaaaa", "Old", false);
            t["base"] = json!("revival");
            t["effects"] = effects.clone();
            assert!(t.get("decorationsOff").is_none() && t["mini"]["colors"].get("decoExtra").is_none());
            let got = custom_minis(&list(vec![t]));
            assert_eq!(got.len(), 1, "{effects}");
            assert!(got[0].decorations_off.is_empty(), "{effects}");
            assert_eq!(got[0].colors.len(), 14);
            assert!(!got[0].colors.contains_key("decoExtra"));
            let ui = MiniThemeChoice::Custom(got[0].clone()).into_ui(Some("https://x"));
            assert_eq!(ui.id, "revival");
            let v = serde_json::to_value(&ui).unwrap();
            assert_eq!(v["custom"]["decorationsOff"], json!([]));
            assert!(v["custom"]["colors"].get("decoExtra").is_none());
        }
        // with a picture: the background pieces are off, as they are for a theme from a new bot
        let mut pic = api_theme("aaaaaaaaaaaa", "Old", false);
        pic["base"] = json!("revival");
        pic["mini"]["useImage"] = json!(true);
        pic["background"] = json!({ "url": PIC });
        assert_eq!(custom_minis(&list(vec![pic]))[0].decorations_off, ["sunrise", "rays"]);
    }

    #[test]
    fn matching_the_panel_brings_the_custom_themes_pieces() {
        // the panel wearing a custom theme with a mini-player version: the mini-player wears that version, pieces and all
        let customs = custom_minis(&list(vec![api_theme_now("k3j9x0q2m7ab", "revival", json!(["rays"]))]));
        let settings = Settings { panel_theme: Some(panel("revival", "custom:k3j9x0q2m7ab", &[("--accent", "#123456")])), ..Settings::default() };
        let ui = effective(&settings, &customs).into_ui(Some("https://x"));
        assert_eq!(ui.id, "revival");
        assert!(ui.vars.is_empty(), "the version's own colors, not the panel's live ones");
        let custom = ui.custom.unwrap();
        assert_eq!(custom.decorations_off, ["rays"]);
        assert_eq!(custom.colors["decoExtra"], "#fde047");
    }

    // ---- which skin the mini-player wears ----

    fn mini(id: &str, base: &str) -> CustomMini {
        custom_minis(&list(vec![{
            let mut t = api_theme(id, "Mini", false);
            t["base"] = json!(base);
            t
        }]))
        .remove(0)
    }

    #[test]
    fn a_custom_theme_with_a_mini_version_wears_it() {
        let customs = [mini("k3j9x0q2m7ab", "ember"), mini("aaaaaaaaaaaa", "aurora")];
        let live = [("--accent", "#123456")];
        let got = resolve_mini_theme(&panel("ember", "custom:k3j9x0q2m7ab", &live), &customs);
        assert_eq!(got, MiniThemeChoice::Custom(customs[0].clone()));
        let got = resolve_mini_theme(&panel("aurora", "custom:aaaaaaaaaaaa", &[]), &customs);
        assert_eq!(got, MiniThemeChoice::Custom(customs[1].clone()));
    }

    #[test]
    fn a_custom_theme_without_a_mini_version_wears_its_base_skin_not_its_colors() {
        // it isn't in the list of mini-player versions, so only the base the panel reports is left; the panel's live
        // values are the custom theme's colors, which the hand-tuned skin must not be painted with
        let got = resolve_mini_theme(&panel("steel", "custom:k3j9x0q2m7ab", &[("--accent", "#123456")]), &[]);
        assert_eq!(got, MiniThemeChoice::Builtin { id: "steel".into(), vars: Default::default() });
        // another theme's version in the list changes nothing
        let got = resolve_mini_theme(&panel("steel", "custom:k3j9x0q2m7ab", &[]), &[mini("aaaaaaaaaaaa", "ember")]);
        assert_eq!(got, MiniThemeChoice::Builtin { id: "steel".into(), vars: Default::default() });
    }

    #[test]
    fn an_unknown_custom_theme_wears_its_base_or_nebula() {
        let nebula = MiniThemeChoice::Builtin { id: "nebula".into(), vars: Default::default() };
        assert_eq!(resolve_mini_theme(&panel("hologram", "custom:k3j9x0q2m7ab", &[]), &[]), nebula, "a base this app doesn't know");
        assert_eq!(resolve_mini_theme(&panel("", "custom:k3j9x0q2m7ab", &[]), &[]), nebula);
        assert_eq!(resolve_mini_theme(&panel("nebula", "custom:k3j9x0q2m7ab", &[]), &[]), nebula);
        let grove = MiniThemeChoice::Builtin { id: "grove".into(), vars: Default::default() };
        assert_eq!(resolve_mini_theme(&panel("grove", "custom:k3j9x0q2m7ab", &[]), &[]), grove, "the list hasn't arrived yet");
    }

    #[test]
    fn a_built_in_theme_keeps_the_panels_live_colors() {
        let live = [("--accent", "#0ff")];
        let want = MiniThemeChoice::Builtin { id: "neon".into(), vars: theme("neon", &live).vars };
        assert_eq!(resolve_mini_theme(&panel("neon", "neon", &live), &[]), want);
        // a report from before the choice existed means the same
        assert_eq!(resolve_mini_theme(&theme("neon", &live), &[]), want);
        // a built-in theme newer than this app still reaches the skin picker as it did before
        assert_eq!(
            resolve_mini_theme(&panel("next-theme", "next-theme", &live), &[]),
            MiniThemeChoice::Builtin { id: "next-theme".into(), vars: theme("x", &live).vars }
        );
        // a custom theme that is in the list is never mixed with live colors
        let customs = [mini("k3j9x0q2m7ab", "ember")];
        assert!(matches!(resolve_mini_theme(&panel("ember", "custom:k3j9x0q2m7ab", &live), &customs), MiniThemeChoice::Custom(_)));
    }

    #[test]
    fn effective_follows_match_setting() {
        let seen = panel("neon", "neon", &[("--accent", "#0ff")]);
        let synced = Settings { panel_theme: Some(seen.clone()), ..Settings::default() };
        assert_eq!(effective(&synced, &[]), MiniThemeChoice::Builtin { id: "neon".into(), vars: seen.vars.clone() });
        assert_eq!(effective(&Settings::default(), &[]), MiniThemeChoice::Builtin { id: "nebula".into(), vars: Default::default() });
        let own = Settings { mini_player_match_panel: false, mini_player_theme: "ember".into(), ..synced.clone() };
        assert_eq!(effective(&own, &[]), MiniThemeChoice::Builtin { id: "ember".into(), vars: Default::default() });
    }

    #[test]
    fn effective_matching_the_panel_wears_the_panels_custom_theme() {
        let customs = [mini("k3j9x0q2m7ab", "ember")];
        let settings = Settings { panel_theme: Some(panel("ember", "custom:k3j9x0q2m7ab", &[("--accent", "#1")])), ..Settings::default() };
        assert_eq!(effective(&settings, &customs), MiniThemeChoice::Custom(customs[0].clone()));
        assert_eq!(
            effective(&settings, &[]),
            MiniThemeChoice::Builtin { id: "ember".into(), vars: Default::default() },
            "until the list arrives, or when the theme has no mini-player version"
        );
    }

    #[test]
    fn effective_with_its_own_custom_pick_falls_back_to_the_base_it_remembers() {
        let customs = [mini("k3j9x0q2m7ab", "ember")];
        let own = Settings {
            mini_player_match_panel: false,
            mini_player_theme: "custom:k3j9x0q2m7ab".into(),
            mini_player_theme_base: "aurora".into(),
            ..Settings::default()
        };
        assert_eq!(effective(&own, &customs), MiniThemeChoice::Custom(customs[0].clone()));
        // deleted, hidden or made private since: its base skin, then Nebula
        assert_eq!(effective(&own, &[]), MiniThemeChoice::Builtin { id: "aurora".into(), vars: Default::default() });
        let no_base = Settings { mini_player_theme_base: "".into(), ..own.clone() };
        assert_eq!(effective(&no_base, &[]), MiniThemeChoice::Builtin { id: "nebula".into(), vars: Default::default() });
        let junk_base = Settings { mini_player_theme_base: "../x".into(), ..own };
        assert_eq!(effective(&junk_base, &[]), MiniThemeChoice::Builtin { id: "nebula".into(), vars: Default::default() });
    }

    #[test]
    fn the_theme_menu_shows_the_pick_or_the_skin_that_stands_in_for_it() {
        let customs = [mini("k3j9x0q2m7ab", "ember")];
        let sel = |theme: &str, base: &str, customs: &[CustomMini]| menu_selection(theme, base, customs);
        assert_eq!(sel("neon", "neon", &[]), "neon");
        assert_eq!(sel("custom:k3j9x0q2m7ab", "ember", &customs), "custom:k3j9x0q2m7ab");
        assert_eq!(sel("custom:k3j9x0q2m7ab", "aurora", &[]), "aurora", "no longer offered: the base skin that is worn");
        assert_eq!(sel("custom:k3j9x0q2m7ab", "", &[]), "nebula");
        assert_eq!(sel("custom:k3j9x0q2m7ab", "<b>", &[]), "nebula");
        assert_eq!(sel("hologram", "", &[]), "nebula");
        assert_eq!(sel("", "", &[]), "nebula");
    }

    #[test]
    fn valid_choice_is_a_built_in_id_or_a_custom_pattern() {
        for ok in THEME_IDS.iter().copied().chain(["custom:k3j9x0q2m7ab", "custom:000000000000", "custom:zzzzzzzzzzzz"]) {
            assert!(valid_choice(ok), "{ok}");
        }
        for bad in ["", "Neon", "hologram", "custom:", "custom:short", "custom:K3J9X0Q2M7AB", "custom:k3j9x0q2m7ab1", "custom:k3j9x0q2m7a-",
            "custom:../../etc/x", "custom: k3j9x0q2m7ab", "custom:k3j9x0q2m7ab\n", "Custom:k3j9x0q2m7ab", "custom:k3j9x0q2m7ab;", "nebula "] {
            assert!(!valid_choice(bad), "{bad:?}");
        }
    }

    #[test]
    fn choosing_a_theme_remembers_a_custom_ones_base_beside_it() {
        let customs = [mini("k3j9x0q2m7ab", "grove")];
        let mut s = Settings::default();
        choose(&mut s, false, Some("custom:k3j9x0q2m7ab"), &customs);
        assert!(!s.mini_player_match_panel);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:k3j9x0q2m7ab", "grove"));
        choose(&mut s, false, Some("aurora"), &customs);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("aurora", "aurora"));
        // a custom theme the list doesn't have (yet) is still taken, on Nebula
        choose(&mut s, true, Some("custom:zzzzzzzzzzzz"), &customs);
        assert!(s.mini_player_match_panel);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:zzzzzzzzzzzz", "nebula"));
    }

    #[test]
    fn flipping_match_the_panel_alone_leaves_a_stored_custom_pick_and_its_base_as_they_were() {
        // The custom theme isn't in the list (not loaded yet, the bot is offline, the panel is signed out): the menu
        // shows its base skin in its place, and the toggle must not turn that stand-in into the pick.
        let mut s = Settings {
            mini_player_match_panel: false,
            mini_player_theme: "custom:k3j9x0q2m7ab".into(),
            mini_player_theme_base: "grove".into(),
            ..Settings::default()
        };
        assert_eq!(menu_selection(&s.mini_player_theme, &s.mini_player_theme_base, &[]), "grove", "what the select shows meanwhile");
        choose(&mut s, true, None, &[]);
        assert!(s.mini_player_match_panel);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:k3j9x0q2m7ab", "grove"));
        choose(&mut s, false, None, &[]);
        assert!(!s.mini_player_match_panel);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:k3j9x0q2m7ab", "grove"));
        // and with the list loaded and the theme in it, the same
        let customs = [mini("k3j9x0q2m7ab", "ember")];
        choose(&mut s, true, None, &customs);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:k3j9x0q2m7ab", "grove"));
        // a built-in pick is left alone too
        let mut own = Settings { mini_player_theme: "ember".into(), mini_player_theme_base: "ember".into(), ..Settings::default() };
        choose(&mut own, false, None, &[]);
        assert_eq!((own.mini_player_theme.as_str(), own.mini_player_theme_base.as_str()), ("ember", "ember"));
    }

    #[test]
    fn picking_the_stored_custom_theme_again_while_it_is_missing_keeps_its_base() {
        let mut s = Settings {
            mini_player_theme: "custom:k3j9x0q2m7ab".into(),
            mini_player_theme_base: "grove".into(),
            ..Settings::default()
        };
        choose(&mut s, false, Some("custom:k3j9x0q2m7ab"), &[]);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:k3j9x0q2m7ab", "grove"));
        // a different custom theme that isn't in the list has no base to remember
        choose(&mut s, false, Some("custom:zzzzzzzzzzzz"), &[]);
        assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("custom:zzzzzzzzzzzz", "nebula"));
        // a base that isn't a built-in theme is not kept
        let mut junk = Settings { mini_player_theme: "custom:k3j9x0q2m7ab".into(), mini_player_theme_base: "../x".into(), ..Settings::default() };
        choose(&mut junk, false, Some("custom:k3j9x0q2m7ab"), &[]);
        assert_eq!(junk.mini_player_theme_base, "nebula");
    }

    #[test]
    fn choosing_something_that_is_not_a_theme_keeps_the_current_pick() {
        let mut s = Settings { mini_player_theme: "ember".into(), mini_player_theme_base: "ember".into(), ..Settings::default() };
        for bad in ["", "hologram", "custom:", "custom:../x", "custom:K3J9X0Q2M7AB", "<script>"] {
            choose(&mut s, false, Some(bad), &[]);
            assert_eq!((s.mini_player_theme.as_str(), s.mini_player_theme_base.as_str()), ("ember", "ember"), "{bad:?}");
            assert!(!s.mini_player_match_panel, "the match switch still follows the person");
        }
    }

    #[test]
    fn the_theme_list_is_asked_for_again_after_a_report_while_it_has_never_loaded() {
        // the usual reasons: the panel's theme changed, or the list is not here yet
        assert!(needs_refresh(true, true), "the panel switched themes");
        assert!(needs_refresh(true, false));
        assert!(needs_refresh(false, false), "same theme as last session, but the first ask came before the panel did");
        assert!(!needs_refresh(false, true), "nothing new: a report of the same theme with the list in hand");
    }

    #[test]
    fn the_mini_player_is_told_a_custom_theme_over_its_base_with_a_full_picture_link() {
        let mut t = api_theme("aaaaaaaaaaaa", "Pic", false);
        t["mini"]["useImage"] = json!(true);
        t["background"] = json!({ "url": PIC });
        let customs = custom_minis(&list(vec![t]));
        let ui = MiniThemeChoice::Custom(customs[0].clone()).into_ui(Some("https://cloutkings.duckdns.org"));
        assert_eq!(ui.id, "ember", "its base skin is what shows if the colors can't be used");
        assert!(ui.vars.is_empty());
        let custom = ui.custom.unwrap();
        assert_eq!(custom.picture.as_deref(), Some("https://cloutkings.duckdns.org/theme-images/9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f.webp"));
        assert_eq!(custom.colors, customs[0].colors);
        // a bot address that isn't a usable URL has no picture to fetch from
        let ui = MiniThemeChoice::Custom(customs[0].clone()).into_ui(None);
        assert_eq!(ui.custom.unwrap().picture, None);
    }

    #[test]
    fn the_mini_player_is_told_a_built_in_theme_with_its_live_colors_and_no_custom_part() {
        let ui = MiniThemeChoice::Builtin { id: "neon".into(), vars: theme("x", &[("--accent", "#0ff")]).vars }.into_ui(Some("https://x"));
        let v = serde_json::to_value(&ui).unwrap();
        assert_eq!(v, json!({ "id": "neon", "vars": { "--accent": "#0ff" }, "custom": null }));
    }

    #[test]
    fn the_theme_menu_payload_has_the_list_and_what_to_select() {
        let customs = vec![mini("k3j9x0q2m7ab", "ember")];
        let settings = Settings { mini_player_match_panel: false, mini_player_theme: "custom:k3j9x0q2m7ab".into(), ..Settings::default() };
        let menu = theme_menu(&settings, &customs);
        assert_eq!(menu.theme, "custom:k3j9x0q2m7ab");
        assert_eq!(menu.custom_themes, customs);
        let v = serde_json::to_value(&menu).unwrap();
        assert!(v["customThemes"].is_array() && v["theme"].is_string());
        assert_eq!(theme_menu(&settings, &[]).theme, "nebula");
    }
}
