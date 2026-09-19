package com.cartunnel.client.profile

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

enum class ProfileHealthStatus { UNTESTED, TESTING, AVAILABLE, FAILED, STALE }

data class ProfileHealthRecord(
    val status: ProfileHealthStatus,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val safeErrorCode: String? = null,
    val profileRevision: String,
)

interface HealthStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class ProfileHealthStore(
    private val storage: HealthStorage,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val testingProfiles = mutableMapOf<String, String>()

    constructor(context: Context) : this(
        object : HealthStorage {
            private val prefs = context.getSharedPreferences("cartunnel.profile.health", Context.MODE_PRIVATE)
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) }
            override fun remove(key: String) { check(prefs.edit().remove(key).commit()) }
        },
    )

    @Synchronized
    fun read(profile: VmessWsProfile): ProfileHealthRecord {
        val revision = revision(profile)
        if (testingProfiles[profile.id] == revision) {
            return ProfileHealthRecord(ProfileHealthStatus.TESTING, profileRevision = revision)
        }
        testingProfiles.remove(profile.id)
        val value = storage.get(profile.id) ?: return ProfileHealthRecord(ProfileHealthStatus.UNTESTED, profileRevision = revision)
        val record = runCatching { decode(value) }.getOrElse {
            return ProfileHealthRecord(ProfileHealthStatus.STALE, profileRevision = revision)
        }
        return if (record.profileRevision == revision) record else record.copy(
            status = ProfileHealthStatus.STALE,
            profileRevision = revision,
        )
    }

    @Synchronized
    fun markTesting(profile: VmessWsProfile) {
        testingProfiles[profile.id] = revision(profile)
    }

    @Synchronized fun cancelTesting(id: String) { testingProfiles.remove(id) }

    @Synchronized
    fun markAvailable(profile: VmessWsProfile, latencyMs: Long) {
        testingProfiles.remove(profile.id)
        write(
            ProfileHealthRecord(
                status = ProfileHealthStatus.AVAILABLE,
                latencyMs = latencyMs,
                checkedAt = clock(),
                profileRevision = revision(profile),
            ),
            profile.id,
        )
    }

    @Synchronized
    fun markFailed(profile: VmessWsProfile, safeErrorCode: String) {
        testingProfiles.remove(profile.id)
        write(
            ProfileHealthRecord(
                status = ProfileHealthStatus.FAILED,
                checkedAt = clock(),
                safeErrorCode = safeErrorCode,
                profileRevision = revision(profile),
            ),
            profile.id,
        )
    }

    @Synchronized
    fun markStale(profile: VmessWsProfile) {
        val current = read(profile)
        write(current.copy(status = ProfileHealthStatus.STALE, profileRevision = revision(profile)), profile.id)
    }

    @Synchronized
    fun delete(profileId: String) {
        testingProfiles.remove(profileId)
        storage.remove(profileId)
    }

    @Synchronized fun snapshot(id: String): String? = storage.get(id)
    @Synchronized fun restore(id: String, value: String?) {
        if (value == null) storage.remove(id) else storage.put(id, value)
    }

    private fun write(record: ProfileHealthRecord, key: String) {
        storage.put(key, JSONObject().apply {
            put("status", record.status.name)
            put("latencyMs", record.latencyMs ?: JSONObject.NULL)
            put("checkedAt", record.checkedAt ?: JSONObject.NULL)
            put("safeErrorCode", record.safeErrorCode ?: JSONObject.NULL)
            put("profileRevision", record.profileRevision)
        }.toString())
    }

    private fun decode(value: String): ProfileHealthRecord {
        val json = JSONObject(value)
        return ProfileHealthRecord(
            status = ProfileHealthStatus.valueOf(json.getString("status")),
            latencyMs = if (json.isNull("latencyMs")) null else json.getLong("latencyMs"),
            checkedAt = if (json.isNull("checkedAt")) null else json.getLong("checkedAt"),
            safeErrorCode = if (json.isNull("safeErrorCode")) null else json.getString("safeErrorCode"),
            profileRevision = json.getString("profileRevision"),
        )
    }

    companion object {
        fun revision(profile: VmessWsProfile): String {
            val canonical = listOf(
                profile.schemaVersion,
                profile.id,
                profile.name,
                profile.server,
                profile.port,
                profile.uuid,
                profile.wsHost,
                profile.wsPath,
            ).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
