package com.jabra.mgb

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

/**
 * D4: "Extract audio (.m4a)" for a saved video file (Downloads > file menu). The AAC audio track is copied as it is
 * (MediaExtractor -> MediaMuxer, no re-encoding, no quality loss) into Downloads/MGB. Audio that is not AAC (WebM / Opus)
 * is refused with a clear message: Android has no built-in encoder for MP3 or for re-encoding here, so there is no transcode.
 * Streams that carry a separate audio track are downloaded audio-only by the engine (downloader.js, variant "audio").
 */
object AudioExtract {
    fun run(act: Activity, src: Uri?, title: String) {
        if (src == null) { Util.toast(act, "File not found"); return }
        Util.toast(act, "Extracting audio...")
        val app = act.applicationContext
        Thread {
            val msg = try { "Saved " + extract(app, src, title) + " in Downloads/MGB" } catch (e: Exception) { e.message ?: "Could not extract audio from this file" }
            act.runOnUiThread { Util.toast(act, msg) }
        }.start()
    }

    private fun extract(ctx: Context, src: Uri, title: String): String {
        val tmp = File(ctx.cacheDir, "mgb_audio_extract.m4a")
        tmp.delete()
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, src, null)
            var ti = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) throw IllegalStateException("No audio track in this file")
            if (fmt.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") throw IllegalStateException("This audio is not AAC, so .m4a cannot be made without re-encoding")
            ex.selectTrack(ti)
            mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = mux.addTrack(fmt)
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(out, buf, info)
                ex.advance()
            }
            mux.stop()
        } finally {
            try { mux?.release() } catch (e: Exception) { /* already released */ }
            ex.release()
        }
        val name = title.substringBeforeLast('.', title).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "audio" } + ".m4a"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = ctx.contentResolver
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MGB")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val dst = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Could not create the file in Downloads")
                cr.openOutputStream(dst)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                cr.update(dst, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MGB")
                dir.mkdirs()
                tmp.copyTo(File(dir, name), true)
            }
        } finally { tmp.delete() }
        return name
    }
}
