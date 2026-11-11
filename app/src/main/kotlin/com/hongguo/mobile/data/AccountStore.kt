// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hongguotv.core.AccountCredentials
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** 登录态加密存放；Keystore 密钥丢失时直接当作未登录，不保留半残数据。 */
class AccountStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("account_secure", Context.MODE_PRIVATE)

    fun read(): AccountCredentials? = runCatching {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        val plain = AccountCipher.decrypt(Base64.decode(raw, Base64.NO_WRAP), key())
        AccountCredentials.fromJson(String(plain, Charsets.UTF_8))
    }.getOrElse {
        prefs.edit().clear().apply()
        null
    }

    fun save(credentials: AccountCredentials) {
        val encrypted = AccountCipher.encrypt(
            credentials.toJson().toByteArray(Charsets.UTF_8),
            key(),
        )
        check(
            prefs.edit()
                .putString(KEY_SESSION, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit(),
        ) { "保存登录态失败" }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hongguo.mobile.account.v1"
        const val KEY_SESSION = "session"
    }
}
