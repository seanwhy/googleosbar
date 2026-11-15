#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Builds BentoBar's icon fonts: a subset of Google's Material Symbols Rounded (Apache-2.0).

Downloads the variable font once into ~/.cache/bentobar, pins two static instances (outlined
FILL=0 and filled FILL=1, weight 400, optical size 24, grade 0), keeps only the icons named in
ICONS (subset by codepoint, not by name: ligatures share letters and would keep everything),
and writes app/src/main/assets/fonts/*.ttf plus the Kotlin table util/Sym.kt.

    python3 tools/icons.py        (needs fonttools: sudo apt install python3-fonttools)
"""
import os
import pathlib
import subprocess
import sys
import urllib.request

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ICONS = """
add alarm apps arrow_back arrow_downward arrow_upward autorenew avg_pace battery_0_bar
battery_charging_full battery_full bedtime bolt bookmark build calendar_month calendar_today
check check_circle chevron_left chevron_right close coffee content_copy dark_mode data_usage
delete desktop_windows device_thermostat directions do_not_disturb_on done drag_indicator edit emoji_objects
event event_upcoming expand_less expand_more favorite grid_view groups hard_drive handyman help
history hourglass_top info keyboard label light_mode link local_cafe lock memory menu mouse
music_note network_check notifications open_in_new palette pause play_arrow power_settings_new
public push_pin refresh remove replay restart_alt rocket_launch schedule screenshot_monitor search
settings skip_next skip_previous space_bar speed star stop swap_vert text_fields thermostat timer
title toggle_on touch_app tune update videocam visibility visibility_off volume_down volume_mute
volume_off volume_up warning widgets wifi wysiwyg memory_alt
""".split()

# Added for 0.9 in one run, so that nobody working on a single item has to rerun this: now playing,
# device batteries, heat, weather (day and night), flights, world clock, and what goes with being online.
ICONS += """
album fast_forward fast_rewind graphic_eq headphones music_off pause_circle play_circle queue_music
battery_1_bar battery_3_bar battery_5_bar battery_alert battery_low battery_unknown battery_very_low
bluetooth bluetooth_connected earbuds gamepad headset_mic speaker sports_esports stadia_controller
stylus stylus_note trackpad_input watch
ac_unit heat local_fire_department mode_cool mode_fan mode_heat severe_cold thermometer
air clear_day clear_night cloud cloud_off cloudy_snowing cyclone explore foggy humidity_percentage
location_on mist my_location navigation near_me nights_stay partly_cloudy_day partly_cloudy_night
rainy rainy_heavy rainy_light rainy_snow snowing storm sunny sunny_snowing thunderstorm tornado
travel_explore umbrella water_drop wb_twilight weather_hail weather_mix weather_snowy
airlines airplane_ticket airplanemode_active airplanemode_inactive connecting_airports
departure_board door_front flight flight_land flight_takeoff local_airport luggage meeting_room travel
add_circle calendar_add_on calendar_clock cancel edit_calendar language more_time remove_circle today
arrow_forward backspace block cloud_done cloud_sync content_paste east error key lock_open north
notifications_active notifications_off password pending priority_high privacy_tip send shield south
sync sync_problem toggle_off trending_flat vpn_key west wifi_off
""".split()

# Added for the Shortcut item: "Report a bug" (the other actions use glyphs the Tools menu has).
ICONS += """
bug_report
""".split()

# Added for the Stocks item: its glyph in the bar goes up, down or flat with the day's change.
ICONS += """
show_chart trending_down trending_up
""".split()

# Added for the items after 1.8 in one run, so that nobody working on a single item has to rerun this.
ICONS += """
mic mic_off videocam_off photo_camera_front flip subtitles text_increase text_decrease inbox attach_file image
description radio_button_checked self_improvement center_focus_strong routine brightness_low brightness_medium
brightness_high brightness_auto backlight_low backlight_high lan vpn_lock captive_portal signal_wifi_bad
settings_ethernet signal_cellular_alt usb tv timelapse clock_loader_10 clock_loader_20 clock_loader_40
clock_loader_60 clock_loader_80 clock_loader_90 relax currency_bitcoin data_object auto_awesome translate summarize
spellcheck short_text clear_all downloading call hourglass_bottom
""".split()

# Symbols also exported as vector drawables (tiles, notifications, launcher icon), from the filled font.
DRAWABLES = ["coffee", "timer", "avg_pace", "event", "pause", "play_arrow", "add", "stop", "videocam", "open_in_new"]

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = pathlib.Path(os.environ.get("BENTOBAR_CACHE", pathlib.Path.home() / ".cache/bentobar"))
# Pinned to a commit, not master: with no cached font an icon added later must not change every glyph. Move it on purpose.
BASE = "https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/variablefont/"
VF = "MaterialSymbolsRounded[FILL,GRAD,opsz,wght]"


def fetch(name):
    path = CACHE / name
    if not path.exists() or path.stat().st_size == 0:
        CACHE.mkdir(parents=True, exist_ok=True)
        url = BASE + urllib.request.quote(name)
        print("downloading", url)
        urllib.request.urlretrieve(url, path)
    return path


def main():
    codepoints = {}
    for line in fetch(VF + ".codepoints").read_text().splitlines():
        name, cp = line.split()
        codepoints[name] = int(cp, 16)
    missing = [n for n in ICONS if n not in codepoints]
    if missing:
        sys.exit(f"not in Material Symbols: {missing}")
    wanted = sorted({codepoints[n] for n in ICONS})
    out_dir = ROOT / "app/src/main/assets/fonts"
    out_dir.mkdir(parents=True, exist_ok=True)
    for fill, suffix in ((0, ""), (1, "_Fill")):
        font = TTFont(fetch(VF + ".ttf"))
        font = instancer.instantiateVariableFont(font, {"FILL": fill, "GRAD": 0, "opsz": 24, "wght": 400})
        options = subset.Options()
        options.layout_features = ["*"]
        options.glyph_names = False
        options.notdef_outline = True
        sub = subset.Subsetter(options)
        sub.populate(unicodes=wanted)
        sub.subset(font)
        target = out_dir / f"MaterialSymbolsRounded{suffix}.ttf"
        font.save(target)
        print(target.relative_to(ROOT), target.stat().st_size, "bytes")

    # Vector drawables: glyph outlines, flipped from font units (y up, 960 per em) to a 0..960 viewport.
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    filled = TTFont(out_dir / "MaterialSymbolsRounded_Fill.ttf")
    cmap, glyphs = filled.getBestCmap(), filled.getGlyphSet()
    res = ROOT / "app/src/main/res/drawable"
    res.mkdir(parents=True, exist_ok=True)
    for n in DRAWABLES:
        pen = SVGPathPen(glyphs)
        glyphs[cmap[codepoints[n]]].draw(TransformPen(pen, (1, 0, 0, -1, 0, 960)))
        (res / f"sym_{n}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Material Symbols Rounded "' + n + '" (Apache-2.0, Google), generated by tools/icons.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp" android:viewportWidth="960" android:viewportHeight="960"\n'
            '    android:tint="?android:attr/colorControlNormal">\n'
            '  <path android:fillColor="@android:color/white" android:pathData="' + pen.getCommands() + '"/>\n'
            '</vector>\n')
        print(f"res/drawable/sym_{n}.xml")

    lines = [
        "package com.meridian11.books.bentobar.util",
        "",
        "// Generated by tools/icons.py: Material Symbols Rounded codepoints (Apache-2.0, Google).",
        "// The glyphs live in assets/fonts (outlined + filled); draw them with [SymIcon].",
        "object Sym {",
    ]
    for n in sorted(ICONS):
        lines.append(f'    const val {n.upper()} = "\\u{codepoints[n]:04x}"')
    lines.append("}")
    kt = ROOT / "app/src/main/java/com/meridian11/books/bentobar/util/Sym.kt"
    kt.write_text("\n".join(lines) + "\n")
    print(kt.relative_to(ROOT), len(ICONS), "icons")


if __name__ == "__main__":
    main()
