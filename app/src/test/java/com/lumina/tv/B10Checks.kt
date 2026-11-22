package com.lumina.tv

import com.lumina.tv.diag.LogRedactor
import com.lumina.tv.diag.boundedDiagnosticJson
import com.lumina.tv.pair.PairConfig
import com.lumina.tv.pair.PairRequestDeliveryResult
import com.lumina.tv.pair.deliverPairRequest
import com.lumina.tv.player.runPlaybackErrorClassificationAssertions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object B10Checks {
    @JvmStatic fun main(args: Array<String>) {
        pairingDelivery()
        diagnostics()
        httpStatuses()
        println("B10 PASS: Q-043 no-receiver pending revocation; Q-044 IO-only logcat; Q-045 parseable JSON bounds; Q-046 Cookie redaction; Q-047 SAF/FileProvider; Q-049 HTTP classifications")
    }

    private fun pairingDelivery() {
        val config = PairConfig("alias", "", "http", "127.0.0.1", "8096", "/", "user", "pass")
        var pending = true
        val noReceiver = deliverPairRequest(null, config) { pending = false }
        check(noReceiver == PairRequestDeliveryResult.NO_RECEIVER && !pending)
        pending = true
        val rejected = deliverPairRequest({ false }, config) { pending = false }
        check(rejected == PairRequestDeliveryResult.NO_RECEIVER && !pending)
        pending = true
        val accepted = deliverPairRequest({ received -> check(received == config); true }, config) { pending = false }
        check(accepted == PairRequestDeliveryResult.DELIVERED && pending)
        val server = source("pair/PairingServer.kt")
        check("deliverPairRequestToUi(pending)" in server && "revokePending(request)" in server)
        check("state = \"unavailable\"" in server && "state\", \"unavailable\"" in server)
    }

    private fun diagnostics() {
        val cap = 120 * 1024
        for (oversized in listOf(
            JSONObject().put("payload", "x".repeat(cap + 1024)).toString(),
            JSONArray().put("界🙂".repeat(cap)).toString(),
        )) {
            val bounded = boundedDiagnosticJson(oversized, cap)
            check(bounded.toByteArray(Charsets.UTF_8).size <= cap)
            check(JSONObject(bounded).getBoolean("truncated"))
        }
        val smallObject = JSONObject(boundedDiagnosticJson("{\"ok\":true}", cap))
        check(smallObject.getBoolean("ok"))
        val smallArray = JSONArray(boundedDiagnosticJson("[1,2,3]", cap))
        check(smallArray.length() == 3)
        check(boundedDiagnosticJson("\"scalar\"", cap) == "\"scalar\"")
        val malformed = JSONObject(boundedDiagnosticJson("{broken", cap))
        check(malformed.getBoolean("invalid") && !malformed.has("truncated") && malformed.getString("reason") == "invalid_json")

        val privateCookie = "session=must-not-escape"
        val sanitized = LogRedactor.redactJson(
            "{\"RequiredHttpHeaders\":{\"Cookie\":\"$privateCookie\",\"Set-Cookie\":\"$privateCookie; Secure\"}}",
        )
        val headers = JSONObject(sanitized).getJSONObject("RequiredHttpHeaders")
        check(headers.getString("Cookie") == "***" && headers.getString("Set-Cookie") == "***")
        check(privateCookie !in sanitized)
        check(LogRedactor.redact("error=\"Cookie: SID=demo-one; session=demo-two\"") == "error=\"Cookie: ***\"")
        val urls = JSONObject(LogRedactor.redactJson(
            """{"baseUrl":"https://user:pass@example.test:8096/emby?token=demo#fragment","nested":{"base_url":"http://[::1]:8096/path"}}""",
        ))
        check(urls.getString("baseUrl") == "example.test")
        check(urls.getJSONObject("nested").getString("base_url") == "[::1]")

        val playerDiagnostics = source("ui/screens/PlayerDiagnostics.kt")
        check("DiagExporter.appLogTail()" !in playerDiagnostics)
        val exporter = source("diag/DiagExporter.kt")
        check("Looper.myLooper() == Looper.getMainLooper()" in exporter)
        check("\"app.log\" to (AppLog.snapshot() + \"\\n[logcat]\\n\" + appLogTail())" in exporter)
        check("val isJson = name.endsWith(\".json\", ignoreCase = true)" in exporter)

        val dialog = source("ui/components/DiagExportDialog.kt")
        check("ActivityResultContracts.CreateDocument(\"application/zip\")" in dialog)
        check("FileProvider.getUriForFile" in dialog && "GRANT_READ_URI_PERMISSION" in dialog)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        check("androidx.core.content.FileProvider" in manifest && "${'$'}{applicationId}.fileprovider" in manifest)
        check(File("src/main/res/xml/diag_file_paths.xml").readText().contains("path=\"diag/\""))
    }

    private fun httpStatuses() {
        runPlaybackErrorClassificationAssertions()
    }

    private fun source(path: String): String = File("src/main/java/com/lumina/tv", path).readText()
}
