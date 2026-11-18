//! The Omarchy theme, as Omarchy's own apps follow it: the colours of
//! `~/.local/state/omarchy/current/theme/colors.toml`, re-read when the file changes, and the
//! window rounding Hyprland reports. Anywhere else, Rune's own dark or light tokens.

use std::path::PathBuf;
use std::time::SystemTime;

use rune::color::hex;
use rune::{Color, tok};

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Palette {
    pub bg: Color,
    pub card: Color,
    pub card_border: Color,
    pub text: Color,
    pub text_secondary: Color,
    pub accent: Color,
    /// Text on an accent fill.
    pub on_accent: Color,
    pub hover: Color,
    pub control: Color,
    pub danger: Color,
    pub warning: Color,
    pub success: Color,
    pub radius: f32,
}

impl Palette {
    pub fn rune(dark: bool) -> Palette {
        let t = tok::theme(dark);
        Palette {
            bg: t.bg,
            card: t.card,
            card_border: t.card_border,
            text: t.text,
            text_secondary: t.text_secondary,
            accent: tok::accent(0, dark),
            on_accent: if dark { t.bg } else { t.card },
            hover: t.hover,
            control: t.control_off,
            danger: rune::oklch(0.68, 0.16, 25.0),
            warning: rune::oklch(0.8, 0.13, 85.0),
            success: rune::oklch(0.75, 0.13, 155.0),
            radius: tok::radius::MD,
        }
    }

    /// The palette `colors.toml` names, over Rune's defaults for any key it lacks.
    pub fn from_colors(source: &str, radius: Option<f32>) -> Palette {
        let value = |key: &str| -> Option<Color> {
            source.lines().find_map(|line| {
                let (k, v) = line.split_once('=')?;
                (k.trim() == key).then(|| hex(v.trim().trim_matches('"')))?
            })
        };
        let dark = !source.lines().any(|l| {
            l.split_once('=')
                .is_some_and(|(k, v)| k.trim() == "mode" && v.contains("light"))
        });
        let base = Palette::rune(dark);
        let bg = value("background").unwrap_or(base.bg);
        Palette {
            bg,
            card: value("lighter_background").unwrap_or(base.card),
            card_border: value("selection").unwrap_or(base.card_border),
            text: value("foreground").unwrap_or(base.text),
            text_secondary: value("dark_foreground")
                .or_else(|| value("muted"))
                .unwrap_or(base.text_secondary),
            accent: value("accent").unwrap_or(base.accent),
            on_accent: value("darker_background").unwrap_or(bg),
            hover: value("selection").map_or(base.hover, |c| c.alpha(0.35)),
            control: value("selection").unwrap_or(base.control),
            danger: value("red").unwrap_or(base.danger),
            warning: value("yellow").unwrap_or(base.warning),
            success: value("green").unwrap_or(base.success),
            radius: radius.unwrap_or(base.radius),
        }
    }
}

pub fn colors_path() -> Option<PathBuf> {
    let home = std::env::var_os("HOME")?;
    Some(PathBuf::from(home).join(".local/state/omarchy/current/theme/colors.toml"))
}

/// Hyprland's `decoration:rounding`, or `None` off Hyprland.
pub fn hypr_rounding() -> Option<f32> {
    let out = std::process::Command::new("hyprctl")
        .args(["getoption", "decoration:rounding", "-j"])
        .output()
        .ok()?;
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).ok()?;
    v.get("int")?.as_f64().map(|n| n as f32)
}

/// The theme and the file it came from, so a change can be noticed.
pub struct Followed {
    pub palette: Palette,
    stamp: Option<SystemTime>,
}

impl Followed {
    pub fn load(system_dark: bool) -> Followed {
        let stamp = colors_path().and_then(|p| std::fs::metadata(p).ok()?.modified().ok());
        let palette = colors_path()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .map_or(Palette::rune(system_dark), |s| {
                Palette::from_colors(&s, hypr_rounding())
            });
        Followed { palette, stamp }
    }

    /// Re-reads the file if it changed since the last read; true when the palette did.
    pub fn refresh(&mut self, system_dark: bool) -> bool {
        let stamp = colors_path().and_then(|p| std::fs::metadata(p).ok()?.modified().ok());
        if stamp == self.stamp {
            return false;
        }
        let next = Followed::load(system_dark);
        let changed = next.palette != self.palette;
        *self = next;
        changed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_omarchy_keys() {
        let p = Palette::from_colors(
            "mode = \"dark\"\naccent = \"#8bcbb1\"\nbackground = \"#142620\"\nforeground = \"#dfede5\"\n",
            Some(0.0),
        );
        assert_eq!(p.accent, hex("#8bcbb1").unwrap());
        assert_eq!(p.bg, hex("#142620").unwrap());
        assert_eq!(p.text, hex("#dfede5").unwrap());
        assert_eq!(p.radius, 0.0);
        // A key the file lacks keeps Rune's.
        assert_eq!(p.danger, Palette::rune(true).danger);
    }

    #[test]
    fn a_light_theme_falls_back_to_light_tokens() {
        let p = Palette::from_colors("mode = \"light\"\n", None);
        assert_eq!(p.bg, Palette::rune(false).bg);
    }
}
