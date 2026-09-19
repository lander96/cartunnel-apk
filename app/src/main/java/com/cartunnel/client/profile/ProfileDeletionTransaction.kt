package com.cartunnel.client.profile

/** Commit encrypted nodes last. Any earlier failure leaves the node intact and repairs metadata. */
class ProfileDeletionTransaction(
    private val load: () -> List<VmessWsProfile>,
    private val save: (List<VmessWsProfile>) -> Unit,
    private val selected: () -> String?,
    private val select: (String?) -> Unit,
    private val healthRead: (String) -> String?,
    private val healthDelete: (String) -> Unit,
    private val healthRestore: (String, String?) -> Unit,
) : DeletionRepository {
    @Synchronized override fun deleteAtomically(id: String): String? {
        val before = load()
        val remaining = before.filterNot { it.id == id }
        val oldSelected = selected()
        val next = oldSelected?.takeIf { current -> remaining.any { it.id == current } } ?: remaining.firstOrNull()?.id
        val oldHealth = healthRead(id)
        try {
            healthDelete(id)
            select(next)
            save(remaining)
        } catch (error: Exception) {
            runCatching { select(oldSelected) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { healthRestore(id, oldHealth) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        return next
    }
}
