package dev.asdbg

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal const val ASDBG_VERSION = "0.1.8"

/**
 * Pause hooks config, stored in ~/.android-debug-bridge/webhook.json. Off by default.
 * headers hold literal values (for example Authorization); headersFromEnv maps a header to an env var name.
 * Header values are never logged or returned by describe().
 */
internal data class PauseHookConfig(
    val enabled: Boolean = false,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val headersFromEnv: Map<String, String> = emptyMap(),
    val debounceMs: Long = 0,
    val eventsFile: Boolean = true,
    val eventsMaxBytes: Long = 1_000_000,
    val onPauseCommand: List<String> = emptyList(),
    val commandTimeoutMs: Long = 10_000,
    val timeoutMs: Int = 3_000,
) {
    companion object {
        fun parse(text: String): PauseHookConfig {
            if (text.isBlank()) return PauseHookConfig()
            val o = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("webhook config must be a json object")
            return PauseHookConfig().merge(o)
        }
    }

    /** Applies the keys present in [o]; other fields stay. url "" or null clears the url. */
    fun merge(o: JsonObject): PauseHookConfig {
        var c = this
        fun has(k: String) = o.has(k)
        fun el(k: String): JsonElement? = o.get(k)?.takeIf { !it.isJsonNull }
        if (has("enabled")) c = c.copy(enabled = el("enabled")?.asBoolean ?: false)
        if (has("url")) {
            val url = el("url")?.asString?.trim()?.ifBlank { null }
            if (url != null) {
                val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
                require(scheme == "http" || scheme == "https") { "url must be http or https" }
            }
            c = c.copy(url = url)
        }
        if (has("headers")) c = c.copy(headers = stringMap(el("headers"), "headers"))
        if (has("headersFromEnv")) c = c.copy(headersFromEnv = stringMap(el("headersFromEnv"), "headersFromEnv"))
        if (has("debounceMs")) c = c.copy(debounceMs = (el("debounceMs")?.asLong ?: 0).coerceIn(0, 3_600_000))
        if (has("eventsFile")) c = c.copy(eventsFile = el("eventsFile")?.asBoolean ?: true)
        if (has("eventsMaxBytes")) c = c.copy(eventsMaxBytes = (el("eventsMaxBytes")?.asLong ?: 1_000_000).coerceIn(10_000, 100_000_000))
        if (has("onPauseCommand")) {
            val cmd = el("onPauseCommand")
            c = c.copy(
                onPauseCommand = when {
                    cmd == null -> emptyList()
                    cmd.isJsonArray -> cmd.asJsonArray.map { it.asString }
                    else -> throw IllegalArgumentException("onPauseCommand must be an argv array, e.g. [\"/usr/bin/say\", \"paused\"]")
                },
            )
        }
        if (has("commandTimeoutMs")) c = c.copy(commandTimeoutMs = (el("commandTimeoutMs")?.asLong ?: 10_000).coerceIn(100, 600_000))
        if (has("timeoutMs")) c = c.copy(timeoutMs = (el("timeoutMs")?.asInt ?: 3_000).coerceIn(100, 30_000))
        return c
    }

    private fun stringMap(e: JsonElement?, name: String): Map<String, String> {
        if (e == null) return emptyMap()
        require(e.isJsonObject) { "$name must be an object" }
        return e.asJsonObject.entrySet().associate { (k, v) -> k to v.asString }
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "url" to url,
        "headers" to headers,
        "headersFromEnv" to headersFromEnv,
        "debounceMs" to debounceMs,
        "eventsFile" to eventsFile,
        "eventsMaxBytes" to eventsMaxBytes,
        "onPauseCommand" to onPauseCommand,
        "commandTimeoutMs" to commandTimeoutMs,
        "timeoutMs" to timeoutMs,
    )

    /** Same as toJson, with header values and the url path/query hidden. */
    fun redacted(): Map<String, Any?> = toJson() + mapOf(
        "url" to url?.let { redactUrl(it) },
        "headers" to headers.mapValues { "***" },
    )
}

// scheme://host:port, plus a masked path marker when there is a path or query. Webhook urls often carry a secret in the path.
internal fun redactUrl(url: String): String = runCatching {
    val u = URI(url)
    val port = if (u.port >= 0) ":${u.port}" else ""
    val rest = if (!u.rawPath.isNullOrEmpty() && u.rawPath != "/" || u.rawQuery != null) "/***" else ""
    "${u.scheme}://${u.host}$port$rest"
}.getOrDefault("***")

/** Location-only description of one stop. No values, no log text, no token. */
internal data class PauseEvent(
    val event: String = "paused",
    val kind: String? = null,
    val source: String? = null,
    val sessionId: String? = null,
    val sessionName: String? = null,
    val project: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val className: String? = null,
    val method: String? = null,
    val thread: String? = null,
    val threadLevel: Boolean = false,
    val policy: String? = null,
    val contextPolicy: String? = null,
    val breakpointPolicy: String? = null,
    val breakpointId: String? = null,
)

internal object PausePayload {
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun build(e: PauseEvent, seq: Long, now: OffsetDateTime = OffsetDateTime.now()): Map<String, Any?> = linkedMapOf(
        "event" to e.event,
        "kind" to e.kind,
        "source" to e.source,
        "sessionId" to e.sessionId,
        "sessionName" to e.sessionName,
        "project" to e.project,
        "file" to e.file,
        "line" to e.line,
        "class" to e.className,
        "method" to e.method,
        "thread" to e.thread,
        "threadLevel" to e.threadLevel,
        "policy" to e.policy,
        "contextPolicy" to e.contextPolicy,
        "breakpointPolicy" to e.breakpointPolicy,
        "breakpointId" to e.breakpointId,
        "timestamp" to now.truncatedTo(ChronoUnit.MILLIS).format(iso),
        "pluginVersion" to ASDBG_VERSION,
        "seq" to seq,
    )

    fun sample(): PauseEvent = PauseEvent(
        event = "test",
        kind = "breakpoint",
        source = "test",
        sessionId = "000000000000",
        sessionName = "sample",
        project = "sample",
        file = "app/src/main/java/com/example/MainActivity.kt",
        line = 42,
        className = "com.example.MainActivity",
        method = "onCreate",
        thread = "main",
        threadLevel = false,
        policy = "all",
        contextPolicy = "all",
        breakpointPolicy = "all",
        breakpointId = null,
    )
}

/**
 * Pure mapping from the debugger facts of one stop to the event fields, kept free of IDE types so it can be unit-tested.
 * contextPolicy is the JDI suspend context's own policy, the same source `sessions` uses for suspendedThreads.
 * breakpointPolicy is the hit breakpoint's configured suspend policy (only for kind=breakpoint).
 */
internal object PauseMapping {
    private val known = setOf("all", "thread", "none")

    data class Policy(val policy: String, val threadLevel: Boolean, val contextPolicy: String?, val breakpointPolicy: String?)

    /** SuspendContext.getSuspendPolicy(): JDI EventRequest SUSPEND_NONE / SUSPEND_EVENT_THREAD / SUSPEND_ALL. */
    fun jdiPolicy(raw: Int?): String = when (raw) {
        0 -> "none"
        1 -> "thread"
        2 -> "all"
        else -> "unknown"
    }

    /** XBreakpoint SuspendPolicy enum name (ALL / THREAD / NONE). */
    fun breakpointPolicy(name: String?): String? = name?.lowercase()?.takeIf { it in known }

    /**
     * policy: the breakpoint's own setting on a breakpoint hit, otherwise the suspend context's policy.
     * threadLevel: what the VM actually did (context policy == thread); falls back to policy when the context is unknown.
     */
    fun policy(kind: String?, contextPolicy: String?, breakpointPolicy: String?): Policy {
        val ctx = contextPolicy?.lowercase()?.takeIf { it in known }
        val bp = breakpointPolicy?.lowercase()?.takeIf { it in known && kind == "breakpoint" }
        val policy = bp ?: ctx ?: "unknown"
        val threadLevel = if (ctx != null) ctx == "thread" else policy == "thread"
        return Policy(policy, threadLevel, ctx, bp)
    }

    // IntelliJ Java frame text: "onClick:4627, SrSelfTicketActivity (jp.co.example)" or with the runtime class in braces:
    // "invoke:12, SrSelfTicketActivity$a$1 {jp.co.example.SrSelfTicketActivity$a$1} (jp.co.example)".
    private val frameText = Regex("""^\s*([\w$<>\-]+):\d+,\s+([\w$]+)(?:\s+\{([\w.$]+)\})?(?:\s+\(([\w.]+)\))?""")

    /** class and method from the JDI Location when present, else parsed from the IDE stack frame text. */
    fun classAndMethod(declaringType: String?, method: String?, frameText: String?): Pair<String?, String?> {
        val cls = declaringType?.takeIf { it.isNotBlank() }
        val m = method?.takeIf { it.isNotBlank() }
        if (cls != null && m != null) return cls to m
        val match = frameText?.let { this.frameText.find(it) } ?: return cls to m
        val (fm, simple, runtime, pkg) = match.destructured
        val parsedClass = runtime.ifBlank { null } ?: if (pkg.isNotBlank()) "$pkg.$simple" else simple
        return (cls ?: parsedClass) to (m ?: fm)
    }
}

/** Drops repeats: the same session+thread+line within debounceMs. */
internal class Debouncer {
    private val last = ConcurrentHashMap<String, Pair<String, Long>>()

    fun accept(e: PauseEvent, debounceMs: Long, nowMs: Long): Boolean {
        if (debounceMs <= 0) return true
        val key = e.sessionId ?: ""
        val sig = "${e.thread}|${e.file}|${e.line}"
        val prev = last[key]
        last[key] = sig to nowMs
        return !(prev != null && prev.first == sig && nowMs - prev.second < debounceMs)
    }
}

/** Appends one JSON line per event; renames to .1 when the file passes maxBytes. */
internal class EventsFile(val path: Path) {
    @Synchronized
    fun append(json: String, maxBytes: Long) {
        Files.createDirectories(path.parent)
        if (Files.exists(path) && Files.size(path) + json.length + 1 > maxBytes) {
            Files.move(path, path.resolveSibling(path.fileName.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING)
        }
        val fresh = !Files.exists(path)
        Files.writeString(path, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if (fresh) ownerOnly(path)
    }
}

internal fun ownerOnly(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
}

/**
 * Sends pause events to the events file, an HTTP POST, and a local command. Everything runs off the
 * calling thread on small bounded executors; a full queue drops the event and counts it. No retries.
 */
internal class PauseHooks(
    private val home: Path,
    private val env: (String) -> String? = System::getenv,
    private val log: (String) -> Unit = {},
) {
    private val gson: Gson = GsonBuilder().serializeNulls().create()
    val configFile: Path = home.resolve("webhook.json")
    val events = EventsFile(home.resolve("pause-events.jsonl"))
    private val commandLog: Path = home.resolve("on-pause.log")
    @Volatile var config = PauseHookConfig()
        private set
    private val debouncer = Debouncer()
    private val seq = AtomicLong()
    private val sent = AtomicLong()
    private val failed = AtomicLong()
    private val dropped = AtomicLong()
    private val debounced = AtomicLong()
    @Volatile private var lastError: String? = null
    @Volatile private var lastEventAt: String? = null

    private val delivery = pool("asdbg-pause-hook", 32)
    private val commands = pool("asdbg-pause-cmd", 16)

    private fun pool(name: String, queue: Int) = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(queue),
        { r -> Thread(r, name).apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() },
    ).apply { allowCoreThreadTimeOut(true) }

    fun load() {
        config = try {
            if (Files.exists(configFile)) PauseHookConfig.parse(Files.readString(configFile)) else PauseHookConfig()
        } catch (e: Exception) {
            log("asdbg webhook.json ignored: ${e.javaClass.simpleName}: ${e.message}")
            PauseHookConfig()
        }
    }

    @Synchronized
    fun update(patch: JsonObject): PauseHookConfig {
        val next = config.merge(patch)
        Files.createDirectories(home)
        Files.writeString(configFile, GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(next.toJson()))
        ownerOnly(configFile)
        config = next
        return next
    }

    /** True when a pause should be collected at all; keeps the listener free when hooks are off. */
    fun active(): Boolean = config.let { it.enabled && (it.eventsFile || it.url != null || it.onPauseCommand.isNotEmpty()) }

    fun describe(): Map<String, Any?> = linkedMapOf(
        "config" to config.redacted(),
        "active" to active(),
        "configFile" to configFile.toString(),
        "eventsFile" to events.path.toString(),
        "commandLog" to commandLog.toString(),
        "stats" to mapOf(
            "events" to seq.get(),
            "sent" to sent.get(),
            "failed" to failed.get(),
            "dropped" to dropped.get(),
            "debounced" to debounced.get(),
            "lastError" to lastError,
            "lastEventAt" to lastEventAt,
        ),
        "pluginVersion" to ASDBG_VERSION,
    )

    /** Called from any thread. Returns immediately. */
    fun emit(e: PauseEvent) {
        val c = config
        if (!active()) return
        if (!debouncer.accept(e, c.debounceMs, System.currentTimeMillis())) {
            debounced.incrementAndGet()
            return
        }
        val payload = PausePayload.build(e, seq.incrementAndGet())
        lastEventAt = payload["timestamp"] as String
        delivery.execute { deliver(c, payload) }
    }

    /** Sends the sample payload through every configured channel, even when disabled, and waits for the result. */
    fun test(): Map<String, Any?> {
        val c = config
        val payload = PausePayload.build(PausePayload.sample(), 0)
        val result = java.util.concurrent.CompletableFuture<Map<String, Any?>>()
        delivery.execute { result.complete(deliver(c, payload, waitCommand = true)) }
        val out = try {
            result.get(c.timeoutMs * 2L + c.commandTimeoutMs + 2_000L, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            mapOf("error" to e.javaClass.simpleName)
        }
        return mapOf("payload" to payload) + out
    }

    private fun deliver(c: PauseHookConfig, payload: Map<String, Any?>, waitCommand: Boolean = false): Map<String, Any?> {
        val json = gson.toJson(payload)
        val out = linkedMapOf<String, Any?>()
        if (c.eventsFile) {
            out["eventsFile"] = try {
                events.append(json, c.eventsMaxBytes)
                "ok"
            } catch (e: Exception) {
                fail("events file: ${e.javaClass.simpleName}")
            }
        }
        if (c.url != null) out["http"] = post(c, json)
        if (c.onPauseCommand.isNotEmpty()) {
            val task = java.util.concurrent.CompletableFuture<String>()
            commands.execute { task.complete(runCommand(c, payload, json)) }
            out["command"] = if (waitCommand) {
                runCatching { task.get(c.commandTimeoutMs + 2_000L, TimeUnit.MILLISECONDS) }.getOrElse { "error: ${it.javaClass.simpleName}" }
            } else {
                "started"
            }
        }
        return out
    }

    private fun post(c: PauseHookConfig, json: String): Any {
        val url = c.url ?: return "no url"
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = c.timeoutMs
            conn.readTimeout = c.timeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "asdbg/$ASDBG_VERSION")
            c.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            c.headersFromEnv.forEach { (k, name) -> env(name)?.let { conn.setRequestProperty(k, it) } }
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            runCatching { (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readNBytes(4096) } }
            conn.disconnect()
            if (code in 200..299) {
                sent.incrementAndGet()
                mapOf("status" to code)
            } else {
                fail("http status $code")
                mapOf("status" to code)
            }
        } catch (e: Exception) {
            // Exception text can echo the url; keep only the type.
            fail("http ${e.javaClass.simpleName}")
        }
    }

    private fun runCommand(c: PauseHookConfig, payload: Map<String, Any?>, json: String): String = try {
        val pb = ProcessBuilder(c.onPauseCommand)
        val e = pb.environment()
        fun put(name: String, key: String) { payload[key]?.let { e[name] = it.toString() } }
        put("ASDBG_EVENT", "event")
        put("ASDBG_KIND", "kind")
        put("ASDBG_FILE", "file")
        put("ASDBG_LINE", "line")
        put("ASDBG_THREAD", "thread")
        put("ASDBG_SESSION", "sessionId")
        put("ASDBG_TIMESTAMP", "timestamp")
        Files.createDirectories(home)
        val logFile = commandLog.toFile()
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        val p = pb.start()
        p.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        if (p.waitFor(c.commandTimeoutMs, TimeUnit.MILLISECONDS)) {
            "exit ${p.exitValue()}"
        } else {
            p.destroyForcibly()
            fail("command timeout")
        }
    } catch (e: Exception) {
        fail("command ${e.javaClass.simpleName}")
    }

    private fun fail(what: String): String {
        failed.incrementAndGet()
        lastError = what
        log("asdbg pause hook failed: $what")
        return "error: $what"
    }
}

/** Pairs a JDI pause with the XDebugSession pause for the same stop so only one event goes out. */
internal class PauseDedup(private val windowMs: Long = 1_500) {
    private data class Mark(val line: Int?, val thread: String?, val atMs: Long)
    private val jdi = ConcurrentHashMap<String, Mark>()

    fun markJdi(sessionId: String, line: Int?, nowMs: Long, thread: String? = null) {
        jdi[sessionId] = Mark(line, thread, nowMs)
    }

    /**
     * True when a session-level pause already went out through the JDI listener. Matches on the line or the thread,
     * because the JDI line (Kotlin inline/SMAP) and the IDE position line can differ for the same stop.
     */
    fun seenByJdi(sessionId: String, line: Int?, nowMs: Long, thread: String? = null): Boolean {
        val prev = jdi[sessionId] ?: return false
        if (nowMs - prev.atMs >= windowMs) return false
        val sameLine = line == null || prev.line == null || prev.line == line
        val sameThread = thread != null && prev.thread != null && prev.thread == thread
        return sameLine || sameThread
    }
}

