package com.jabra.mgb

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.File
import java.util.Locale

/**
 * 0.0.280: subtitle delay for subtitle FILES (.srt .vtt .ass .ssa). The player cannot shift a loaded track, so the file is
 * rewritten with every start / end time moved by delayMs into cacheDir/subdelay and the player is prepared again at the same
 * position (PlayerActivity.applySubDelay). The original is never touched; the shift is always computed from the original.
 * Not possible for subtitle tracks that live inside the video file (mkv / mp4).
 */
object SubDelay {
    private val orig = HashMap<String, Uri>() // shifted file uri -> original uri
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun supported(mime: String?): Boolean = mime == MimeTypes.APPLICATION_SUBRIP || mime == MimeTypes.TEXT_VTT || mime == MimeTypes.TEXT_SSA

    fun origOf(u: Uri): Uri = orig[u.toString()] ?: u

    fun reset() { orig.clear() }

    fun fmtDelay(ms: Long): String {
        val s = String.format(Locale.US, "%.1f", Math.abs(ms) / 1000.0) + " s"
        return if (ms > 0) "+$s (later)" else if (ms < 0) "-$s (earlier)" else "0.0 s"
    }

    /** The shifted copy of [src] (a new file uri), [src] itself when delayMs is 0, null when it cannot be done. */
    fun shifted(ctx: Context, src: Uri, mime: String?, delayMs: Long): Uri? {
        if (delayMs == 0L) return src
        if (!supported(mime)) return null
        return try {
            val bytes = ctx.contentResolver.openInputStream(src)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_BYTES) return null
            var text = String(bytes, Charsets.UTF_8)
            if (text.startsWith("\uFEFF")) text = text.substring(1)
            val out = if (mime == MimeTypes.TEXT_SSA) shiftAss(text, delayMs) else shiftCue(text, delayMs, mime == MimeTypes.TEXT_VTT)
            val dir = File(ctx.cacheDir, "subdelay").apply { mkdirs() }
            val key = Integer.toHexString(src.toString().hashCode())
            dir.listFiles()?.forEach { if (it.name.startsWith(key + "_")) it.delete() } // older shifts of the same file
            val ext = if (mime == MimeTypes.TEXT_VTT) "vtt" else if (mime == MimeTypes.TEXT_SSA) "ass" else "srt"
            val f = File(dir, key + "_" + delayMs + "." + ext)
            f.writeText(out, Charsets.UTF_8)
            val u = Uri.fromFile(f)
            orig[u.toString()] = src
            u
        } catch (e: Exception) {
            android.util.Log.e("MGB.subdelay", "shift", e)
            null
        }
    }

    /** "00:01:02,500" / "01:02.500" / "1:02:03.50" -> ms, null when it is not a time. */
    private fun parse(s: String): Long? = try {
        val p = s.trim().replace(',', '.').split(':')
        val sec = p.last().toDouble()
        val m = if (p.size >= 2) p[p.size - 2].toLong() else 0L
        val h = if (p.size >= 3) p[p.size - 3].toLong() else 0L
        Math.round(sec * 1000.0) + (m + h * 60L) * 60000L
    } catch (_: Exception) { null }

    private fun fmt(ms0: Long, vtt: Boolean): String {
        val ms = if (ms0 < 0) 0L else ms0
        return String.format(Locale.US, "%02d:%02d:%02d%s%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, if (vtt) "." else ",", ms % 1000)
    }

    private fun fmtAss(ms0: Long): String {
        val cs = (if (ms0 < 0) 0L else ms0) / 10
        return String.format(Locale.US, "%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100)
    }

    /** SRT / VTT: only the lines with "-->" change; cue settings after the end time (VTT) stay. */
    private fun shiftCue(text: String, d: Long, vtt: Boolean): String {
        val sb = StringBuilder(text.length + 64)
        for (line in text.split("\n")) {
            val i = line.indexOf("-->")
            if (i > 0) {
                val a = parse(line.substring(0, i))
                val rest = line.substring(i + 3).trim()
                val sp = rest.indexOfFirst { it == ' ' || it == '\t' }
                val b = parse(if (sp < 0) rest else rest.substring(0, sp))
                if (a != null && b != null) {
                    sb.append(fmt(a + d, vtt)).append(" --> ").append(fmt(b + d, vtt))
                    if (sp >= 0) sb.append(rest.substring(sp))
                    if (line.endsWith("\r")) sb.append('\r')
                    sb.append('\n')
                    continue
                }
            }
            sb.append(line).append('\n')
        }
        return sb.toString().removeSuffix("\n")
    }

    /** ASS / SSA: Dialogue lines, columns Start / End read from the [Events] Format line (default 1 and 2). */
    private fun shiftAss(text: String, d: Long): String {
        var si = 1
        var ei = 2
        var cols = 10
        val sb = StringBuilder(text.length + 64)
        for (line in text.split("\n")) {
            val t = line.trimStart()
            if (t.startsWith("Format:", true) && t.contains("Start", true) && t.contains("End", true)) {
                val names = t.substring(7).split(",").map { it.trim().lowercase() }
                if (names.size >= 3) { cols = names.size; si = names.indexOf("start").takeIf { it >= 0 } ?: 1; ei = names.indexOf("end").takeIf { it >= 0 } ?: 2 }
            }
            if (t.startsWith("Dialogue:", true) || t.startsWith("Comment:", true)) {
                val head = t.substring(0, t.indexOf(':') + 1)
                val f = t.substring(head.length).trimStart().split(",", limit = cols).toMutableList()
                if (f.size == cols && si < cols && ei < cols) {
                    val a = parse(f[si])
                    val b = parse(f[ei])
                    if (a != null && b != null) {
                        f[si] = fmtAss(a + d)
                        f[ei] = fmtAss(b + d)
                        sb.append(head).append(' ').append(f.joinToString(",")).append('\n')
                        continue
                    }
                }
            }
            sb.append(line).append('\n')
        }
        return sb.toString().removeSuffix("\n")
    }
}
