package uz.arion.ai

import java.util.UUID

/**
 * ARION independent-agent foundation.
 *
 * This layer separates planning from execution. The language model may propose
 * actions, but only this policy engine can authorize them. No API keys or bank
 * credentials belong in task descriptions or logs.
 */
enum class AgentActionType {
    RESEARCH,
    DRAFT_MESSAGE,
    SEND_MESSAGE,
    CREATE_ORDER_DRAFT,
    DEMO_TRADE,
    REAL_TRADE,
    TRANSFER_MONEY,
    SIGN_CONTRACT,
    CHANGE_ACCOUNT_SECURITY,
    MODIFY_PROJECT_CODE
}

enum class AgentTaskState {
    WAITING_APPROVAL,
    READY,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentAction(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val details: String,
    val type: AgentActionType,
    /** Amount in the smallest currency unit (tiyin/cents), never Float/Double. */
    val amountMinor: Long = 0L,
    val currency: String = "UZS"
)

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val action: AgentAction,
    val state: AgentTaskState,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val result: String? = null
)

data class AgentPolicy(
    /** Read-only research and local drafts may run without a confirmation. */
    val allowAutomaticResearch: Boolean = true,
    val allowAutomaticDrafts: Boolean = true,
    /** Real-money operations are OFF by default. */
    val allowRealTrading: Boolean = false,
    val allowMoneyTransfers: Boolean = false,
    val allowContractSigning: Boolean = false,
    /** 0 means no autonomous real-money spending is permitted. */
    val maxAutonomousSpendMinor: Long = 0L
)

data class AgentLedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val amountMinor: Long,
    val currency: String,
    val isIncome: Boolean,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

interface AgentActionExecutor {
    /** Implementations must execute only the exact action they receive. */
    fun execute(action: AgentAction): String
}

/**
 * Policy-gated task engine. This is deliberately not a wallet or exchange client.
 * A real exchange adapter must be added separately and must enforce its own
 * server-side limits, idempotency, audit trail and user approval.
 */
class ArionAgentCore(
    private val policy: AgentPolicy = AgentPolicy()
) {
    private val tasks = linkedMapOf<String, AgentTask>()
    private val ledger = mutableListOf<AgentLedgerEntry>()

    @Synchronized
    fun submit(goal: String, action: AgentAction): AgentTask {
        require(goal.isNotBlank()) { "Maqsad bo‘sh bo‘lmasin" }
        require(action.title.isNotBlank()) { "Vazifa nomi bo‘sh bo‘lmasin" }
        require(action.amountMinor >= 0L) { "Mablag‘ manfiy bo‘lishi mumkin emas" }

        val state = if (canRunWithoutApproval(action)) {
            AgentTaskState.READY
        } else {
            AgentTaskState.WAITING_APPROVAL
        }
        return AgentTask(goal = goal, action = action, state = state).also {
            tasks[it.id] = it
        }
    }

    @Synchronized
    fun approve(taskId: String, userConfirmed: Boolean): AgentTask {
        val task = tasks[taskId] ?: error("Vazifa topilmadi")
        require(task.state == AgentTaskState.WAITING_APPROVAL) {
            "Bu vazifa tasdiq kutmayapti"
        }
        if (!userConfirmed) {
            return task.copy(state = AgentTaskState.CANCELLED).also { tasks[taskId] = it }
        }
        require(isAllowedAfterExplicitApproval(task.action)) {
            "Bu amal joriy xavfsizlik siyosatida taqiqlangan"
        }
        return task.copy(state = AgentTaskState.READY).also { tasks[taskId] = it }
    }

    @Synchronized
    fun execute(taskId: String, executor: AgentActionExecutor): AgentTask {
        val task = tasks[taskId] ?: error("Vazifa topilmadi")
        require(task.state == AgentTaskState.READY) { "Vazifa bajarishga tayyor emas" }
        require(canExecute(task.action)) { "Amal xavfsizlik siyosati bilan bloklandi" }

        tasks[taskId] = task.copy(state = AgentTaskState.RUNNING)
        return try {
            val result = executor.execute(task.action)
            task.copy(state = AgentTaskState.COMPLETED, result = result)
                .also { tasks[taskId] = it }
        } catch (e: Exception) {
            task.copy(state = AgentTaskState.FAILED, result = e.message ?: "Noma’lum xato")
                .also { tasks[taskId] = it }
        }
    }

    @Synchronized
    fun cancel(taskId: String): AgentTask {
        val task = tasks[taskId] ?: error("Vazifa topilmadi")
        require(task.state == AgentTaskState.WAITING_APPROVAL || task.state == AgentTaskState.READY) {
            "Boshlangan yoki tugagan vazifani bu yerdan bekor qilib bo‘lmaydi"
        }
        return task.copy(state = AgentTaskState.CANCELLED).also { tasks[taskId] = it }
    }

    @Synchronized
    fun getTasks(): List<AgentTask> = tasks.values.toList()

    @Synchronized
    fun recordLedgerEntry(entry: AgentLedgerEntry) {
        require(entry.amountMinor >= 0L) { "Hisob summasi manfiy bo‘lmasin" }
        require(entry.currency.isNotBlank()) { "Valyuta ko‘rsatilishi kerak" }
        ledger.add(entry)
    }

    @Synchronized
    fun getLedger(): List<AgentLedgerEntry> = ledger.toList()

    /** Returns net cash flow for one currency, in minor units. */
    @Synchronized
    fun netCashFlow(currency: String): Long {
        return ledger.asSequence()
            .filter { it.currency.equals(currency, ignoreCase = true) }
            .fold(0L) { total, entry ->
                val signed = if (entry.isIncome) entry.amountMinor else -entry.amountMinor
                Math.addExact(total, signed)
            }
    }

    private fun canRunWithoutApproval(action: AgentAction): Boolean = when (action.type) {
        AgentActionType.RESEARCH -> policy.allowAutomaticResearch
        AgentActionType.DRAFT_MESSAGE,
        AgentActionType.CREATE_ORDER_DRAFT -> policy.allowAutomaticDrafts
        else -> false
    }

    private fun isAllowedAfterExplicitApproval(action: AgentAction): Boolean = when (action.type) {
        AgentActionType.RESEARCH -> policy.allowAutomaticResearch
        AgentActionType.DRAFT_MESSAGE,
        AgentActionType.CREATE_ORDER_DRAFT -> policy.allowAutomaticDrafts
        AgentActionType.SEND_MESSAGE,
        AgentActionType.MODIFY_PROJECT_CODE,
        AgentActionType.DEMO_TRADE -> true
        AgentActionType.REAL_TRADE -> policy.allowRealTrading
        AgentActionType.TRANSFER_MONEY -> policy.allowMoneyTransfers
        AgentActionType.SIGN_CONTRACT,
        AgentActionType.CHANGE_ACCOUNT_SECURITY -> policy.allowContractSigning
    }

    private fun canExecute(action: AgentAction): Boolean {
        if (!isAllowedAfterExplicitApproval(action)) return false
        return when (action.type) {
            AgentActionType.REAL_TRADE,
            AgentActionType.TRANSFER_MONEY -> action.amountMinor <= policy.maxAutonomousSpendMinor
            else -> true
        }
    }
}
