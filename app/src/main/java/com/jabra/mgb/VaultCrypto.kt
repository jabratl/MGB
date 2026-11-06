package com.jabra.mgb

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Seekable read source for an encrypted blob (a SAF file, or a byte array in the self test). */
interface RandomSource {
    fun size(): Long
    /** Reads up to [len] bytes at [pos]. Returns the count, or -1 at the end. */
    fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int
    fun close() {}
}

class ByteArraySource(private val data: ByteArray) : RandomSource {
    override fun size(): Long = data.size.toLong()
    override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), dst, off, n)
        return n
    }
}

/**
 * V1: crypto core of the Thazir Vault (no UI, no storage). Not related to the old [Vault] object (Keystore helper for passwords).
 *
 * Why not the Android Keystore: Keystore keys are deleted when the app is uninstalled, but the vault folder must stay readable after a
 * reinstall. So the container key is ENVELOPE encrypted:
 *   DEK  = random 256-bit key that encrypts every vault byte.
 *   KEK  = PBKDF2-HMAC-SHA256(passphrase, salt, iter) wraps the DEK  (wrapPass).
 *   RK   = PBKDF2(recovery key, recSalt, recIter) wraps the same DEK (wrapRec). Recovery key = 160 random bits, shown once.
 * Changing the passphrase only re-wraps the DEK (no data is re-encrypted).
 * PBKDF2 is implemented over javax.crypto.Mac because SecretKeyFactory "PBKDF2WithHmacSHA256" needs API 26 and minSdk is 24.
 *
 * Header (vault.hdr, JSON, unknown fields are copied through): v, kdf, iter, salt, wrapPass, recIter, recSalt, wrapRec, check, created.
 *   wrap* / check / encStr = base64( iv(12) + AES-GCM(128-bit tag) ).
 *
 * Blob layout (d/<id>.bin): "THV1"(4) | chunkSize int32 BE (4) | noncePrefix (8) | chunks...
 *   chunk i = AES-GCM(DEK, nonce = noncePrefix + i as int32 BE, aad = [1 if last chunk else 0]) of up to chunkSize plain bytes (+16 tag).
 *   All chunks are full size except the last one. The "last" flag in the AAD means a truncated file fails to decrypt.
 *   Fixed chunk size = random access: plain offset p lives in chunk p / chunkSize.
 */
object VaultCrypto {
    const val HDR_V = 1
    const val ITER = 250_000
    const val REC_ITER = 10_000
    const val CHUNK = 64 * 1024

    private val rnd = SecureRandom()
    private val MAGIC = "THV1".toByteArray(Charsets.US_ASCII)
    private val AAD_PASS = "thv-wrap-pass".toByteArray(Charsets.US_ASCII)
    private val AAD_REC = "thv-wrap-rec".toByteArray(Charsets.US_ASCII)
    private val CHECK = "THV-OK".toByteArray(Charsets.US_ASCII)
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }
    fun wipe(b: ByteArray?) { if (b != null) java.util.Arrays.fill(b, 0.toByte()) }
    fun wipe(c: CharArray?) { if (c != null) java.util.Arrays.fill(c, '\u0000') }
    fun ctEq(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun newId(): String = hex(randomBytes(8))

    class Header(
        val v: Int, val iter: Int, val salt: ByteArray, val wrapPass: ByteArray,
        val recIter: Int, val recSalt: ByteArray, val wrapRec: ByteArray, val check: ByteArray,
        val created: Long, private val raw: JSONObject
    ) {
        fun toJson(): JSONObject {
            val o = JSONObject(raw.toString())
            o.put("v", v).put("kdf", "PBKDF2WithHmacSHA256").put("iter", iter)
            o.put("salt", b64(salt)).put("wrapPass", b64(wrapPass))
            o.put("recIter", recIter).put("recSalt", b64(recSalt)).put("wrapRec", b64(wrapRec))
            o.put("check", b64(check)).put("created", created)
            return o
        }
        fun copy(
            iter: Int = this.iter, salt: ByteArray = this.salt, wrapPass: ByteArray = this.wrapPass,
            recIter: Int = this.recIter, recSalt: ByteArray = this.recSalt, wrapRec: ByteArray = this.wrapRec
        ) = Header(v, iter, salt, wrapPass, recIter, recSalt, wrapRec, check, created, raw)
        companion object {
            fun parse(text: String): Header {
                val o = JSONObject(text)
                return Header(
                    o.getInt("v"), o.getInt("iter"), unb64(o.getString("salt")), unb64(o.getString("wrapPass")),
                    o.getInt("recIter"), unb64(o.getString("recSalt")), unb64(o.getString("wrapRec")),
                    unb64(o.getString("check")), o.optLong("created", 0L), o
                )
            }
        }
    }

    class Created(val header: Header, val dek: ByteArray, val recovery: String)

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ---------- key derivation ----------

    /** PBKDF2-HMAC-SHA256, one 32-byte block. */
    fun pbkdf2(pass: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val pw = String(pass).toByteArray(Charsets.UTF_8)
        if (pw.isEmpty()) throw IllegalArgumentException("empty passphrase")
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pw, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iter) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t
        } finally { wipe(pw) }
    }

    // ---------- AES-GCM helpers ----------

    private fun gcmEnc(key: ByteArray, plain: ByteArray, aad: ByteArray?): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    private fun gcmDec(key: ByteArray, enc: ByteArray, aad: ByteArray?): ByteArray? = try {
        if (enc.size < 12 + 16) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, enc, 0, 12))
            if (aad != null) c.updateAAD(aad)
            c.doFinal(enc, 12, enc.size - 12)
        }
    } catch (e: Exception) { null }

    /** Text -> "base64(iv + cipher)" with the DEK (used for the index). */
    fun encStr(dek: ByteArray, plain: String): String = b64(gcmEnc(dek, plain.toByteArray(Charsets.UTF_8), null))
    fun decStr(dek: ByteArray, enc: String): String? = try {
        gcmDec(dek, unb64(enc.trim()), null)?.let { String(it, Charsets.UTF_8) }
    } catch (e: Exception) { null }

    // ---------- recovery key ----------

    /** 32 base32 characters (160 bits) shown as XXXX-XXXX-... (8 groups). */
    fun newRecoveryKey(): String {
        val b = randomBytes(20)
        val sb = StringBuilder()
        var bits = 0; var acc = 0
        for (x in b) {
            acc = (acc shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(B32[(acc shr (bits - 5)) and 31]); bits -= 5 }
            acc = acc and ((1 shl bits) - 1)
        }
        return sb.chunked(4).joinToString("-")
    }

    /** Upper case, only A-Z 2-7: the user may type spaces/dashes/lower case. */
    fun normRecovery(s: String): String = s.uppercase().filter { it in B32 }

    // ---------- vault header ----------

    fun create(pass: CharArray, iter: Int = ITER, recIter: Int = REC_ITER): Created {
        val dek = randomBytes(32)
        val salt = randomBytes(16)
        val kek = pbkdf2(pass, salt, iter)
        val rec = newRecoveryKey()
        val recSalt = randomBytes(16)
        val rk = pbkdf2(normRecovery(rec).toCharArray(), recSalt, recIter)
        try {
            val h = Header(
                HDR_V, iter, salt, gcmEnc(kek, dek, AAD_PASS), recIter, recSalt, gcmEnc(rk, dek, AAD_REC),
                gcmEnc(dek, CHECK, null), System.currentTimeMillis(), JSONObject()
            )
            return Created(h, dek, rec)
        } finally { wipe(kek); wipe(rk) }
    }

    /** True when [dek] is the key of this vault (the check blob decrypts). */
    fun verifyDek(h: Header, dek: ByteArray): Boolean = gcmDec(dek, h.check, null)?.let { ctEq(it, CHECK) } == true

    /** Returns the DEK, or null when the passphrase is wrong. Caller must wipe the result when done. */
    fun openPass(h: Header, pass: CharArray): ByteArray? {
        val kek = pbkdf2(pass, h.salt, h.iter)
        try {
            val dek = gcmDec(kek, h.wrapPass, AAD_PASS) ?: return null
            if (!verifyDek(h, dek)) { wipe(dek); return null }
            return dek
        } finally { wipe(kek) }
    }

    fun openRecovery(h: Header, recovery: String): ByteArray? {
        val n = normRecovery(recovery)
        if (n.length != 32) return null
        val rk = pbkdf2(n.toCharArray(), h.recSalt, h.recIter)
        try {
            val dek = gcmDec(rk, h.wrapRec, AAD_REC) ?: return null
            if (!verifyDek(h, dek)) { wipe(dek); return null }
            return dek
        } finally { wipe(rk) }
    }

    /** Change passphrase: new salt, DEK wrapped again. Nothing else changes. */
    fun rewrapPass(h: Header, dek: ByteArray, newPass: CharArray, iter: Int = ITER): Header {
        val salt = randomBytes(16)
        val kek = pbkdf2(newPass, salt, iter)
        try { return h.copy(iter = iter, salt = salt, wrapPass = gcmEnc(kek, dek, AAD_PASS)) } finally { wipe(kek) }
    }

    /** New recovery key (the old one stops working). Returns the new header and the key to show once. */
    fun newRecovery(h: Header, dek: ByteArray, recIter: Int = REC_ITER): Pair<Header, String> {
        val rec = newRecoveryKey()
        val recSalt = randomBytes(16)
        val rk = pbkdf2(normRecovery(rec).toCharArray(), recSalt, recIter)
        try { return Pair(h.copy(recIter = recIter, recSalt = recSalt, wrapRec = gcmEnc(rk, dek, AAD_REC)), rec) } finally { wipe(rk) }
    }

    // ---------- blobs (files, pages) ----------

    private fun be32(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun nonce(prefix: ByteArray, idx: Long): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, 8)
        System.arraycopy(be32(idx.toInt()), 0, n, 8, 4)
        return n
    }

    /** Encrypting OutputStream: wraps the raw (SAF) stream; close() writes the last chunk and closes [out]. */
    class BlobWriter(private val out: OutputStream, dek: ByteArray) : OutputStream() {
        private val key = SecretKeySpec(dek.copyOf(), "AES")
        private val prefix = randomBytes(8)
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var idx = 0L
        private var closed = false
        var plainBytes = 0L
            private set

        init { out.write(MAGIC); out.write(be32(CHUNK)); out.write(prefix) }

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                if (n == CHUNK) emit(false)
                val c = minOf(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, c)
                n += c; o += c; l -= c; plainBytes += c
            }
        }

        private fun emit(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, idx)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            out.write(c.doFinal(buf, 0, n))
            idx++; n = 0
        }

        override fun flush() { out.flush() }

        override fun close() {
            if (closed) return
            closed = true
            try { emit(true); out.flush() } finally { try { out.close() } catch (e: Exception) { }; wipe(buf) }
        }
    }

    /** Random-access decrypting reader over an encrypted blob. Throws IOException when the file is damaged. */
    class BlobReader(private val src: RandomSource, dek: ByteArray) {
        private val key = SecretKeySpec(dek.copyOf(), "AES")
        private val chunk: Int
        private val prefix = ByteArray(8)
        private val nChunks: Long
        private val lastLen: Long
        val plainSize: Long
        private var cacheIdx = -1L
        private var cache: ByteArray? = null

        init {
            val h = ByteArray(16)
            if (!readFully(src, 0, h, 0, 16)) throw IOException("short blob")
            for (i in 0 until 4) if (h[i] != MAGIC[i]) throw IOException("not a vault blob")
            chunk = ((h[4].toInt() and 0xff) shl 24) or ((h[5].toInt() and 0xff) shl 16) or ((h[6].toInt() and 0xff) shl 8) or (h[7].toInt() and 0xff)
            if (chunk < 1024 || chunk > 1024 * 1024) throw IOException("bad chunk size")
            System.arraycopy(h, 8, prefix, 0, 8)
            val total = src.size() - 16
            val full = chunk + 16L
            if (total < 16) throw IOException("short blob")
            nChunks = (total + full - 1) / full
            lastLen = total - (nChunks - 1) * full
            if (lastLen < 16) throw IOException("short blob")
            plainSize = (nChunks - 1) * chunk + (lastLen - 16)
        }

        private fun load(i: Long): ByteArray {
            if (i == cacheIdx) return cache!!
            val full = chunk + 16L
            val len = (if (i == nChunks - 1) lastLen else full).toInt()
            val ct = ByteArray(len)
            if (!readFully(src, 16 + i * full, ct, 0, len)) throw IOException("short blob")
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, i)))
                c.updateAAD(byteArrayOf(if (i == nChunks - 1) 1 else 0))
                val p = c.doFinal(ct)
                cacheIdx = i; cache = p
                return p
            } catch (e: Exception) { throw IOException("blob damaged or wrong key") }
        }

        /** Plain bytes at [pos]; -1 at the end. */
        fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (pos >= plainSize || len <= 0) return if (pos >= plainSize) -1 else 0
            var done = 0
            var p = pos
            while (done < len && p < plainSize) {
                val ch = load(p / chunk)
                val inChunk = (p % chunk).toInt()
                val n = minOf(len - done, ch.size - inChunk)
                System.arraycopy(ch, inChunk, dst, off + done, n)
                done += n; p += n
            }
            return done
        }

        fun copyTo(out: OutputStream, progress: ((Long, Long) -> Unit)? = null) {
            val b = ByteArray(chunk)
            var p = 0L
            while (true) {
                val n = readAt(p, b, 0, b.size)
                if (n <= 0) break
                out.write(b, 0, n); p += n
                progress?.invoke(p, plainSize)
            }
            wipe(b)
        }

        fun close() { src.close(); wipe(cache); cache = null }
    }

    private fun readFully(src: RandomSource, pos: Long, dst: ByteArray, off: Int, len: Int): Boolean {
        var got = 0
        while (got < len) {
            val n = src.readAt(pos + got, dst, off + got, len - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    // ---------- self test (V11 "Verify vault" calls this) ----------

    /** Returns null when everything works, else a short reason. Uses tiny KDF counts so it is fast. */
    fun selfTest(): String? {
        try {
            val pass = "correct horse".toCharArray()
            val cr = create(pass, 1000, 1000)
            val h = Header.parse(cr.header.toJson().toString())
            val d1 = openPass(h, pass) ?: return "open with passphrase failed"
            if (!ctEq(d1, cr.dek)) return "dek mismatch"
            if (openPass(h, "wrong pass".toCharArray()) != null) return "wrong passphrase accepted"
            val d2 = openRecovery(h, cr.recovery.lowercase().replace("-", " ")) ?: return "open with recovery key failed"
            if (!ctEq(d2, cr.dek)) return "recovery dek mismatch"
            val h2 = rewrapPass(h, cr.dek, "new pass".toCharArray(), 1000)
            if (openPass(h2, "new pass".toCharArray()) == null || openPass(h2, pass) != null) return "rewrap failed"
            val s = encStr(cr.dek, "hello")
            if (decStr(cr.dek, s) != "hello") return "string round trip failed"
            for (size in intArrayOf(0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 7)) {
                val plain = randomBytes(size)
                val bos = ByteArrayOutputStream()
                BlobWriter(bos, cr.dek).use { it.write(plain) }
                val enc = bos.toByteArray()
                val r = BlobReader(ByteArraySource(enc), cr.dek)
                if (r.plainSize != size.toLong()) return "blob size wrong for $size"
                val back = ByteArrayOutputStream()
                r.copyTo(back)
                if (!ctEq(back.toByteArray(), plain)) return "blob round trip failed for $size"
                if (size > 10) {
                    val at = size / 2
                    val part = ByteArray(5)
                    if (r.readAt(at.toLong(), part, 0, 5) != 5 || !ctEq(part, plain.copyOfRange(at, at + 5))) return "offset read failed for $size"
                }
                if (size > 0) {
                    val bad = enc.copyOf(); bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
                    var failed = false
                    try { BlobReader(ByteArraySource(bad), cr.dek).copyTo(ByteArrayOutputStream()) } catch (e: IOException) { failed = true }
                    if (!failed) return "tampered blob accepted ($size)"
                }
                if (size > CHUNK) {
                    val cut = enc.copyOf(enc.size - (CHUNK + 16))
                    var failed = false
                    try { BlobReader(ByteArraySource(cut), cr.dek).copyTo(ByteArrayOutputStream()) } catch (e: IOException) { failed = true }
                    if (!failed) return "truncated blob accepted ($size)"
                }
            }
            return null
        } catch (e: Exception) {
            return "self test crashed: " + e.javaClass.simpleName
        }
    }
}
