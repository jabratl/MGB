package com.jabra.mgb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * S30: small secret vault. AES-GCM key lives in the Android Keystore (never leaves the chip / TEE),
 * encrypt() / decrypt() turn a string into "base64(iv + cipher)". unlock() asks for fingerprint / face / screen lock
 * before secrets are shown or filled; once unlocked it stays open for 2 minutes. If the phone has no screen lock
 * at all, unlock() lets the action through (the data is still encrypted at rest).
 */
object Vault {
    private const val ALIAS = "mgb_vault"
    private const val OPEN_MS = 120_000L
    private var unlockedAt = 0L

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = try {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    } catch (e: Exception) { null }

    /** Run [ok] after the user proved it is them (or at once when unlocked lately / no screen lock). */
    fun unlock(act: FragmentActivity, why: String, ok: () -> Unit) {
        if (System.currentTimeMillis() - unlockedAt < OPEN_MS) { ok(); return }
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = try { BiometricManager.from(act).canAuthenticate(auth) } catch (e: Exception) { -99 }
        if (can != BiometricManager.BIOMETRIC_SUCCESS) { ok(); return }
        try {
            val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockedAt = System.currentTimeMillis()
                    ok()
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(why).setAllowedAuthenticators(auth).build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            Util.toast(act, "Cannot ask for unlock")
        }
    }
}
