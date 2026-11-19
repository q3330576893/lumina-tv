package com.lumina.tv.pair

internal enum class PairRequestDeliveryResult { DELIVERED, NO_RECEIVER }

/** A pairing request is accepted only when a live UI receiver confirms delivery. */
internal fun deliverPairRequest(
    receiver: ((PairConfig) -> Boolean)?,
    config: PairConfig,
    onUnavailable: () -> Unit = {},
): PairRequestDeliveryResult = try {
    if (receiver?.invoke(config) == true) PairRequestDeliveryResult.DELIVERED
    else PairRequestDeliveryResult.NO_RECEIVER.also { onUnavailable() }
} catch (_: Exception) {
    runCatching(onUnavailable)
    PairRequestDeliveryResult.NO_RECEIVER
}
