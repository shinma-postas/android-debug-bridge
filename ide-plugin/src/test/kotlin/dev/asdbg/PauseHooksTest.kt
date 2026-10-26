package dev.asdbg

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PauseHooksTest {
    private fun cfg(json: String) = PauseHookConfig.parse(json)

    @Test
    fun offByDefault() {
        val c = PauseHookConfig()
        assertFalse(c.enabled)
        assertNull(c.url)
        val hooks = PauseHooks(Files.createTempDirectory("asdbg-hooks"))
        hooks.load()
        assertFalse(hooks.active())
    }

    @Test
    fun parsesAndMergesConfig() {
        val c = cfg("""{"enabled":true,"url":"http://127.0.0.1:9/hook","headers":{"Authorization":"Bearer x"},"debounceMs":500,"onPauseCommand":["/bin/echo","hi"]}""")
        assertTrue(c.enabled)
        assertEquals("http://127.0.0.1:9/hook", c.url)
        assertEquals(500L, c.debounceMs)
        assertEquals(listOf("/bin/echo", "hi"), c.onPauseCommand)
        val cleared = c.merge(JsonParser.parseString("""{"url":"","enabled":false}""").asJsonObject)
        assertNull(cleared.url)
        assertFalse(cleared.enabled)
        assertEquals(mapOf("Authorization" to "Bearer x"), cleared.headers)
    }

    @Test
    fun rejectsBadConfig() {
        for (bad in listOf("""{"url":"file:///etc/passwd"}""", """{"onPauseCommand":"rm -rf /"}""", "[]")) {
            try {
                cfg(bad)
                fail("expected failure for $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun redactsHeadersAndUrlPath() {
        val c = cfg("""{"url":"https://hooks.example.com/services/SECRET?k=v","headers":{"Authorization":"Bearer secret"}}""")
        val r = c.redacted()
        assertEquals("https://hooks.example.com/***", r["url"])
        assertEquals(mapOf("Authorization" to "***"), r["headers"])
        assertFalse(r.toString().contains("secret", ignoreCase = false))
        assertEquals("http://127.0.0.1:8080", redactUrl("http://127.0.0.1:8080"))
    }

    @Test
    fun payloadIsLocationOnly() {
        val now = OffsetDateTime.of(2026, 10, 26, 16, 40, 0, 123_000_000, ZoneOffset.ofHours(9))
        val p = PausePayload.build(PausePayload.sample().copy(event = "paused"), 7, now)
        assertEquals(
            listOf(
                "event", "kind", "source", "sessionId", "sessionName", "project", "file", "line", "class", "method",
                "thread", "threadLevel", "policy", "breakpointId", "timestamp", "pluginVersion", "seq",
            ),
            p.keys.toList(),
        )
        assertEquals("2026-10-26T16:40:00.123+09:00", p["timestamp"])
        assertEquals(ASDBG_VERSION, p["pluginVersion"])
        assertEquals(42, p["line"])
    }

    @Test
    fun debounceDropsRepeatsOnly() {
        val d = Debouncer()
        val e = PauseEvent(sessionId = "s", thread = "main", file = "A.kt", line = 1)
        assertTrue(d.accept(e, 1000, 0))
        assertFalse(d.accept(e, 1000, 500))
        assertTrue(d.accept(e.copy(line = 2), 1000, 600))
        assertTrue(d.accept(e.copy(line = 2), 0, 700))
        assertTrue(d.accept(e.copy(line = 2), 1000, 5000))
    }

    @Test
    fun dedupPairsJdiAndSessionPause() {
        val d = PauseDedup(1500)
        assertFalse(d.seenByJdi("s", 10, 0))
        d.markJdi("s", 10, 0)
        assertTrue(d.seenByJdi("s", 10, 1000))
        assertFalse(d.seenByJdi("s", 11, 1000))
        assertFalse(d.seenByJdi("s", 10, 2000))
    }

    @Test
    fun eventsFileRotates() {
        val dir = Files.createTempDirectory("asdbg-events")
        val f = EventsFile(dir.resolve("pause-events.jsonl"))
        repeat(50) { f.append("{\"n\":$it,\"pad\":\"" + "x".repeat(400) + "\"}", 10_000) }
        assertTrue(Files.exists(dir.resolve("pause-events.jsonl.1")))
        assertTrue(Files.size(dir.resolve("pause-events.jsonl")) <= 10_000)
    }

    @Test
    fun deliversToHttpFileAndCommand() {
        val dir = Files.createTempDirectory("asdbg-deliver")
        val bodies = CopyOnWriteArrayList<String>()
        val auth = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { ex ->
            bodies += String(ex.requestBody.readAllBytes())
            auth += ex.requestHeaders.getFirst("Authorization")
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.start()
        try {
            val out = dir.resolve("cmd-out.txt")
            val env = mapOf("ASDBG_TEST_AUTH" to "Bearer from-env")
            val hooks = PauseHooks(dir, env = { env[it] })
            hooks.update(
                JsonParser.parseString(
                    """{"enabled":true,"url":"http://127.0.0.1:${server.address.port}/hook",
                    "headersFromEnv":{"Authorization":"ASDBG_TEST_AUTH"},
                    "onPauseCommand":["/bin/sh","-c","cat > \"${'$'}0\"; echo \"${'$'}ASDBG_FILE:${'$'}ASDBG_LINE:${'$'}ASDBG_THREAD\" >> \"${'$'}0\"","$out"]}""",
                ).asJsonObject,
            )
            assertTrue(hooks.active())
            val result = hooks.test()
            assertEquals(mapOf("status" to 204), result["http"])
            assertEquals("ok", result["eventsFile"])
            assertEquals("exit 0", result["command"])
            assertEquals(1, bodies.size)
            assertEquals("Bearer from-env", auth[0])
            val sent = JsonParser.parseString(bodies[0]).asJsonObject
            assertEquals("test", sent["event"].asString)
            assertEquals(42, sent["line"].asInt)
            val cmd = Files.readString(out)
            assertTrue(cmd.contains("\"event\":\"test\""))
            assertTrue(cmd.contains("app/src/main/java/com/example/MainActivity.kt:42:main"))
            assertEquals(1, Files.readAllLines(dir.resolve("pause-events.jsonl")).size)

            hooks.emit(PauseEvent(sessionId = "s", file = "B.kt", line = 3, thread = "main", kind = "step"))
            val deadline = System.currentTimeMillis() + 5000
            while (bodies.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(2, bodies.size)
            assertEquals("paused", JsonParser.parseString(bodies[1]).asJsonObject["event"].asString)
            val described = hooks.describe().toString()
            assertFalse(described.contains("from-env"))
            // Saved config is owner-only.
            val perms = Files.getPosixFilePermissions(hooks.configFile)
            assertEquals(2, perms.size)
            if (System.getProperty("asdbg.webhookExampleOut") != null) {
                Files.writeString(java.nio.file.Path.of(System.getProperty("asdbg.webhookExampleOut")), bodies[1])
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unreachableUrlFailsFastWithoutThrowing() {
        val dir = Files.createTempDirectory("asdbg-fail")
        val hooks = PauseHooks(dir)
        hooks.update(JsonParser.parseString("""{"enabled":true,"url":"http://127.0.0.1:1/x","eventsFile":false,"timeoutMs":500}""").asJsonObject)
        val t0 = System.nanoTime()
        val r = hooks.test()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000)
        assertTrue((r["http"] as String).startsWith("error: http"))
        assertFalse(r.toString().contains("127.0.0.1:1/x"))
    }
}
