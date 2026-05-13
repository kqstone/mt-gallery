package com.kqstone.mtphotos.data.repository

import android.util.Base64
import android.util.Log
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.PrefsManager
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

private const val TAG = "AuthRepo"

class AuthRepository(private val container: AppContainer) {

    private val prefsManager: PrefsManager get() = container.prefsManager

    suspend fun login(serverUrl: String, username: String, password: String): Result<Unit> {
        return try {
            val cleanUrl = serverUrl.trim().trimEnd('/')
            Log.d(TAG, "Step 0: Saving credentials, serverUrl=$cleanUrl")
            // Save server URL first so RetrofitClient can use it
            prefsManager.saveCredentials(cleanUrl, username, password)
            // Invalidate cached Retrofit so it rebuilds with new URL
            container.retrofitClient.invalidate()

            // Step 1: Get RSA public key
            Log.d(TAG, "Step 1: Fetching RSA public key")
            val rsaResponse = container.authApi.AppControllerGetLoginRSAKeys()
            Log.d(TAG, "RSA response keys: ${rsaResponse.keys}")
            val publicKeyPem = rsaResponse["publicKey"] as? String
                ?: return Result.failure(Exception("Failed to get RSA public key"))
            Log.d(TAG, "RSA key starts with: ${publicKeyPem.take(30)}")

            // Step 2: Encrypt password with RSA
            Log.d(TAG, "Step 2: Encrypting password")
            val encryptedPassword = encryptWithRSA(password, publicKeyPem)
            Log.d(TAG, "Encrypted password length: ${encryptedPassword.length}")

            // Step 3: Login
            Log.d(TAG, "Step 3: Logging in")
            val loginBody = mapOf(
                "username" to username,
                "password" to encryptedPassword
            )
            val loginResponse = container.authApi.AppControllerLogin(loginBody)
            Log.d(TAG, "Login response keys: ${loginResponse.keys}")

            val token = loginResponse["token"] as? String
                ?: loginResponse["access_token"] as? String
                ?: return Result.failure(Exception("Login succeeded but no token returned"))
            Log.d(TAG, "Got token: ${token.take(20)}...")

            val refreshTk = loginResponse["refresh_token"] as? String ?: ""
            prefsManager.saveToken(token, refreshTk)

            // Step 4: Get auth_code
            Log.d(TAG, "Step 4: Getting auth_code")
            refreshAuthCode()

            Log.d(TAG, "Login complete!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            Result.failure(e)
        }
    }

    suspend fun refreshAuthCode(): Result<String> {
        return try {
            val response = container.authApi.AppControllerGetAuthCode(emptyMap())
            val authCode = response["auth_code"] as? String
                ?: response["authCode"] as? String
                ?: return Result.failure(Exception("No auth_code in response"))
            prefsManager.saveAuthCode(authCode)
            Result.success(authCode)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAuthCode(): String {
        return prefsManager.getAuthCodeSync()
    }

    private fun encryptWithRSA(data: String, publicKeyPem: String): String {
        val isPkcs1 = publicKeyPem.contains("RSA PUBLIC KEY")
        val cleanKey = publicKeyPem
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.decode(cleanKey, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("RSA")

        val publicKey = if (isPkcs1) {
            // PKCS#1: SEQUENCE { modulus (INT), exponent (INT) }
            val (modulus, exponent) = parsePkcs1PublicKey(keyBytes)
            keyFactory.generatePublic(RSAPublicKeySpec(modulus, exponent))
        } else {
            keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
        }

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    /**
     * Parse PKCS#1 DER-encoded RSA public key to extract modulus and exponent.
     */
    private fun parsePkcs1PublicKey(der: ByteArray): Pair<BigInteger, BigInteger> {
        var pos = 0
        // SEQUENCE tag
        require(der[pos++] == 0x30.toByte()) { "Expected SEQUENCE" }
        pos += readLengthBytes(der, pos) // skip length
        // modulus INTEGER
        require(der[pos++] == 0x02.toByte()) { "Expected INTEGER for modulus" }
        val modLen = readLength(der, pos)
        pos += modLen.second
        val modulus = BigInteger(der.copyOfRange(pos, pos + modLen.first))
        pos += modLen.first
        // exponent INTEGER
        require(der[pos++] == 0x02.toByte()) { "Expected INTEGER for exponent" }
        val expLen = readLength(der, pos)
        pos += expLen.second
        val exponent = BigInteger(der.copyOfRange(pos, pos + expLen.first))
        return modulus to exponent
    }

    /** Read DER length, returns (contentLength, numberOfLengthBytes) */
    private fun readLength(der: ByteArray, offset: Int): Pair<Int, Int> {
        val first = der[offset].toInt() and 0xFF
        if (first < 0x80) return first to 1
        val numBytes = first and 0x7F
        var length = 0
        for (i in 0 until numBytes) {
            length = (length shl 8) or (der[offset + 1 + i].toInt() and 0xFF)
        }
        return length to (1 + numBytes)
    }

    /** Returns total number of bytes consumed by length field */
    private fun readLengthBytes(der: ByteArray, offset: Int): Int = readLength(der, offset).second
}
