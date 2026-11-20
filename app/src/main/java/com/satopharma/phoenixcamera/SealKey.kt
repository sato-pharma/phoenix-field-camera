package com.satopharma.phoenixcamera

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The seal key: made once inside the phone's secure chip (StrongBox where the phone has one), never leaves it, and
 * comes with Google's attestation chain. That chain tells Phoenix the key is in real hardware, the phone is not
 * rooted or unlocked, and the key belongs to THIS app signed with Sato Pharma's own certificate.
 */
object SealKey {
    private const val ALIAS = "phoenix-camera-seal"
    private val CHALLENGE = "PHOENIX-CAMERA-SEAL-V1".toByteArray(Charsets.US_ASCII)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensure() {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) return
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                generate(strongBox = true)
                return
            } catch (_: Exception) {
                runCatching { store.deleteEntry(ALIAS) }
            }
        }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(CHALLENGE)
        if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(spec.build())
        generator.generateKeyPair()
    }

    fun sign(data: ByteArray): ByteArray {
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    /** Leaf first, root last, each DER-encoded. */
    fun chain(): List<ByteArray> = keyStore().getCertificateChain(ALIAS).map { it.encoded }
}
