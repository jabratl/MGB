package com.jabra.mgb


/**
 * PLAYER PLAN P1: page builders for PlayerPanel. root() = what the 3-dot button opens.
 * Rows whose page is not built yet (P2-P5) close the panel and open the old dialog, so nothing is lost.
 */
object PlayerPages {
    fun root(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Player") {
        // animated close brings the controls back, the old dialog opens on top
        fun go(f: () -> Unit) { a.panel.close(); f() }
        val p = a.player
        val left = if (a.sleepAt > 0) ((a.sleepAt - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(1) else 0L
        row(R.drawable.ic_pl_cc, "Subtitles", a.subSummary(), true) { go { a.showSubtitleDialog() } }
        row(R.drawable.ic_list, "Audio track", "", true) { go { a.showAudioDialog() } }
        row(R.drawable.ic_pl_quality, "Quality", if (a.qualityCap() > 0) a.qualityCap().toString() + "p" else "Auto", true) { a.panel.push(qualityPage(a)) }
        row(R.drawable.ic_pl_speed, "Playback speed", a.speedText(p?.playbackParameters?.speed ?: 1f), true) { a.panel.push(PlayerSpeed.page(a)) }
        row(R.drawable.ic_pl_repeat, "Repeat", a.repeatLabel(), true) { a.panel.push(repeatPage(a)) }
        row(R.drawable.ic_clock, "Sleep timer", if (left > 0) "$left min" else "Off", true) { go { a.showSleepDialog() } }
        section("More")
        toggle(R.drawable.ic_pl_audio, "Background audio", PlayerService.on(a)) { a.toggleBgAudio() }
        row(R.drawable.ic_pl_pip, "Picture in picture") { go { a.enterPip() } }
        row(R.drawable.ic_info, "Video info") { go { a.showInfo() } }
        row(R.drawable.ic_open, "Open in another app") { go { a.openExternal() } }
    }

    /** P3: Auto + one row per available video height (highest first). A single-quality file only shows Auto and a note. */
    fun qualityPage(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Quality") {
        val cap = a.qualityCap()
        val opts = a.videoQualities()
        radio("Auto", "Best for your connection", cap == 0) { a.setQuality(0); a.panel.refresh() }
        if (opts.size < 2) {
            note("Only one quality in this video.")
        } else {
            for (o in opts) {
                val h = o.first
                radio(h.toString() + "p", if (o.second > 0) String.format(java.util.Locale.US, "%.1f Mbps", o.second / 1e6) else null, cap == h) {
                    a.setQuality(h)
                    a.panel.refresh()
                }
            }
        }
    }

    /** P3: Off / this video / all videos of the folder (loops back to the first file after the last). */
    fun repeatPage(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Repeat") {
        val m = a.repeatMode()
        radio("Off", null, m == 0) { a.setRepeat(0); a.panel.refresh() }
        radio("Repeat this video", null, m == 1) { a.setRepeat(1); a.panel.refresh() }
        if (a.hasSiblings()) radio("Repeat folder", "All videos in this folder, in order", m == 2) { a.setRepeat(2); a.panel.refresh() }
        else note("No other videos in this folder to repeat.")
    }
}
