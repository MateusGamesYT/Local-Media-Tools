package com.localmediatools.print

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's network rule, checked in its source: only the printing core opens connections, and every
 * place that does goes through LocalNetwork (local network only, no name lookups). Nothing else in
 * the app may use the network — no HTTP, no URLs, no sockets.
 */
class NetworkRuleTest {
    private val main = File("app/src/main/kotlin")
    private val forbidden = listOf(
        Regex("""\bjava\.net\.(URL|URI|HttpURLConnection|Socket|ServerSocket|DatagramSocket|MulticastSocket|InetAddress)\b"""),
        Regex("""\bjavax\.net\.ssl\b"""), Regex("""\bHttpsURLConnection\b"""), Regex("""\bopenConnection\("""), Regex("""\bokhttp3?\b"""),
        Regex("""\bURL\("""), Regex("""\bSocket\("""), Regex("""\bDatagramSocket\("""), Regex("""android\.net\.http"""), Regex("""\bWebView\b"""),
    )

    @Test fun onlyThePrintingCoreTalksToTheNetwork() {
        val files = main.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > 100)
        val core = File(main, "com/localmediatools/print/core")
        val offenders = files.filter { !it.startsWith(core) }.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && forbidden.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${f.relativeTo(main)}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("Network use outside the printing core:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun everyConnectionInThePrintingCoreIsCheckedFirst() {
        val core = File(main, "com/localmediatools/print/core")
        for (f in core.listFiles()!!.filter { it.extension == "kt" }) {
            val text = f.readText()
            val opens = Regex("""\b(Socket|DatagramSocket|MulticastSocket)\(""").findAll(text).count() + Regex("""\.connect\(""").findAll(text).count()
            if (opens == 0) continue
            // Each file that opens sockets checks addresses with LocalNetwork.
            assertTrue("${f.name} opens connections without LocalNetwork checks", text.contains("LocalNetwork.check(") || text.contains("LocalNetwork.isLocal("))
        }
        // Names are never resolved: no getByName on arbitrary text, only on IP literals inside LocalNetwork.
        val lookups = core.listFiles()!!.filter { it.name != "LocalNetwork.kt" }.filter { it.readText().contains("getByName(") || it.readText().contains("getAllByName(") }
        assertTrue("Name lookups in $lookups", lookups.isEmpty())
    }
}
