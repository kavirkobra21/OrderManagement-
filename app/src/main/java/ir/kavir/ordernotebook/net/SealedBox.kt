package ir.kavir.ordernotebook.net

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * libsodium-compatible crypto_box_seal (X25519 + XSalsa20-Poly1305), required by GitHub's
 * "create or update a repository secret" API. Pure JVM (BouncyCastle), so it is unit-tested against a
 * vector produced by an independent reference implementation.
 */
object SealedBox {
    private fun rd(b: ByteArray, o: Int) =
        (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    private fun wr(v: Int, b: ByteArray, o: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    /** HSalsa20 core: 32-byte key + 16-byte input -> 32-byte key (not provided by BouncyCastle). */
    fun hsalsa20(key: ByteArray, n16: ByteArray): ByteArray {
        require(key.size == 32 && n16.size == 16)
        val sigma = "expand 32-byte k".toByteArray(Charsets.US_ASCII)
        val x = intArrayOf(
            rd(sigma, 0), rd(key, 0), rd(key, 4), rd(key, 8), rd(key, 12), rd(sigma, 4),
            rd(n16, 0), rd(n16, 4), rd(n16, 8), rd(n16, 12), rd(sigma, 8),
            rd(key, 16), rd(key, 20), rd(key, 24), rd(key, 28), rd(sigma, 12),
        )
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[b] = x[b] xor Integer.rotateLeft(x[a] + x[d], 7)
            x[c] = x[c] xor Integer.rotateLeft(x[b] + x[a], 9)
            x[d] = x[d] xor Integer.rotateLeft(x[c] + x[b], 13)
            x[a] = x[a] xor Integer.rotateLeft(x[d] + x[c], 18)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(5, 9, 13, 1); qr(10, 14, 2, 6); qr(15, 3, 7, 11)
            qr(0, 1, 2, 3); qr(5, 6, 7, 4); qr(10, 11, 8, 9); qr(15, 12, 13, 14)
        }
        val out = ByteArray(32)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, w -> wr(x[w], out, i * 4) }
        return out
    }

    fun publicKey(secret: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) }

    private fun nonce(epk: ByteArray, rpk: ByteArray): ByteArray {
        val d = Blake2bDigest(192)
        d.update(epk, 0, 32); d.update(rpk, 0, 32)
        return ByteArray(24).also { d.doFinal(it, 0) }
    }

    private fun boxKey(sk: ByteArray, pk: ByteArray): ByteArray {
        val shared = ByteArray(32)
        X25519.scalarMult(sk, 0, pk, 0, shared, 0)
        return hsalsa20(shared, ByteArray(16))
    }

    /** Returns epk(32) || tag(16) || ciphertext. [ephemeralSecret] is only for tests. */
    fun seal(message: ByteArray, recipientPublic: ByteArray, ephemeralSecret: ByteArray? = null): ByteArray {
        require(recipientPublic.size == 32) { "bad public key" }
        val esk = ephemeralSecret ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        val epk = publicKey(esk)
        val key = boxKey(esk, recipientPublic)
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce(epk, recipientPublic)))
        val buf = ByteArray(32 + message.size)
        System.arraycopy(message, 0, buf, 32, message.size)
        val out = ByteArray(buf.size)
        engine.processBytes(buf, 0, buf.size, out, 0)
        val ct = out.copyOfRange(32, out.size)
        val mac = Poly1305()
        mac.init(KeyParameter(out.copyOfRange(0, 32)))
        mac.update(ct, 0, ct.size)
        val tag = ByteArray(16).also { mac.doFinal(it, 0) }
        return epk + tag + ct
    }

    /** Inverse of [seal]; used by tests. Throws on a bad tag. */
    fun open(sealed: ByteArray, recipientSecret: ByteArray): ByteArray {
        val rpk = publicKey(recipientSecret)
        val epk = sealed.copyOfRange(0, 32)
        val tag = sealed.copyOfRange(32, 48)
        val ct = sealed.copyOfRange(48, sealed.size)
        val engine = XSalsa20Engine()
        engine.init(false, ParametersWithIV(KeyParameter(boxKey(recipientSecret, epk)), nonce(epk, rpk)))
        val buf = ByteArray(32 + ct.size)
        System.arraycopy(ct, 0, buf, 32, ct.size)
        val out = ByteArray(buf.size)
        engine.processBytes(buf, 0, buf.size, out, 0)
        val mac = Poly1305()
        mac.init(KeyParameter(out.copyOfRange(0, 32)))
        mac.update(ct, 0, ct.size)
        val calc = ByteArray(16).also { mac.doFinal(it, 0) }
        require(calc.contentEquals(tag)) { "authentication failed" }
        return out.copyOfRange(32, out.size)
    }
}
