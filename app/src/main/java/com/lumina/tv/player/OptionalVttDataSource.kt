package com.lumina.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import java.io.ByteArrayOutputStream
import java.io.IOException

internal const val OPTIONAL_VTT_MAX_BYTES = 16 * 1024 * 1024

internal fun readOptionalVttBytes(read: (ByteArray, Int, Int) -> Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer, 0, buffer.size)
        if (size == C.RESULT_END_OF_INPUT) break
        if (output.size() + size > OPTIONAL_VTT_MAX_BYTES) throw IOException("VTT size limit")
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}

internal fun loadOptionalVttBytes(
    read: () -> ByteArray,
    onUnavailable: (Exception) -> Unit,
    parse: (ByteArray) -> Unit = ::parseOptionalVtt,
): ByteArray = try {
    read().also(parse)
} catch (failure: Exception) {
    onUnavailable(failure)
    "WEBVTT\n\n".toByteArray(Charsets.UTF_8)
}

private fun parseOptionalVtt(bytes: ByteArray) {
    WebvttParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { }
}

/** Video uses its own cache/headers; optional VTT uses the subtitle connection only. */
internal class OptionalVttDataSourceFactory(
    private val videoFactory: DataSource.Factory,
    private val subtitleFactory: DataSource.Factory,
    private val subtitleIds: Map<Uri, String>,
    private val onUnavailable: (String, String) -> Unit,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private var delegate: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(listener: TransferListener) {
            listeners += listener
            delegate?.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val subtitleId = subtitleIds[dataSpec.uri]
            var effectiveSpec = dataSpec
            val source = if (subtitleId == null) videoFactory.createDataSource() else {
                // Read and validate before exposing any bytes, so partial/invalid VTT also
                // degrades to an empty subtitle, never a fatal video-source exception.
                val upstream = subtitleFactory.createDataSource()
                val bytes = try {
                    loadOptionalVttBytes(read = {
                        upstream.open(dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
                        readOptionalVttBytes { buffer, offset, length -> upstream.read(buffer, offset, length) }
                    }, onUnavailable = { failure -> onUnavailable(subtitleId, failure.javaClass.simpleName) })
                } finally {
                    runCatching { upstream.close() }
                }
                // A retry/seek may carry an offset beyond the eight-byte empty fallback.
                effectiveSpec = dataSpec.buildUpon().setPosition(dataSpec.position.coerceAtMost(bytes.size.toLong())).build()
                ByteArrayDataSource(bytes)
            }
            delegate = source
            listeners.forEach(source::addTransferListener)
            return source.open(effectiveSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate).read(buffer, offset, length)
        override fun getUri(): Uri? = delegate?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
        override fun close() { delegate?.close(); delegate = null }
    }
}
