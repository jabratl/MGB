package com.jabra.mgb

import androidx.media3.common.Player

/**
 * PLAYER PLAN P1: page builders for PlayerPanel. root() = what the 3-dot button opens.
 * Rows whose page is not built yet (P2-P5) close the panel and open the old dialog, so nothing is lost.
 */
object PlayerPages {
    fun root(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Player") {
        fun go(f: () -> Unit) { a.panel.close(); f() // animated close brings the controls back, the dialog opens on top }
        val p = a.player
        val left = if (a.sleepAt > 0) ((a.sleepAt - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(1) else 0L
        val rep = p?.repeatMode == Player.REPEAT_MODE_ONE
        row(R.drawable.ic_pl_cc, "Subtitles", a.subSummary(), true) { go { a.showSubtitleDialog() } }
        row(R.drawable.ic_list, "Audio track", "", true) { go { a.showAudioDialog() } }
        row(R.drawable.ic_pl_speed, "Playback speed", a.speedText(p?.playbackParameters?.speed ?: 1f), true) { go { a.showSpeedDialog() } }
        toggle(R.drawable.ic_pl_repeat, "Repeat this video", rep) { a.toggleRepeat() }
        row(R.drawable.ic_clock, "Sleep timer", if (left > 0) "$left min" else "Off", true) { go { a.showSleepDialog() } }
        section("More")
        toggle(R.drawable.ic_pl_audio, "Background audio", PlayerService.on(a)) { a.toggleBgAudio() }
        row(R.drawable.ic_pl_pip, "Picture in picture") { go { a.enterPip() } }
        row(R.drawable.ic_info, "Video info") { go { a.showInfo() } }
        row(R.drawable.ic_open, "Open in another app") { go { a.openExternal() } }
    }
}
