// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 登录态落盘格式：12 字节 IV + GCM 密文。密钥在 Android Keystore 里，不写磁盘。 */
internal object AccountCipher {

    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == IV_SIZE) { "账号加密格式不兼容" }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        require(bytes.size >= IV_SIZE + 16) { "登录态数据不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size))
    }

    private const val IV_SIZE = 12
}
