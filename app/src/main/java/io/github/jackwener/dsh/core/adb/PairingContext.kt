// Native SPAKE2 implementation adapted from Shizuku+, Apache-2.0.
package io.github.jackwener.dsh.core.adb

import java.io.Closeable

class PairingContext(password: ByteArray) : Closeable {
    private var pointer = nativeConstructor(true, password).also { check(it != 0L) { "SPAKE2 initialization failed" } }
    fun message(): ByteArray = checkNotNull(nativeMsg(pointer))
    fun initialize(peer: ByteArray) { check(nativeInitCipher(pointer, peer)) { "Invalid pairing peer" } }
    fun encrypt(data: ByteArray): ByteArray = checkNotNull(nativeEncrypt(pointer, data))
    fun decrypt(data: ByteArray): ByteArray = checkNotNull(nativeDecrypt(pointer, data)) { "Pairing code rejected" }
    override fun close() { if (pointer != 0L) { nativeDestroy(pointer); pointer = 0 } }
    private external fun nativeConstructor(client: Boolean, password: ByteArray): Long
    private external fun nativeMsg(pointer: Long): ByteArray?
    private external fun nativeInitCipher(pointer: Long, peer: ByteArray): Boolean
    private external fun nativeEncrypt(pointer: Long, data: ByteArray): ByteArray?
    private external fun nativeDecrypt(pointer: Long, data: ByteArray): ByteArray?
    private external fun nativeDestroy(pointer: Long)
    companion object { init { System.loadLibrary("dsh_pairing") } }
}
