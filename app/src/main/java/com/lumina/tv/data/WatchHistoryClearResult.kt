package com.lumina.tv.data

enum class WatchHistoryClearStatus { SUCCESS, PARTIAL_FAILURE, FAILURE }

enum class WatchHistoryClearStop { COMPLETE, NO_PROGRESS, BUDGET_EXHAUSTED, REQUEST_FAILED }

data class WatchHistoryClearFailure(val itemId: String?, val cause: Exception)

/** Counts only removals confirmed by the next first-page read. No total means no remaining count. */
data class WatchHistoryClearResult(
    val cleared: Int,
    val attempted: Int,
    val stop: WatchHistoryClearStop,
    val failures: List<WatchHistoryClearFailure>,
    val unconfirmedIds: Set<String>,
) {
    val status: WatchHistoryClearStatus
        get() = when {
            stop == WatchHistoryClearStop.COMPLETE && failures.isEmpty() && unconfirmedIds.isEmpty() ->
                WatchHistoryClearStatus.SUCCESS
            cleared > 0 -> WatchHistoryClearStatus.PARTIAL_FAILURE
            else -> WatchHistoryClearStatus.FAILURE
        }
}

/** The legacy Int API must not turn an incomplete operation into a successful count. */
class WatchHistoryClearException(val result: WatchHistoryClearResult) :
    Exception("Watch history clear: " + result.status + " (" + result.stop + "), confirmed=" + result.cleared,
        result.failures.firstOrNull()?.cause)
