package com.jabra.mgb

/**
 * PLAYER PLAN P11: the "Playlist" page of the side panel = the other media files of the same folder (PlayerActivity.sibs,
 * same natural order as the previous / next buttons). The current file is accent + check and the list scrolls to it.
 */
object PlayerQueue {
    fun page(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Playlist") {
        val n = a.queueSize()
        val cur = a.queueIndex()
        if (n < 2) {
            note("No other videos in this folder.")
        } else {
            note((cur + 1).toString() + " of " + n)
            for (i in 0 until n) {
                if (i == cur) mark()
                radio(a.queueName(i), null, i == cur) {
                    if (i != cur) {
                        a.panel.close()
                        a.openAt(i)
                    }
                }
            }
        }
    }
}
