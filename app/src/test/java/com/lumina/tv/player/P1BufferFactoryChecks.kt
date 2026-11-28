package com.lumina.tv.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.FileTypes
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mp3.Mp3Extractor
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.MediaStream
import kotlinx.coroutines.*
import java.io.File

object P1BufferFactoryChecks {
    fun run() = runBlocking {
        fun source(rate: Long) = MediaSource("source-$rate", "fixture", "mp4", true, true,
            listOf(MediaStream(0, "Video", "h264", null, null, true, false, null, bitRate = rate)), bitrate = 90_000_000)
        val fence = LoadRetirementFence()
        val oldPlayer = Any()
        Media3DecoderRetirements.decoderInitialized(oldPlayer, "fixture decoder")
        val retirement = Media3DecoderRetirements.begin(oldPlayer)
        fence.track(retirement)
        Media3DecoderRetirements.finish(oldPlayer, retirement)
        var built = 0
        val create = async(start = CoroutineStart.UNDISPATCHED) {
            val permit = fence.awaitPermit()
            val plan = Media3ResourcePlan.resolve(source(40_000_000), 4096, true, permit)
            built++
            plan
        }
        yield()
        check(built == 0) { "A player was created before the old decoder retired" }
        Media3DecoderRetirements.decoderReleased(oldPlayer, "fixture decoder")
        check(create.await().buffer.targetBytes == 160 * 1024 * 1024 && built == 1)
        val permit = fence.awaitPermit()
        for ((mem, lowBytes, highBytes) in listOf(Triple(2048, 64, 64), Triple(4096, 128, 160), Triple(8192, 192, 256))) {
            for (fast in listOf(false, true)) for (rate in listOf(8_000_000L, 40_000_000L)) {
                val resolved = source(rate)
                val plan = Media3ResourcePlan.resolve(resolved, mem, fast, permit)
                check(plan.sourceId == resolved.id && plan.videoBitrateBps == rate)
                check(plan.buffer.targetBytes == (if (rate > 25_000_000) highBytes else lowBytes) * 1024 * 1024)
                check(plan.buffer.forPlaybackMs == if (!fast) 2500 else if (mem > 4096 && rate > 25_000_000) 1500 else 1000)
            }
        }
        val blocking = LoadRetirement().also(fence::track)
        check(runCatching { Media3ResourcePlan.resolve(source(1), 4096, true, permit) }.isFailure)
        blocking.succeed()

        val upstream = DataSource.Factory { error("No network is allowed in this fixture") }
        val wiring = Media3SourceWiring(upstream)
        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
        val loader = checkNotNull(field(wiring.factory, "delegateFactoryLoader"))
        check(field(loader, "extractorsFactory") === wiring.extractors)
        check(field(wiring.factory, "dataSourceFactory") === upstream)
        check(field(wiring.extractors, "constantBitrateSeekingEnabled") == true)
        check(field(wiring.extractors, "constantBitrateSeekingAlwaysEnabled") == true)

        // Exercise Media3's real factory dispatch and real MP3 parser. No ID3/Xing/Info/VBRI
        // headers and unknown HTTP content length: only the configured extractor enables seeking.
        fun mp3(factory: DefaultExtractorsFactory): Mp3Extractor {
            val extractors = mutableListOf<Extractor>()
            factory.javaClass.getDeclaredMethod("addExtractorsForFileType", Int::class.javaPrimitiveType, List::class.java)
                .apply { isAccessible = true }.invoke(factory, FileTypes.MP3, extractors)
            return extractors.filterIsInstance<Mp3Extractor>().single()
        }
        fun cbrSeekable(extractor: Mp3Extractor): Boolean {
            val bytes = ByteArray(417 * 32)
            repeat(32) { frame ->
                byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(bytes, frame * 417)
            }
            var offset = 0
            val reader = DataReader { output, start, length ->
                if (offset == bytes.size) C.RESULT_END_OF_INPUT else {
                    val count = minOf(length, bytes.size - offset)
                    bytes.copyInto(output, start, offset, offset + count)
                    offset += count
                    count
                }
            }
            var seekMap: SeekMap? = null
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(map: SeekMap) { seekMap = map }
            })
            val input = DefaultExtractorInput(reader, 0, C.LENGTH_UNSET.toLong())
            val holder = PositionHolder()
            var ended = false
            repeat(128) {
                if (!ended) ended = extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT
            }
            check(ended && input.position == bytes.size.toLong())
            return checkNotNull(seekMap).isSeekable
        }
        check(cbrSeekable(mp3(wiring.extractors)))
        check(!cbrSeekable(mp3(DefaultExtractorsFactory()))) // Negative control for lost factory wiring.
        val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check(".setMediaSourceFactory(sourceWiring.factory)" in screen)
        check("val videoSource = sourceWiring.factory.createMediaSource(videoItem)" in screen)
        check("DefaultMediaSourceFactory(optionalFactory)" !in screen)
        val load = screen.substringAfter("fun loadSource(")
        check(load.indexOf("Media3DecoderRetirements.finish(exo, exoRetirement)") < load.indexOf("MpvRetirementFence.awaitPermit()"))
        check(load.indexOf("MpvRetirementFence.awaitPermit()") < load.indexOf("retireMedia3Player(retiringPlayer"))
        check(load.indexOf("retireMedia3Player(retiringPlayer") < load.indexOf("Media3ResourcePlan.resolve(target.source"))
        check(load.indexOf("Media3ResourcePlan.resolve(target.source") < load.indexOf("createMedia3Player(resourcePlan, sourceWiring)"))
        check(load.indexOf("listenersReady.await()") < load.indexOf("exo.prepare()"))
        println("P1BufferFactoryChecks PASS: cold low/high bitrate x memory x fastStart; retirement gate; factory/extractor identity; real CBR no-seek-header fixture and negative control")
    }
}
