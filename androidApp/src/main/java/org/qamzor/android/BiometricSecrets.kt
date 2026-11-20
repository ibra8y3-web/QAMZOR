package org.qamzor.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (vault / archive passwords) that can only be decrypted after a fingerprint or face check.
 *
 * The AES-256-GCM key lives in the Android Keystore with "user authentication required for every use", so
 * the secret cannot be recovered without the biometric prompt, and the key is invalidated by the system when
 * the set of enrolled biometrics changes. Requires Android 10 (API 29) or newer.
 */
object BiometricSecrets {
    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "qamzor.biometric.secrets.v1"
    private const val PREFS = "qamzor-biometric-secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    fun has(context: Context, id: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("$id.ct")

    fun remove(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$id.iv").remove("$id.ct").apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }

    private fun obtainKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Encrypts [secret] after a successful biometric check and keeps it under [id]. */
    fun store(activity: Activity, id: String, secret: String, title: String, onResult: (Boolean, String?) -> Unit) {
        val cipher = try {
            Cipher.getInstance(TRANSFORM).also { it.init(Cipher.ENCRYPT_MODE, obtainKey()) }
        } catch (error: Exception) {
            onResult(false, error.message ?: "تعذر تجهيز مفتاح البصمة")
            return
        }
        authenticate(activity, title, cipher, { ready ->
            try {
                val encrypted = ready.doFinal(secret.toByteArray(Charsets.UTF_8))
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("$id.iv", Base64.encodeToString(ready.iv, Base64.NO_WRAP))
                    .putString("$id.ct", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply()
                onResult(true, null)
            } catch (error: Exception) {
                onResult(false, error.message ?: "تعذر حفظ السر")
            }
        }, { message -> onResult(false, message) })
    }

    /** Decrypts the secret stored under [id] after a successful biometric check. */
    fun retrieve(activity: Activity, id: String, title: String, onSecret: (String) -> Unit, onError: (String) -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString("$id.iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val encrypted = prefs.getString("$id.ct", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (iv == null || encrypted == null) {
            onError("لا توجد كلمة مرور محفوظة بالبصمة")
            return
        }
        val cipher = try {
            Cipher.getInstance(TRANSFORM).also { it.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(128, iv)) }
        } catch (error: KeyPermanentlyInvalidatedException) {
            clearAll(activity)
            onError("تغيّرت البصمات المسجّلة في الجهاز، فتم مسح كلمات المرور المحفوظة بالبصمة. أدخل كلمة المرور يدويًا.")
            return
        } catch (error: Exception) {
            onError(error.message ?: "تعذر تجهيز مفتاح البصمة")
            return
        }
        authenticate(activity, title, cipher, { ready ->
            try {
                onSecret(String(ready.doFinal(encrypted), Charsets.UTF_8))
            } catch (error: Exception) {
                onError(error.message ?: "تعذر فك التشفير")
            }
        }, onError)
    }

    private fun authenticate(activity: Activity, title: String, cipher: Cipher, onSuccess: (Cipher) -> Unit, onError: (String) -> Unit) {
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("QAMZOR")
            .setNegativeButton("إلغاء", executor, DialogInterface.OnClickListener { _, _ -> onError("تم الإلغاء") })
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (Build.VERSION.SDK_INT >= 29) builder.setConfirmationRequired(false)
        val prompt = builder.build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val crypto = result.cryptoObject?.cipher
                if (crypto == null) onError("تعذر التحقق من البصمة") else onSuccess(crypto)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancel (negative button) is reported by the button callback; avoid a second message.
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    onError(errString.toString())
                } else {
                    onError("تم الإلغاء")
                }
            }
        })
    }
}

/** Optional lock for the whole app, opened with the device biometric or screen lock. */
object AppLock {
    private const val PREFS = "qamzor-app-lock"
    const val REQUEST_CREDENTIAL = 9301

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    /** Milliseconds in the background before the lock returns; 0 locks every time the app is left. */
    fun timeoutMs(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("timeout", 30_000L)

    fun setTimeoutMs(context: Context, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("timeout", value).apply()
    }

    fun isDeviceSecure(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Android 11+ uses the biometric dialog; older versions use the system confirm-credentials screen. */
    fun usesCredentialScreen(): Boolean = Build.VERSION.SDK_INT < 30

    @Suppress("DEPRECATION")
    fun credentialIntent(activity: Activity): Intent? =
        activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent("QAMZOR", "افتح التطبيق بقفل الجهاز")

    /** Only valid when [usesCredentialScreen] is false. */
    fun authenticate(activity: Activity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            onError("غير مدعوم")
            return
        }
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("فتح QAMZOR")
            .setSubtitle("استخدم البصمة أو قفل الجهاز")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        })
    }
}
