package com.cartunnel.client.profile

interface DeletionRepository { fun deleteAtomically(id: String): String? }
sealed interface DeleteResult {
    data class Deleted(val selectedId: String?) : DeleteResult
    data object WaitingForStop : DeleteResult
    data object Busy : DeleteResult
    data object Failed : DeleteResult
    data object Idle : DeleteResult
}

/** No running profile can be removed before the service confirms all resources stopped. */
class ProfileDeletionCoordinator(private val repository: DeletionRepository) {
    private var pendingId: String? = null
    @Synchronized fun request(id: String, activeId: String?, stopped: Boolean): DeleteResult {
        if (pendingId != null) return DeleteResult.Busy
        pendingId = id
        return if (id == activeId && !stopped) DeleteResult.WaitingForStop else commit()
    }
    @Synchronized fun onState(stopped: Boolean): DeleteResult = when {
        pendingId == null -> DeleteResult.Idle
        !stopped -> DeleteResult.WaitingForStop
        else -> commit()
    }
    @Synchronized fun cancel() { pendingId = null }
    private fun commit(): DeleteResult {
        val id = pendingId ?: return DeleteResult.Idle
        return try { DeleteResult.Deleted(repository.deleteAtomically(id)) }
        catch (_: Exception) { DeleteResult.Failed }
        finally { pendingId = null }
    }
}
