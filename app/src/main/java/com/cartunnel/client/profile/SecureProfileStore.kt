package com.cartunnel.client.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.text.Charsets.UTF_8

class ConfigUnreadableException(cause: Throwable? = null) :
    IllegalStateException("CONFIG_UNREADABLE", cause)

class ConfigWriteException(cause: Throwable? = null) : IllegalStateException("CONFIG_WRITE_FAILED", cause)
class ConfigDataException(cause: Throwable? = null) : IllegalStateException("CONFIG_DATA_INVALID", cause)

class SecureProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE)
    private val alias = "cartunnel.profile.v1"

    private fun key(): SecretKey = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    } catch (error: Exception) {
        throw ConfigUnreadableException(error)
    }

    @Synchronized
    fun save(profiles: List<VmessWsProfile>) = saveInternal(profiles, null)

    private fun saveInternal(profiles: List<VmessWsProfile>, removedCount: Int?) {
        profiles.forEach(ProfileValidator::requireValid)
        val before = prefs.all.toMap()
        try {
            val plain = JSONArray().apply {
                profiles.forEach { put(it.toJson()) }
            }.toString().toByteArray(UTF_8)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                // AndroidKeyStore AES-GCM keys require randomized encryption. Let the
                // provider create the IV instead of supplying a caller-generated IV.
                init(Cipher.ENCRYPT_MODE, key())
            }
            val encrypted = cipher.doFinal(plain)
            val iv = cipher.iv ?: throw IllegalStateException("Missing GCM IV")
            val editor = prefs.edit()
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putInt(PREF_SCHEMA, STORAGE_SCHEMA)
            if (removedCount != null) editor.putInt("migration.removed", removedCount)
            val committed = editor.commit()
            if (!committed) {
                // SharedPreferences mutates its memory map even when the disk write fails.
                // Restore that map as well so a failed delete cannot disappear from the UI.
                val restore = prefs.edit().clear()
                before.forEach { (key, value) -> when (value) {
                    is String -> restore.putString(key, value)
                    is Int -> restore.putInt(key, value)
                } }
                restore.commit()
                throw IllegalStateException("Encrypted profile commit failed")
            }
        } catch (error: ConfigUnreadableException) {
            throw error
        } catch (error: Exception) {
            throw ConfigWriteException(error)
        }
    }

    @Synchronized
    fun load(): List<VmessWsProfile> {
        val ivText = prefs.getString(PREF_IV, null)
        val blob = prefs.getString(PREF_BLOB, null)
        if (ivText == null && blob == null) return emptyList()
        val schema = prefs.getInt(PREF_SCHEMA, 0)
        if (ivText == null || blob == null || schema !in 1..STORAGE_SCHEMA) {
            throw ConfigUnreadableException()
        }
        val plain = try {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), UTF_8)
        } catch (error: ConfigUnreadableException) {
            throw error
        } catch (error: Exception) {
            throw ConfigUnreadableException(error)
        }
        val array = try { JSONArray(plain) } catch (error: Exception) { throw ConfigDataException(error) }
        if (schema == 1) {
            val migrated = ProfileMigration.migrate(array, null)
            saveInternal(migrated.profiles, migrated.removedCount)
            return migrated.profiles
        }
        return try { List(array.length()) { array.getJSONObject(it).toProfile() } }
        catch (error: Exception) { throw ConfigDataException(error) }
    }

    @Synchronized fun migrationNotice(): Int = prefs.getInt("migration.removed", 0)
    @Synchronized fun acknowledgeMigration() { check(prefs.edit().remove("migration.removed").commit()) }

    @Synchronized
    fun upsert(profile: VmessWsProfile) {
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.id == profile.id }
        if (index >= 0) all[index] = profile else all += profile
        save(all)
    }

    @Synchronized
    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** Clears unreadable local ciphertext so the user can import or create profiles again. */
    @Synchronized
    fun reset() {
        try {
            if (!prefs.edit().clear().commit()) {
                throw IllegalStateException("Encrypted profile reset failed")
            }
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        } catch (error: Exception) {
            throw ConfigUnreadableException(error)
        }
    }

    private fun VmessWsProfile.toJson() = ProfileJsonCodec.toJson(this)
    private fun JSONObject.toProfile() = ProfileJsonCodec.fromJson(this)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val STORAGE_SCHEMA = 2
        const val PREF_IV = "profiles.iv"
        const val PREF_BLOB = "profiles.blob"
        const val PREF_SCHEMA = "profiles.schema"
    }
}
