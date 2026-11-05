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
        val ao = a.audioOpts()
        row(R.drawable.ic_pl_cc, "Subtitles", a.subSummary(), true) { a.panel.push(PlayerSubs.page(a)) }
        row(R.drawable.ic_list, "Audio track", if (ao.size > 1) (ao.firstOrNull { it.selected }?.label ?: "") else "", true) {
            if (ao.isEmpty()) Util.toast(a, "No audio track") else a.panel.push(audioPage(a))
        }
        row(R.drawable.ic_pl_quality, "Quality", if (a.qualityCap() > 0) a.qualityCap().toString() + "p" else "Auto", true) { a.panel.push(qualityPage(a)) }
        row(R.drawable.ic_pl_speed, "Playback speed", a.speedText(p?.playbackParameters?.speed ?: 1f), true) { a.panel.push(PlayerSpeed.page(a)) }
        row(R.drawable.ic_pl_repeat, "Repeat", a.repeatLabel(), true) { a.panel.push(repeatPage(a)) }
        row(R.drawable.ic_pl_repeat, "A-B loop", a.loopSummary(), true) { a.panel.push(loopPage(a)) }
        row(R.drawable.ic_clock, "Sleep timer", sleepValue(a), true) { a.panel.push(sleepPage(a)) }
        if (a.hasSiblings()) row(R.drawable.ic_pl_playlist, "Playlist", (a.queueIndex() + 1).toString() + " of " + a.queueSize(), true) { a.panel.push(PlayerQueue.page(a)) }
        section("More")
        toggle(R.drawable.ic_pl_headphones, "Audio only", a.audioOnly) { a.toggleAudioOnly() }
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

    /** P12: Set A / Set B at the current position, Clear. The marks show on the seek bar. */
    fun loopPage(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("A-B loop") {
        val l = a.loop
        note("Playback goes back from B to A. Open this page at the moment you want, then tap Set A or Set B.")
        row(R.drawable.ic_add, "Set A here", if (l.a >= 0L) a.loopSummaryA() else "Not set") { a.setLoopA(); a.panel.refresh() }
        row(R.drawable.ic_add, "Set B here", if (l.b >= 0L) a.loopSummaryB() else "Not set") { a.setLoopB(); a.panel.refresh() }
        if (l.a >= 0L || l.b >= 0L) row(R.drawable.ic_close, "Clear loop") { a.clearLoop(); a.panel.refresh() }
    }

    /** P3: Off / this video / all videos of the folder (loops back to the first file after the last). */
    fun repeatPage(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Repeat") {
        val m = a.repeatMode()
        radio("Off", null, m == 0) { a.setRepeat(0); a.panel.refresh() }
        radio("Repeat this video", null, m == 1) { a.setRepeat(1); a.panel.refresh() }
        if (a.hasSiblings()) radio("Repeat folder", "All videos in this folder, in order", m == 2) { a.setRepeat(2); a.panel.refresh() }
        else note("No other videos in this folder to repeat.")
    }

    /** P4: sleep timer sentinel for setSleep: pause when this video ends. */
    const val SLEEP_END = -1
    private val SLEEP_MINS = intArrayOf(15, 30, 45, 60, 90)

    /** Root row value: "Off" / "12 min" / "End of video". */
    fun sleepValue(a: PlayerActivity): String {
        if (a.sleepAt == -1L) return "End of video"
        val m = a.sleepLeftMin()
        return if (m > 0) "$m min" else "Off"
    }

    /** P4: Off, 15 / 30 / 45 / 60 / 90 min, end of this video, +15 min quick add, custom slider 5-180 min. */
    fun sleepPage(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Sleep timer") {
        val end = a.sleepAt == -1L
        val m = a.sleepLeftMin()
        val on = end || m > 0
        fun set(min: Int) { a.setSleep(min); a.panel.refresh() }
        if (end) note("Pauses when this video ends.")
        else if (m > 0) note("Pauses in $m min")
        else note("Playback pauses when the time is up.")
        radio("Off", null, !on) { set(0) }
        for (x in SLEEP_MINS) {
            val sel = m > 0 && a.sleepPick == x
            radio("$x minutes", if (sel) "Pauses in $m min" else null, sel) { set(x) }
        }
        radio("End of this video", if (end) "Pauses when the video ends" else null, end) { set(SLEEP_END) }
        if (a.repeatMode() == 1) note("Repeat this video is on, so the video never ends.")
        if (m > 0) row(R.drawable.ic_add, "Add 15 min") { a.addSleep(15); a.panel.refresh() }
        section("Custom")
        slider("Minutes", 5f, 180f, 5f, if (m > 0) m.toFloat() else 30f, { v -> v.toInt().toString() + " min" }, { _ -> }, { v -> a.setSleep(v.toInt()) })
    }

    /** One audio track row (built by PlayerActivity.audioOpts). */
    class AudioOpt(val label: String, val sub: String?, val selected: Boolean, val pick: () -> Unit)

    fun channelName(n: Int): String? = when (n) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> if (n > 0) "$n ch" else null
    }

    fun codecName(mime: String?): String? {
        if (mime == null) return null
        return when (mime.lowercase()) {
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "AC3"
            "audio/eac3" -> "E-AC3"
            "audio/eac3-joc" -> "E-AC3"
            "audio/vnd.dts" -> "DTS"
            "audio/vnd.dts.hd" -> "DTS-HD"
            "audio/true-hd" -> "TrueHD"
            "audio/opus" -> "Opus"
            "audio/mpeg" -> "MP3"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            else -> mime.substringAfter('/').uppercase()
        }
    }

    /** P4: audio tracks as radio rows (language, channels, codec). Audio delay is NOT included (NEXT). */
    fun audioPage(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Audio track") {
        val opts = a.audioOpts()
        if (opts.isEmpty()) {
            note("No audio track in this video.")
        } else {
            for (o in opts) radio(o.label, o.sub, o.selected) { o.pick() }
            if (opts.size == 1) note("This video has only one audio track.")
        }
    }
}
