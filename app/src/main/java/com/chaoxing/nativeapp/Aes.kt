package com.chaoxing.nativeapp

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 对应原 api/cipher.py：
 * AES/CBC + PKCS7 填充，key 与 iv 均为 gc.AESKey 的 UTF-8 字节，输出 Base64。
 */
object Aes {
    fun encrypt(plain: String): String {
        val key = Cfg.AES_KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }
}
