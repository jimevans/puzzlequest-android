package com.thepuzzlequest.puzzlequest

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import com.thepuzzlequest.puzzlequest.core.TokenStore
import com.thepuzzlequest.puzzlequest.core.TokenVault
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Each server's session token, encrypted with a key kept in the Android
 * Keystore, which never leaves the phone (on most phones, it's in secure
 * hardware). The encrypted tokens are in the app's own preferences, which
 * aren't backed up, one entry for each server, named by its origin.
 */
class KeystoreTokenVault(
    context: Context,
    /** The preferences file and key's name; tests use their own. */
    private val name: String = "com.thepuzzlequest.puzzlequest.session",
) : TokenVault {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun tokens(server: ServerOrigin): TokenStore =
        KeystoreTokenStore(preferences, keyName = name, entry = "token ${server.url}")

    override fun forgetAll() {
        preferences.edit(commit = true) { clear() }
    }
}

/** One server's token, in the vault's preferences under [entry]. */
private class KeystoreTokenStore(
    private val preferences: SharedPreferences,
    private val keyName: String,
    private val entry: String,
) : TokenStore {
    override fun read(): String? {
        val stored = preferences.getString(entry, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // The key is gone (as after the phone's lock screen was reset) or
            // the data is damaged: there's no session to restore.
            delete()
            null
        } catch (_: IllegalArgumentException) {
            delete()
            null
        }
    }

    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit(commit = true) { putString(entry, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    override fun delete() {
        preferences.edit(commit = true) { remove(entry) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyName, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(keyName, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
