package com.carlink.navigation.compose

import android.graphics.Path

/**
 * Static SVG path strings extracted from Apple's MapKit via the prior RE work
 * (/Users/zeno/Documents/Apple_Maps_SVG/generate_maneuver_svgs.py SHAPES dictionary).
 *
 * These cover maneuver shapes that don't take per-route parameters: straight-ahead,
 * U-turn, merges, ramps, destination glyphs, ferries. For each Apple uses a procedural
 * bezier-builder function with fixed inputs — the output is deterministic and matches
 * Apple's exact bezier coordinates byte-for-byte (extracted by dlsym + call on macOS,
 * see Apple_Maps_Maneuver_Icons_RE.md §21).
 *
 * Coordinate system: 56-pt-square viewport (Apple's standard). Compose dispatcher maps
 * these into the same Bitmap-renderer pipeline as the dynamically-generated roundabout
 * and turn-arrow paths — visually consistent output at any cluster resolution.
 *
 * Each constant is the `fill` field from the Python SHAPES dict. For shapes with separate
 * `stroke` or `mainRoad` subpaths, those are appended via the same SvgPathParser into one
 * Android Path object. Even-odd fill rule is set on the Path for shapes flagged
 * `fill_rule: "evenodd"` (destinations, ferries).
 */
internal object StaticPathConstants {
    // ── Straight ahead (CPManeuverType 3) ──
    const val STRAIGHT =
        "M 28.5000 56.0000 C 26.5670 56.0000 25.0000 54.4330 25.0000 52.5000 L 25.0000 27.6400 " +
            "L 32.0000 27.6400 L 32.0000 52.5000 C 32.0000 54.4330 30.4330 56.0000 28.5000 56.0000 Z " +
            "M 26.8733 9.8649 L 13.9854 24.2515 L 13.9854 24.2515 " +
            "C 13.2518 25.1213 13.3622 26.4210 14.2319 27.1546 " +
            "C 14.7360 27.5798 15.4132 27.7380 16.0535 27.5801 " +
            "L 25.0000 22.4148 L 25.0000 27.6400 L 32.0000 27.6400 L 32.0000 22.4148 " +
            "L 40.9465 27.5801 L 40.9465 27.5801 " +
            "C 42.0512 27.8525 43.1676 27.1778 43.4401 26.0731 " +
            "C 43.5980 25.4328 43.4398 24.7556 43.0146 24.2515 " +
            "L 30.1267 9.8649 L 30.1267 9.8649 " +
            "C 29.5207 8.9665 28.3012 8.7295 27.4028 9.3354 " +
            "C 27.1940 9.4763 27.0142 9.6561 26.8733 9.8649 Z"

    // ── U-turn (CPManeuverType 4) ──
    const val UTURN =
        "M 46.0000 56.0000 C 44.0670 56.0000 42.5000 54.4330 42.5000 52.5000 " +
            "L 42.5000 48.7556 L 49.5000 48.7556 L 49.5000 52.5000 " +
            "C 49.5000 54.4330 47.9330 56.0000 46.0000 56.0000 Z " +
            "M 42.5000 48.7556 L 42.5000 48.7556 C 42.5000 42.1281 37.1274 36.7556 30.5000 36.7556 " +
            "C 23.8726 36.7556 18.5000 42.1281 18.5000 48.7556 L 11.5000 48.7556 L 11.5000 48.7556 " +
            "C 11.5000 38.2621 20.0066 29.7556 30.5000 29.7556 C 40.9934 29.7556 49.5000 38.2621 49.5000 48.7556 Z " +
            "M 18.5000 48.7556 L 11.5000 48.7556 L 11.5000 48.3600 L 18.5000 48.3600 Z " +
            "M 16.6267 66.1351 L 29.5146 51.7485 L 29.5146 51.7485 " +
            "C 30.2482 50.8787 30.1378 49.5790 29.2681 48.8454 " +
            "C 28.7640 48.4202 28.0868 48.2620 27.4465 48.4199 " +
            "L 18.5000 53.5852 L 18.5000 48.3600 L 11.5000 48.3600 L 11.5000 53.5852 " +
            "L 2.5535 48.4199 L 2.5535 48.4199 " +
            "C 1.4488 48.1475 0.3324 48.8222 0.0599 49.9269 " +
            "C -0.0980 50.5672 0.0602 51.2444 0.4854 51.7485 " +
            "L 13.3733 66.1351 L 13.3733 66.1351 " +
            "C 13.9793 67.0335 15.1988 67.2705 16.0972 66.6646 " +
            "C 16.3060 66.5237 16.4858 66.3439 16.6267 66.1351 Z"

    // ── Merge right (CPManeuverType 13 KeepLeft is the mirror; 14 KeepRight uses this directly) ──
    const val MERGE_RIGHT =
        "M 21.7000 56.0000 C 19.7670 56.0000 18.2000 54.4330 18.2000 52.5000 " +
            "L 18.2000 45.8182 L 25.2000 45.8182 L 25.2000 52.5000 " +
            "C 25.2000 54.4330 23.6330 56.0000 21.7000 56.0000 Z " +
            "M 18.2000 45.8182 L 18.2000 45.8182 C 18.2000 41.6581 19.0652 37.5435 20.7407 33.7358 L 20.7407 33.7358 " +
            "C 22.4161 29.9280 24.8652 26.5104 27.9323 23.6999 L 32.6614 28.8608 L 32.6614 28.8608 " +
            "C 30.3100 31.0155 28.4323 33.6357 27.1478 36.5550 L 27.1478 36.5550 " +
            "C 25.8633 39.4743 25.2000 42.6288 25.2000 45.8182 L 25.2000 45.8182 Z " +
            "M 32.4364 19.5726 L 37.1655 24.7336 L 32.6614 28.8608 L 27.9323 23.6999 Z " +
            "M 46.8072 8.9451 L 27.4933 9.1625 L 27.4933 9.1625 " +
            "C 26.3564 9.2093 25.4727 10.1688 25.5194 11.3056 " +
            "C 25.5465 11.9646 25.8874 12.5707 26.4364 12.9361 " +
            "L 36.2888 16.0425 L 32.4364 19.5726 L 37.1655 24.7336 L 41.0179 21.2035 L 43.2539 31.2891 L 43.2539 31.2891 " +
            "C 43.7993 32.2877 45.0510 32.6550 46.0496 32.1095 " +
            "C 46.6283 31.7933 47.0207 31.2192 47.1052 30.5652 " +
            "L 49.0051 11.3437 L 49.0051 11.3437 " +
            "C 49.2581 10.2900 48.6089 9.2307 47.5552 8.9778 " +
            "C 47.3103 8.9190 47.0563 8.9079 46.8072 8.9451 Z"

    // ── Merge left (CPManeuverType 13 KeepLeft) ──
    const val MERGE_LEFT =
        "M 34.3000 56.0000 C 36.2330 56.0000 37.8000 54.4330 37.8000 52.5000 " +
            "L 37.8000 45.8182 L 30.8000 45.8182 L 30.8000 52.5000 " +
            "C 30.8000 54.4330 32.3670 56.0000 34.3000 56.0000 Z " +
            "M 37.8000 45.8182 L 37.8000 45.8182 C 37.8000 41.6581 36.9348 37.5435 35.2593 33.7358 L 35.2593 33.7358 " +
            "C 33.5839 29.9280 31.1348 26.5104 28.0677 23.6999 L 23.3386 28.8608 L 23.3386 28.8608 " +
            "C 25.6900 31.0155 27.5677 33.6357 28.8522 36.5550 L 28.8522 36.5550 " +
            "C 30.1367 39.4743 30.8000 42.6288 30.8000 45.8182 L 30.8000 45.8182 Z " +
            "M 23.5636 19.5726 L 18.8345 24.7336 L 23.3386 28.8608 L 28.0677 23.6999 Z " +
            "M 9.1928 8.9451 L 28.5067 9.1625 L 28.5067 9.1625 " +
            "C 29.6436 9.2093 30.5273 10.1688 30.4806 11.3056 " +
            "C 30.4535 11.9646 30.1126 12.5707 29.5636 12.9361 " +
            "L 19.7112 16.0425 L 23.5636 19.5726 L 18.8345 24.7336 L 14.9821 21.2035 L 12.7461 31.2891 L 12.7461 31.2891 " +
            "C 12.2007 32.2877 10.9490 32.6550 9.9504 32.1095 " +
            "C 9.3717 31.7933 8.9793 31.2192 8.8948 30.5652 " +
            "L 6.9949 11.3437 L 6.9949 11.3437 " +
            "C 6.7419 10.2900 7.3911 9.2307 8.4448 9.7780 " +
            "C 8.6897 8.9190 8.9437 8.9079 9.1928 8.9451 Z"

    // ── Exit right (CPManeuverType 9 OnRamp / 23 HighwayOffRampRight) ──
    const val EXIT_RIGHT =
        "M 17.1244 56.0000 C 15.1914 56.0000 13.6244 54.4330 13.6244 52.5000 " +
            "L 13.6244 45.8182 L 20.6244 45.8182 L 20.6244 52.5000 " +
            "C 20.6244 54.4330 19.0574 56.0000 17.1244 56.0000 Z " +
            "M 13.6244 45.8182 L 13.6244 45.8182 C 13.6244 42.1412 14.3486 38.5002 15.7558 35.1030 L 15.7558 35.1030 " +
            "C 17.1629 31.7059 19.2254 28.6192 21.8254 26.0192 L 26.7752 30.9689 L 26.7752 30.9689 " +
            "C 24.8251 32.9190 23.2783 35.2340 22.2229 37.7818 L 22.2229 37.7818 " +
            "C 21.1676 40.3297 20.6244 43.0604 20.6244 45.8182 L 20.6244 45.8182 Z " +
            "M 26.1452 21.6994 L 31.0949 26.6492 L 26.7752 30.9689 L 21.8254 26.0192 Z " +
            "M 40.0387 10.4552 L 20.7527 11.5149 L 20.7527 11.5149 " +
            "C 19.6190 11.6111 18.7779 12.6083 18.8742 13.7420 " +
            "C 18.9300 14.3991 19.2970 14.9898 19.8615 15.3309 " +
            "L 29.8400 18.0046 L 26.1452 21.6994 L 31.0949 26.6492 L 34.7897 22.9544 L 37.4634 32.9329 L 37.4634 32.9329 " +
            "C 38.0519 33.9067 39.3184 34.2191 40.2923 33.6305 " +
            "C 40.8567 33.2894 41.2237 32.6988 41.2795 32.0416 " +
            "L 42.3392 12.7556 L 42.3392 12.7556 " +
            "C 42.5459 11.6919 41.8512 10.6619 40.7875 10.4552 " +
            "C 40.5402 10.4071 40.2860 10.4071 40.0387 10.4552 Z"

    // ── Exit left (CPManeuverType 8 OffRamp / 22 HighwayOffRampLeft) ──
    const val EXIT_LEFT =
        "M 38.8756 56.0000 C 40.8086 56.0000 42.3756 54.4330 42.3756 52.5000 " +
            "L 42.3756 45.8182 L 35.3756 45.8182 L 35.3756 52.5000 " +
            "C 35.3756 54.4330 36.9426 56.0000 38.8756 56.0000 Z " +
            "M 42.3756 45.8182 L 42.3756 45.8182 C 42.3756 42.1412 41.6514 38.5002 40.2442 35.1030 L 40.2442 35.1030 " +
            "C 38.8371 31.7059 36.7746 28.6192 34.1746 26.0192 L 29.2248 30.9689 L 29.2248 30.9689 " +
            "C 31.1749 32.9190 32.7217 35.2340 33.7771 37.7818 L 33.7771 37.7818 " +
            "C 34.8324 40.3297 35.3756 43.0604 35.3756 45.8182 L 35.3756 45.8182 Z " +
            "M 29.8548 21.6994 L 24.9051 26.6492 L 29.2248 30.9689 L 34.1746 26.0192 Z " +
            "M 15.9613 10.4552 L 35.2473 11.5149 L 35.2473 11.5149 " +
            "C 36.3810 11.6111 37.2221 12.6083 37.1258 13.7420 " +
            "C 37.0700 14.3991 36.7030 14.9898 36.1385 15.3309 " +
            "L 26.1600 18.0046 L 29.8548 21.6994 L 24.9051 26.6492 L 21.2103 22.9544 L 18.5366 32.9329 L 18.5366 32.9329 " +
            "C 17.9481 33.9067 16.6816 34.2191 15.7077 33.6305 " +
            "C 15.1433 33.2894 14.7763 32.6988 14.7205 32.0416 " +
            "L 13.6608 12.7556 L 13.6608 12.7556 " +
            "C 13.4541 11.6919 14.1488 10.6619 15.2125 10.4552 " +
            "C 15.4598 10.4071 15.7140 10.4071 15.9613 10.4552 Z"

    // ── No-turn fallback (CPManeuverType 0) — same as STRAIGHT for now ──
    const val NO_TURN = STRAIGHT
}

/**
 * Minimal SVG path-data parser. Accepts the subset Apple's MapKit emits:
 *   M x y       — moveTo absolute
 *   L x y       — lineTo absolute
 *   C x1 y1 x2 y2 x3 y3 — cubic bezier absolute
 *   Z           — closePath
 *
 * Spaces separate tokens. Commands are uppercase only (Apple's output convention). Decimal
 * numbers may be negative. No relative commands, no `H/V/S/T/A/Q` — Apple doesn't use them.
 *
 * Public for testability; called by the dispatcher to convert [StaticPathConstants] into
 * android.graphics.Path objects.
 */
internal object SvgPathParser {
    fun parse(svgPath: String): Path {
        val path = Path()
        val tokens = svgPath.trim().split(Regex("\\s+"))
        var i = 0
        while (i < tokens.size) {
            when (val cmd = tokens[i]) {
                "M" -> {
                    val x = tokens[i + 1].toFloat()
                    val y = tokens[i + 2].toFloat()
                    path.moveTo(x, y)
                    i += 3
                }
                "L" -> {
                    val x = tokens[i + 1].toFloat()
                    val y = tokens[i + 2].toFloat()
                    path.lineTo(x, y)
                    i += 3
                }
                "C" -> {
                    val x1 = tokens[i + 1].toFloat()
                    val y1 = tokens[i + 2].toFloat()
                    val x2 = tokens[i + 3].toFloat()
                    val y2 = tokens[i + 4].toFloat()
                    val x3 = tokens[i + 5].toFloat()
                    val y3 = tokens[i + 6].toFloat()
                    path.cubicTo(x1, y1, x2, y2, x3, y3)
                    i += 7
                }
                "Z", "z" -> {
                    path.close()
                    i += 1
                }
                else -> throw IllegalArgumentException("Unsupported SVG path command: '$cmd' at token $i")
            }
        }
        return path
    }
}
