package dev.asdbg

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.debug.DebugSessionStarter
import com.android.tools.idea.execution.common.debug.impl.java.AndroidJavaDebugger
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.Icon

class BridgeStartup : AppLifecycleListener {
    override fun appStarted() {
        BridgeServer.start()
    }

    override fun appClosing() {
        BridgeServer.stop()
    }
}

private class ApiException(val status: Int, message: String) : RuntimeException(message)

private object BridgeServer {
    private val log = Logger.getInstance("asdbg")
    private val gson = Gson()
    private val home: Path = Path.of(System.getProperty("user.home"), ".android-debug-bridge")
    private val tokenFile = home.resolve("token")
    private val endpointFile = home.resolve("endpoint.json")
    private val ownedFile = home.resolve("owned.txt")
    private val sessions = SessionBook()
    private var server: HttpServer? = null
    private val hooks = PauseHooks(home, log = { log.warn(it) })
    private val dedup = PauseDedup()
    private val jdiListening = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<XDebugSession, Boolean>())
    private val lastAction = ConcurrentHashMap<String, Pair<String, Long>>()

    fun start() {
        if (server != null) return
        Files.createDirectories(home)
        val token = loadOrCreateToken()
        hooks.load()
        val http = bind()
        http.createContext("/") { exchange ->
            try {
                route(exchange, token)
            } catch (e: ApiException) {
                send(exchange, e.status, mapOf("error" to (e.message ?: "error")))
            } catch (e: Exception) {
                log.warn("asdbg request failed", e)
                send(exchange, 500, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            }
        }
        http.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "asdbg").apply { isDaemon = true }
        }
        http.start()
        server = http
        val port = http.address.port
        Files.writeString(
            endpointFile,
            gson.toJson(mapOf("url" to "http://127.0.0.1:$port", "tokenFile" to tokenFile.toString())),
        )
        watchOpenProjects()
        log.info("asdbg listening on 127.0.0.1:$port")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun bind(): HttpServer {
        var last: Exception? = null
        for (port in 29191..29210) {
            try {
                return HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("no free localhost port", last)
    }

    private fun loadOrCreateToken(): String {
        if (Files.exists(tokenFile)) return Files.readString(tokenFile).trim()
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        Files.writeString(tokenFile, token)
        runCatching {
            Files.setPosixFilePermissions(
                tokenFile,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        return token
    }

    private fun watchOpenProjects() {
        val app = ApplicationManager.getApplication()
        app.messageBus.connect(app).subscribe(
            ProjectManager.TOPIC,
            object : ProjectManagerListener {
                override fun projectOpened(project: Project) {
                    attach(project)
                }
            },
        )
        ProjectManager.getInstance().openProjects.forEach { attach(it) }
    }

    private fun attach(project: Project) {
        if (project.isDisposed) return
        project.messageBus.connect(project).subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    watch(debugProcess.session)
                }

                override fun processStopped(debugProcess: XDebugProcess) {
                    val session = debugProcess.session
                    sessions.fire(session, "stopped")
                    sessions.remove(session)
                }
            },
        )
        XDebuggerManager.getInstance(project).debugSessions.forEach { watch(it) }
    }

    private fun watch(session: XDebugSession) {
        if (!sessions.markWatched(session)) return
        ensurePauseListener(session)
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                sessions.fire(session, "paused")
                if (hooks.active()) {
                    // The JDI process may not have existed when the session was first watched; retry so later stops use it.
                    ensurePauseListener(session)
                    val pos = position(session)
                    val stack = safe { session.suspendContext?.activeExecutionStack?.displayName }
                    val frameText = safe {
                        session.currentStackFrame?.let { f -> SimpleColoredText().also { f.customizeTextPresentation(it) }.toString() }
                    }
                    ApplicationManager.getApplication().executeOnPooledThread { emitSessionPause(session, pos, stack, frameText) }
                }
            }

            override fun sessionStopped() {
                sessions.fire(session, "stopped")
                sessions.remove(session)
                synchronized(jdiListening) { jdiListening.remove(session) }
            }
        })
    }

    /** Adds the JDI pause listener once per session. Returns false (and the session listener stays the only source) when not attachable yet. */
    private fun ensurePauseListener(session: XDebugSession): Boolean = synchronized(jdiListening) {
        if (session in jdiListening) return@synchronized true
        val ok = JavaDebug.addPauseListener(session) { raw ->
            if (hooks.active()) {
                ApplicationManager.getApplication().executeOnPooledThread { emitJdiPause(session, raw) }
            }
        }
        if (ok) jdiListening.add(session) else log.info("asdbg: JDI pause listener not attached yet; session listener fallback in use")
        ok
    }

    private fun route(exchange: HttpExchange, token: String) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod.uppercase()
        if (method == "GET" && path == "/health") {
            send(exchange, 200, mapOf("ok" to true, "version" to ASDBG_VERSION))
            return
        }
        if (!path.startsWith("/v1/")) {
            send(exchange, 404, mapOf("error" to "not found"))
            return
        }
        authorize(exchange, token)
        when {
            method == "GET" && path == "/v1/webhook" -> return send(exchange, 200, hooks.describe())
            method == "POST" && path == "/v1/webhook" -> {
                try {
                    hooks.update(body(exchange))
                } catch (e: IllegalArgumentException) {
                    throw ApiException(400, e.message ?: "bad webhook config")
                } catch (e: IllegalStateException) {
                    throw ApiException(400, e.message ?: "bad webhook config")
                } catch (e: UnsupportedOperationException) {
                    throw ApiException(400, "bad value type in webhook config")
                }
                return send(exchange, 200, hooks.describe())
            }
            method == "POST" && path == "/v1/webhook/test" -> return send(exchange, 200, hooks.test())
        }
        val project = project(exchange)
        when {
            method == "POST" && path == "/v1/breakpoints" -> send(exchange, 200, addBreakpoint(project, body(exchange)))
            method == "GET" && path == "/v1/breakpoints" -> send(exchange, 200, mapOf("breakpoints" to listBreakpoints(project)))
            method == "DELETE" && path == "/v1/breakpoints" -> send(exchange, 200, clearAllBreakpoints(project))
            method == "POST" && path.startsWith("/v1/breakpoints/") ->
                send(exchange, 200, updateBreakpoint(project, path.removePrefix("/v1/breakpoints/"), body(exchange)))
            method == "DELETE" && path.startsWith("/v1/breakpoints/") -> {
                removeBreakpoint(project, path.removePrefix("/v1/breakpoints/"))
                send(exchange, 200, mapOf("removed" to true))
            }
            method == "POST" && path == "/v1/sessions" -> send(exchange, 200, startSession(project, body(exchange)))
            method == "POST" && path == "/v1/attach" -> send(exchange, 200, attachToProcess(project, body(exchange)))
            method == "GET" && path == "/v1/sessions" -> send(exchange, 200, mapOf("sessions" to listSessions(project)))
            method == "POST" && path.matches(Regex("/v1/sessions/[^/]+/control")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/control")
                val allowCalls = exchange.requestHeaders.getFirst("X-ASdbg-Allow-Eval") == "1"
                send(exchange, 200, control(project, id, body(exchange), allowCalls))
            }
            method == "GET" && path.matches(Regex("/v1/sessions/[^/]+/stack")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/stack")
                send(exchange, 200, mapOf("frames" to stack(project, id)))
            }
            method == "GET" && path.matches(Regex("/v1/sessions/[^/]+/threads")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/threads")
                val frames = (query(exchange, "frames")?.toIntOrNull() ?: 0).coerceIn(0, 200)
                val session = requireSession(project, id)
                val threads = JavaDebug.threads(session, frames) ?: throw ApiException(409, "not a Java debug session")
                threads.forEach { thread ->
                    @Suppress("UNCHECKED_CAST")
                    (thread["frames"] as? List<MutableMap<String, Any?>>)?.forEach { frame ->
                        frame["file"] = (frame["sourcePath"] as? String)?.let { resolveSource(project, it) }
                    }
                }
                send(exchange, 200, mapOf("sessionId" to id, "threads" to threads))
            }
            method == "GET" && path.matches(Regex("/v1/sessions/[^/]+/variables")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/variables")
                send(exchange, 200, mapOf("variables" to variables(project, id)))
            }
            method == "POST" && path.matches(Regex("/v1/sessions/[^/]+/evaluate")) -> {
                if (exchange.requestHeaders.getFirst("X-ASdbg-Allow-Eval") != "1") {
                    throw ApiException(403, "evaluate requires X-ASdbg-Allow-Eval: 1")
                }
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/evaluate")
                send(exchange, 200, evaluate(project, id, body(exchange)))
            }
            method == "GET" && path.matches(Regex("/v1/sessions/[^/]+/events")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/events")
                val timeout = query(exchange, "timeout")?.toLongOrNull() ?: 60_000L
                send(exchange, 200, waitPause(requireSession(project, id), timeout))
            }
            else -> send(exchange, 404, mapOf("error" to "not found"))
        }
    }

    private fun authorize(exchange: HttpExchange, token: String) {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: ""
        val expected = "Bearer $token"
        val ok = MessageDigest.isEqual(
            header.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
        if (!ok) throw ApiException(401, "unauthorized")
    }

    private fun project(exchange: HttpExchange): Project {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && it.basePath != null }
        val wanted = exchange.requestHeaders.getFirst("X-Project-Path")
        if (wanted != null) {
            val file = File(wanted).canonicalFile
            return open.firstOrNull { File(it.basePath!!).canonicalFile == file }
                ?: throw ApiException(404, "project is not open: $wanted")
        }
        if (open.size == 1) return open[0]
        throw ApiException(400, "set X-Project-Path. open=${open.map { it.basePath }}")
    }

    private val breakpointKinds = mapOf(
        "java-line" to "line",
        "kotlin-line" to "line",
        "java-method" to "method",
        "kotlin-function" to "method",
        "java-field" to "field",
        "kotlin-field" to "field",
        "java-exception" to "exception",
    )

    private fun normalizeKind(raw: String): String {
        val kind = when (raw.lowercase()) {
            "function" -> "method"
            "watchpoint", "watch" -> "field"
            else -> raw.lowercase()
        }
        if (kind !in setOf("line", "method", "field", "exception")) {
            throw ApiException(400, "unknown breakpoint type $raw. known=line,method,field,exception")
        }
        return kind
    }

    private fun kindOf(type: XBreakpointType<*, *>): String = breakpointKinds[type.id] ?: type.id

    private fun addBreakpoint(project: Project, json: JsonObject): Map<String, Any?> = edtWrite {
        val kind = normalizeKind(json.str("type") ?: "line")
        if (kind == "exception") return@edtWrite addExceptionBreakpoint(project, json)
        val rel = json.str("file") ?: throw ApiException(400, "file is required")
        val line = json.int("line") ?: throw ApiException(400, "line is required")
        val line0 = line - 1
        if (line0 < 0) throw ApiException(400, "line starts at 1")
        val abs = if (File(rel).isAbsolute) rel else File(project.basePath, rel).path
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(abs)
            ?: throw ApiException(404, "file not found: $abs")
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filter { kindOf(it) == kind }
            .filterIsInstance<XLineBreakpointType<*>>()
            .sortedByDescending { it.priority }
            .firstOrNull { it.canPutAt(file, line0, project) }
            ?: throw ApiException(400, "cannot put $kind at $abs:$line. available=${availableKinds(project, file, line0)}")
        val condition = json.str("condition")
        val temporary = json.bool("temporary")
        val existing = findLineBreakpoint(project, type, file, line0)
        if (existing != null) {
            if (owned(breakpointId(existing))) {
                if (!condition.isNullOrBlank()) existing.setCondition(condition)
                applySuspend(existing, json)
            }
            return@edtWrite breakpointDto(project, existing) + mapOf("created" to false)
        }
        val bp = addLineBreakpoint(project, type, file, line0, temporary)
        markOwned(breakpointId(bp))
        if (!condition.isNullOrBlank()) bp.setCondition(condition)
        applySuspend(bp, json)
        breakpointDto(project, bp) + mapOf("created" to true)
    }

    private fun applySuspend(bp: XBreakpoint<*>, json: JsonObject) {
        val raw = json.str("suspend") ?: return
        val policy = when (raw.lowercase()) {
            "all" -> SuspendPolicy.ALL
            "thread" -> SuspendPolicy.THREAD
            "none" -> SuspendPolicy.NONE
            else -> throw ApiException(400, "unknown suspend $raw. known=all,thread,none")
        }
        val lineType = bp.type as? XLineBreakpointType<*>
        if (policy == SuspendPolicy.THREAD && lineType != null && !lineType.isSuspendThreadSupported) {
            throw ApiException(400, "thread suspend is not supported for ${kindOf(bp.type)}")
        }
        bp.suspendPolicy = policy
    }

    private fun availableKinds(project: Project, file: VirtualFile, line0: Int): List<String> =
        XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<XLineBreakpointType<*>>()
            .filter { it.canPutAt(file, line0, project) }
            .map { kindOf(it) }
            .distinct()

    private fun addExceptionBreakpoint(project: Project, json: JsonObject): Map<String, Any?> {
        val className = json.str("class")
            ?: throw ApiException(400, "class is required for type exception. Use * for any exception")
        val wanted = if (className == "*") "" else className
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .firstOrNull { it.id == "java-exception" }
            ?: throw ApiException(400, "exception breakpoints are unavailable")
        val existing = manager.allBreakpoints.firstOrNull {
            it.type.id == "java-exception" && exceptionName(it) == wanted
        }
        if (existing != null) {
            if (owned(breakpointId(existing))) applySuspend(existing, json)
            return breakpointDto(project, existing) + mapOf("created" to false)
        }
        val props = type.createProperties()
            ?: throw ApiException(500, "could not create exception breakpoint")
        if (wanted.isNotEmpty()) setField(props, "myQualifiedName", wanted)
        val bp = addGeneric(project, type, props)
        markOwned(breakpointId(bp))
        json.str("condition")?.let { bp.setCondition(it) }
        applySuspend(bp, json)
        return breakpointDto(project, bp) + mapOf("created" to true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addGeneric(
        project: Project,
        type: XBreakpointType<*, *>,
        props: XBreakpointProperties<*>,
    ): XBreakpoint<*> {
        val typed = type as XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>
        return XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(typed, props)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.fields.firstOrNull { it.name == name }
            ?: throw ApiException(500, "no field $name on ${target.javaClass.name}")
        field.set(target, value)
    }

    private fun exceptionName(bp: XBreakpoint<*>): String {
        val props = bp.properties ?: return ""
        val value = props.javaClass.fields.firstOrNull { it.name == "myQualifiedName" }?.get(props) as? String
        return value.orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun typed(type: XLineBreakpointType<*>): XLineBreakpointType<XBreakpointProperties<*>> =
        type as XLineBreakpointType<XBreakpointProperties<*>>

    private fun findLineBreakpoint(
        project: Project,
        type: XLineBreakpointType<*>,
        file: VirtualFile,
        line0: Int,
    ): XLineBreakpoint<*>? =
        XDebuggerManager.getInstance(project).breakpointManager.findBreakpointAtLine(typed(type), file, line0)

    private fun addLineBreakpoint(
        project: Project,
        type: XLineBreakpointType<*>,
        file: VirtualFile,
        line0: Int,
        temporary: Boolean,
    ): XLineBreakpoint<*> {
        val typed = typed(type)
        val props = typed.createBreakpointProperties(file, line0)
        val bp = XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(typed, file.url, line0, props)
        if (temporary) bp.isTemporary = true
        return bp
    }

    private fun listBreakpoints(project: Project): List<Map<String, Any?>> = edtRead {
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .map { breakpointDto(project, it) }
    }

    private fun clearAllBreakpoints(project: Project): Map<String, Any?> = edtWrite {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val ids = manager.allBreakpoints.map { breakpointId(it) }
        ids.forEach { unmarkOwned(it) }
        manager.allBreakpoints.toList().forEach { manager.removeBreakpoint(it) }
        mapOf("removed" to ids.size)
    }

    private fun removeBreakpoint(project: Project, id: String) = edtWrite {
        val decoded = URLDecoder.decode(id, StandardCharsets.UTF_8)
        val bp = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .firstOrNull { breakpointId(it) == decoded }
            ?: throw ApiException(404, "breakpoint not found")
        if (!owned(decoded)) throw ApiException(403, "breakpoint was not created by asdbg")
        XDebuggerManager.getInstance(project).breakpointManager.removeBreakpoint(bp)
        unmarkOwned(decoded)
    }

    private fun stopAllDebugSessions(project: Project) = edtWrite {
        XDebuggerManager.getInstance(project).debugSessions.toList().forEach { it.stop() }
    }

    private fun attachToProcess(project: Project, json: JsonObject): Map<String, Any?> {
        val serial = json.str("serial") ?: throw ApiException(400, "serial is required")
        val packageName = json.str("package") ?: throw ApiException(400, "package is required. e.g. com.example.app")
        val timeout = json.long("timeout") ?: 60_000L
        if (timeout < 1_000L) {
            throw ApiException(400, "timeout is in milliseconds. got $timeout ms. Use 30000 for 30 seconds")
        }
        val warnings = mutableListOf<String>()
        // A breakpoint placed while the project is indexing or still opening may not reach the VM.
        val waitedForIndex = if (json.bool("waitReady")) waitForSmartMode(project, timeout - 1_000L) else 0L
        if (!project.isInitialized) warnings += "project is not fully loaded yet. breakpoints may not be registered"
        if (DumbService.isDumb(project)) {
            warnings += "project is indexing (dumb mode). breakpoints may not hit until it ends. " +
                "pass waitReady to wait, and check verified in bp-list after attach"
        }
        val attachTimeout = (timeout - waitedForIndex).coerceAtLeast(1_000L)
        val force = json.bool("force")
        if (force) stopAllDebugSessions(project)
        val bridge = AndroidDebugBridge.getBridge() ?: throw ApiException(503, "adb bridge is not ready")
        if (!bridge.isConnected) throw ApiException(503, "adb bridge is not connected")
        val devices = bridge.devices.toList()
        val device = devices.firstOrNull { matchesSerial(it.serialNumber, serial) }
            ?: throw ApiException(404, "device not found: $serial. known=${devices.map { it.serialNumber }}")
        if (!device.isOnline) throw ApiException(409, "device offline: ${device.serialNumber}")
        val waitForProcess = json.bool("waitForProcess")
        val client = if (waitForProcess) {
            runBlocking {
                withTimeout(attachTimeout) {
                    var found: Client? = null
                    while (isActive) {
                        found = pickClient(device.clients.toList(), packageName)
                        if (found != null) break
                        delay(400)
                    }
                    found ?: throw ApiException(404, "debuggable process not found: $packageName")
                }
            }
        } else {
            val clients = device.clients.toList()
            pickClient(clients, packageName)
                ?: throw ApiException(404, "debuggable process not found: $packageName. clients=${clients.map { clientLabel(it) }}")
        }
        val debugger = AndroidJavaDebugger()
        if (!debugger.supportsProject(project)) throw ApiException(409, "Java debugger does not support this project")
        if (!force) {
            debugger.getExistingDebugSession(project, client)?.let { existing ->
                watch(existing)
                return sessionDto(existing) + mapOf(
                    "attached" to false,
                    "already" to true,
                    "warnings" to warnings,
                    "waitedReadyMs" to waitedForIndex,
                    "serial" to device.serialNumber,
                    "package" to packageName,
                    "pid" to client.clientData.pid,
                )
            }
        }
        if (client.isDebuggerAttached) {
            throw ApiException(409, "a debugger is already attached to ${clientLabel(client)}")
        }
        val before = XDebuggerManager.getInstance(project).debugSessions.toSet()
        val session = try {
            runBlocking {
                withTimeout(attachTimeout) {
                    DebugSessionStarter.attachDebuggerToClientAndShowTab(
                        project,
                        client,
                        debugger,
                        debugger.createState(),
                    )
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A cancelled attach can leave a session that keeps listening for JDWP. Stop only what this call made.
            val leftover = XDebuggerManager.getInstance(project).debugSessions.filter { it !in before }
            safe { edt { leftover.forEach { it.stop() } } }
            throw ApiException(504, "attach timed out after $attachTimeout ms. stopped ${leftover.size} half-open session(s)")
        }
        watch(session)
        return sessionDto(session) + mapOf(
            "attached" to true,
            "warnings" to warnings,
            "waitedReadyMs" to waitedForIndex,
            "serial" to device.serialNumber,
            "package" to packageName,
            "pid" to client.clientData.pid,
        )
    }

    /** Waits while the project is indexing (dumb mode) or still opening. Returns the time waited in ms. */
    private fun waitForSmartMode(project: Project, limitMs: Long): Long {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < limitMs) {
            if (project.isInitialized && !DumbService.isDumb(project)) break
            Thread.sleep(250)
        }
        return System.currentTimeMillis() - started
    }

    private fun matchesSerial(actual: String, wanted: String): Boolean =
        actual == wanted || actual.startsWith(wanted)

    private fun pickClient(clients: List<Client>, packageName: String): Client? {
        fun score(client: Client): Int {
            val data = client.clientData
            val names = listOf(data.packageName, data.processName, data.clientDescription).map { it.orEmpty() }
            return when {
                names.any { it == packageName } -> 3
                names.any { it.startsWith("$packageName:") } -> 2
                names.any { it.contains(packageName) } -> 1
                else -> 0
            }
        }
        return clients.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }
    }

    private fun clientLabel(client: Client): String {
        val data = client.clientData
        val name = data.packageName ?: data.clientDescription ?: data.processName ?: "?"
        return "$name pid=${data.pid}"
    }

    private fun startSession(project: Project, json: JsonObject): Map<String, Any?> {
        val name = json.str("configuration") ?: throw ApiException(400, "configuration is required")
        val timeout = json.long("timeout") ?: 30_000L
        val settings = edtRead { RunManager.getInstance(project).findConfigurationByName(name) }
            ?: throw ApiException(
                404,
                "run configuration not found: $name. known=${configurationNames(project)}",
            )
        val before = XDebuggerManager.getInstance(project).debugSessions.toSet()
        edt {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val fresh = XDebuggerManager.getInstance(project).debugSessions.firstOrNull { it !in before }
            if (fresh != null) {
                watch(fresh)
                return sessionDto(fresh)
            }
            Thread.sleep(200)
        }
        throw ApiException(504, "debug session did not start")
    }

    private fun configurationNames(project: Project): List<String> = edtRead {
        RunManager.getInstance(project).allSettings.map { it.name }
    }

    private fun listSessions(project: Project): List<Map<String, Any?>> =
        XDebuggerManager.getInstance(project).debugSessions.map {
            watch(it)
            sessionDto(it)
        }

    private fun control(project: Project, id: String, json: JsonObject, allowCalls: Boolean = false): Map<String, Any?> {
        val action = json.str("action") ?: throw ApiException(400, "action is required")
        val timeout = json.long("timeout") ?: 60_000L
        val session = requireSession(project, id)
        val sessionId = sessions.idOf(session)
        val extra = mutableMapOf<String, Any?>()
        if (action in setOf("PAUSE", "STEP_OVER", "STEP_INTO", "STEP_OUT", "RUN_TO_LINE")) {
            lastAction[sessionId] = action to System.currentTimeMillis()
        }
        val lock = sessions.lock(session)
        synchronized(lock) {
            when (action) {
                "PAUSE" -> edt { session.pause() }
                "RESUME" -> {
                    if (session.isSuspended) {
                        edt { session.resume() }
                    } else {
                        val resumed = JavaDebug.resumeThreads(session)
                        if (resumed.isNullOrEmpty()) throw ApiException(409, "session is running")
                        extra["resumed"] = resumed
                    }
                }
                "RESUME_VM" -> {
                    if (!JavaDebug.resumeVm(session)) throw ApiException(409, "not a Java debug session")
                    extra["vmResumed"] = true
                }
                "STEP_OVER", "STEP_INTO", "STEP_OUT" -> {
                    val thread = json.str("thread")
                    if (thread == null && session.isSuspended) {
                        edt {
                            when (action) {
                                "STEP_OVER" -> session.stepOver(false)
                                "STEP_INTO" -> session.stepInto()
                                else -> session.stepOut()
                            }
                        }
                    } else {
                        // A named thread, or a suspend=thread stop the session does not report as suspended.
                        extra["thread"] = JavaDebug.step(session, action, thread)
                            ?: throw ApiException(409, "not a Java debug session")
                    }
                }
                "SUSPEND_THREAD" -> {
                    val thread = json.str("thread") ?: throw ApiException(400, "thread is required")
                    extra["thread"] = JavaDebug.freezeThread(session, thread) ?: throw ApiException(409, "not a Java debug session")
                }
                "RESUME_THREAD" -> {
                    val thread = json.str("thread") ?: throw ApiException(400, "thread is required")
                    extra["thread"] = JavaDebug.resumeThread(session, thread) ?: throw ApiException(409, "not a Java debug session")
                }
                "RUN_TO_LINE" -> extra["target"] = runToLine(session, json)
                "SELECT_FRAME" -> extra["frame"] = selectFrame(session, json)
                "STOP" -> edt { session.stop() }
                "DETACH" -> {
                    val handler = session.debugProcess.processHandler
                    edt { handler.detachProcess() }
                    extra["detached"] = true
                }
                "MUTE", "UNMUTE" -> {
                    edt { session.setBreakpointMuted(action == "MUTE") }
                    extra["muted"] = safe { session.areBreakpointsMuted() }
                }
                "WAIT_FOR_PAUSE" -> {
                    val paused = waitPause(session, timeout)
                    if (!json.bool("snapshot") || paused["event"] != "paused") return paused
                    return paused + try {
                        mapOf("snapshot" to snapshot(session, snapshotOptions(json), allowCalls))
                    } catch (e: ApiException) {
                        mapOf("snapshotError" to e.message)
                    } catch (e: Exception) {
                        mapOf("snapshotError" to (e.message ?: e.javaClass.simpleName))
                    }
                }
                "SNAPSHOT" -> return snapshot(session, snapshotOptions(json), allowCalls)
                "SET_VALUE" -> {
                    val sets = parseSets(json)
                    val results = JavaDebug.setValues(session, json.str("thread"), json.int("frameIndex") ?: 0, sets)
                        ?: throw ApiException(409, "not a Java debug session")
                    extra["sets"] = results.map { setDto(it) }
                    if (json.bool("resume")) {
                        if (session.isSuspended) {
                            edt { session.resume() }
                            extra["resumed"] = true
                        } else {
                            val resumed = JavaDebug.resumeThreads(session)
                            if (!resumed.isNullOrEmpty()) extra["resumed"] = resumed
                        }
                    }
                }
                else -> throw ApiException(400, "unsupported action: $action")
            }
        }
        return mapOf(
            "action" to action,
            "sessionId" to sessionId,
            "suspended" to (safe { session.isSuspended } ?: false),
        ) + extra
    }

    // A suspend=thread stop may leave the XDebugSession running, so also poll the Java suspend contexts.
    private fun waitPause(session: XDebugSession, timeoutMs: Long): Map<String, Any?> {
        val id = sessions.idOf(session)
        pausedNow(session, id)?.let { return it }
        val future = CompletableFuture<String>()
        sessions.addWaiter(session, future)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                pausedNow(session, id)?.let { return it }
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return mapOf("event" to "timeout", "sessionId" to id)
                val event = try {
                    future.get(minOf(left, 500L), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    continue
                }
                return when (event) {
                    "paused" -> pausedEvent(session, id)
                    "stopped" -> mapOf("event" to "stopped", "sessionId" to id)
                    else -> mapOf("event" to "timeout", "sessionId" to id)
                }
            }
        } finally {
            sessions.removeWaiter(session, future)
        }
    }

    private fun pausedNow(session: XDebugSession, id: String): Map<String, Any?>? {
        if (safe { session.isSuspended } == true) return pausedEvent(session, id)
        val threads = JavaDebug.suspendedThreads(session)
        if (threads.isNullOrEmpty()) return null
        return pausedEvent(session, id) + mapOf("threadLevel" to true, "suspendedThreads" to threads)
    }

    private fun snapshotOptions(json: JsonObject): SnapshotOptions = SnapshotOptions(
        frameIndex = json.int("frameIndex") ?: 0,
        depth = json.int("depth") ?: 2,
        maxChildren = json.int("maxChildren") ?: 50,
        maxString = json.int("maxString") ?: 1000,
        maxNodes = json.int("maxNodes") ?: 2000,
        maxFrames = json.int("maxFrames") ?: 200,
        timeoutMs = json.long("snapshotTimeout") ?: 10_000L,
        thread = json.str("thread"),
        toStringCalls = json.bool("toString"),
    ).clamp()

    // One or more (path, value-spec) pairs from a SET_VALUE body: either sets=[...] or a single path/value.
    private fun parseSets(json: JsonObject): List<Pair<String, Map<String, Any?>>> {
        val out = mutableListOf<Pair<String, Map<String, Any?>>>()
        if (json.has("sets")) {
            val arr = json.get("sets").takeIf { it.isJsonArray }?.asJsonArray
                ?: throw ApiException(400, "sets must be an array")
            for (el in arr) {
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: throw ApiException(400, "each set must be an object")
                val path = o.str("path") ?: throw ApiException(400, "each set needs a path")
                val value = o.get("value")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw ApiException(400, "each set needs a value object")
                out += path to specMap(value)
            }
        } else {
            val path = json.str("path") ?: throw ApiException(400, "path is required")
            val value = json.get("value")?.takeIf { it?.isJsonObject == true }?.asJsonObject
                ?: throw ApiException(400, "value is required, e.g. {\"int\": 5}")
            out += path to specMap(value)
        }
        if (out.isEmpty()) throw ApiException(400, "no values to set")
        return out
    }

    private fun specMap(value: JsonObject): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        for ((k, v) in value.entrySet()) {
            m[k] = when {
                v.isJsonNull -> null
                v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                else -> v.asString
            }
        }
        return m
    }

    private fun setDto(r: JavaDebug.SetResult): Map<String, Any?> {
        val base = r.path.substringAfterLast('.').substringBefore('[')
        val m = linkedMapOf<String, Any?>(
            "path" to r.path,
            "thread" to r.thread,
            "frameIndex" to r.frameIndex,
            "type" to r.type,
            "old" to mask(base, r.old),
            "new" to mask(base, r.new),
        )
        if (r.warning != null) m["warning"] = r.warning
        return m
    }

    // Everything the IDE debugger shows for one suspended thread, read from JDI mirrors. Read-only.
    private fun snapshot(session: XDebugSession, opts: SnapshotOptions, allowCalls: Boolean): Map<String, Any?> {
        if (opts.toStringCalls && !allowCalls) {
            throw ApiException(403, "toString runs code in the process. It requires X-ASdbg-Allow-Eval: 1")
        }
        val started = System.currentTimeMillis()
        val read = JavaDebug.snapshot(session, opts) { secretName.containsMatchIn(it) }
            ?: throw ApiException(409, "not a Java debug session")
        val (body, walker, thread) = read
        val project = session.project
        val cache = HashMap<String, String?>()
        @Suppress("UNCHECKED_CAST")
        val frames = body["frames"] as List<MutableMap<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val location = body["location"] as MutableMap<String, Any?>
        (frames + location).forEach { frame ->
            if (!frame.containsKey("file")) {
                val source = frame["sourcePath"] as? String
                frame["file"] = source?.let { cache.getOrPut(it) { resolveSource(project, it) } }
            }
        }
        val breakpoint = safe {
            ReadAction.compute<Map<String, Any?>?, RuntimeException> {
                val file = location["file"] as? String
                val line = location["line"] as? Int
                XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .firstOrNull { file != null && it.line + 1 == line && relative(project, it.fileUrl) == file }
                    ?.let { breakpointDto(project, it) }
            }
        }
        val toStringNote = if (opts.toStringCalls) PauseReader.addToStrings(thread, walker, opts.maxString) else null
        val out = linkedMapOf<String, Any?>("sessionId" to sessions.idOf(session))
        out.putAll(body)
        out["breakpoint"] = breakpoint
        out["nodes"] = walker.nodes
        out["truncated"] = walker.truncated || (body["framesTruncated"] == true)
        out["truncatedReason"] = walker.truncatedReason
        if (toStringNote != null) out["toStringNote"] = toStringNote
        out["limits"] = opts.dto()
        out["elapsedMs"] = System.currentTimeMillis() - started
        return out
    }

    /** Maps a JDI source path (package/File.kt) to a project file, relative to the project. */
    private fun resolveSource(project: Project, sourcePath: String): String? = safe {
        ReadAction.compute<String?, RuntimeException> {
            val byRoot = ProjectRootManager.getInstance(project).contentSourceRoots
                .firstNotNullOfOrNull { it.findFileByRelativePath(sourcePath) }
            val file = byRoot ?: run {
                val name = sourcePath.substringAfterLast('/')
                FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).singleOrNull()
            }
            file?.let { relative(project, it.url) }
        }
    }

    private fun runToLine(session: XDebugSession, json: JsonObject): Map<String, Any?> {
        val project = session.project
        val rel = json.str("file") ?: throw ApiException(400, "file is required")
        val line = json.int("line") ?: throw ApiException(400, "line is required")
        if (line < 1) throw ApiException(400, "line starts at 1")
        val abs = if (File(rel).isAbsolute) rel else File(project.basePath, rel).path
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(abs)
            ?: throw ApiException(404, "file not found: $abs")
        val position = com.intellij.xdebugger.XDebuggerUtil.getInstance().createPosition(file, line - 1)
            ?: throw ApiException(400, "no position at $abs:$line")
        val ignore = json.bool("ignoreBreakpoints")
        if (session.isSuspended && json.str("thread") == null) {
            edt { session.runToPosition(position, ignore) }
        } else {
            JavaDebug.runToLine(session, position, ignore, json.str("thread"))
                ?: throw ApiException(409, "not a Java debug session")
        }
        return mapOf("file" to relative(project, file.url), "line" to line, "ignoreBreakpoints" to ignore)
    }

    /** Same as clicking a frame (and a thread) in the Frames panel. vars, stack and eval then use it. */
    private fun selectFrame(session: XDebugSession, json: JsonObject): Map<String, Any?> {
        if (safe { session.isSuspended } != true) throw ApiException(409, "session is running")
        val context = session.suspendContext ?: throw ApiException(409, "no suspend context")
        val index = json.int("frameIndex") ?: 0
        val wanted = json.str("thread")
        val stack = if (wanted == null) {
            currentStack(session) ?: context.activeExecutionStack
        } else {
            executionStacks(context).firstOrNull { it.displayName == wanted || it.displayName.startsWith("\"$wanted\"") }
                ?: throw ApiException(404, "thread not found: $wanted")
        } ?: throw ApiException(409, "no execution stack")
        val frames = readFrames(stack)
        val frame = frames.getOrNull(index) ?: throw ApiException(404, "frame $index not found. frames=${frames.size}")
        edt { session.setCurrentStackFrame(stack, frame, index == 0) }
        return frameDto(session.project, index, frame) + mapOf("thread" to stack.displayName)
    }

    private fun currentStack(session: XDebugSession): XExecutionStack? = safe {
        session.javaClass.methods.firstOrNull { it.name == "getCurrentExecutionStack" && it.parameterCount == 0 }
            ?.invoke(session) as? XExecutionStack
    }

    private fun executionStacks(context: com.intellij.xdebugger.frame.XSuspendContext): List<XExecutionStack> {
        val future = CompletableFuture<List<XExecutionStack>>()
        val acc = mutableListOf<XExecutionStack>()
        context.computeExecutionStacks(object : com.intellij.xdebugger.frame.XSuspendContext.XExecutionStackContainer {
            override fun addExecutionStack(stacks: MutableList<out XExecutionStack>, last: Boolean) {
                acc += stacks
                if (last && !future.isDone) future.complete(acc.toList())
            }

            override fun errorOccurred(errorMessage: String) {
                if (!future.isDone) future.complete(acc.toList())
            }

            override fun isObsolete(): Boolean = false
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun updateBreakpoint(project: Project, rawId: String, json: JsonObject): Map<String, Any?> = edtWrite {
        val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
        val bp = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .firstOrNull { breakpointId(it) == id }
            ?: throw ApiException(404, "breakpoint not found")
        if (json.has("enabled")) bp.isEnabled = json.bool("enabled")
        if (json.has("condition")) bp.setCondition(json.str("condition")?.takeIf { it.isNotBlank() })
        if (json.has("logMessage")) bp.isLogMessage = json.bool("logMessage")
        if (json.has("logStack")) bp.isLogStack = json.bool("logStack")
        if (json.has("logExpression")) bp.setLogExpression(json.str("logExpression")?.takeIf { it.isNotBlank() })
        if (json.has("suspend")) applySuspend(bp, json)
        if (json.has("passCount")) {
            val count = json.int("passCount") ?: 0
            val props = bp.properties ?: throw ApiException(400, "pass count is not supported for ${kindOf(bp.type)}")
            val enable = props.javaClass.methods.firstOrNull { it.name == "setCOUNT_FILTER_ENABLED" }
            val set = props.javaClass.methods.firstOrNull { it.name == "setCOUNT_FILTER" }
            if (enable == null || set == null) throw ApiException(400, "pass count is not supported for ${kindOf(bp.type)}")
            enable.invoke(props, count > 0)
            if (count > 0) set.invoke(props, count)
        }
        breakpointDto(project, bp) + mapOf("updated" to true)
    }

    private fun stack(project: Project, id: String): List<Map<String, Any?>> {
        val session = requireSession(project, id)
        if (safe { session.isSuspended } != true) throw ApiException(409, "session is running")
        val active = currentStack(session) ?: session.suspendContext?.activeExecutionStack
        val frames = if (active == null) {
            listOfNotNull(session.currentStackFrame)
        } else {
            readFrames(active)
        }
        return frames.mapIndexed { index, frame -> frameDto(project, index, frame) }
    }

    private fun readFrames(stack: XExecutionStack): List<XStackFrame> {
        val future = CompletableFuture<List<XStackFrame>>()
        val acc = mutableListOf<XStackFrame>()
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(frames: MutableList<out XStackFrame>, last: Boolean) {
                acc += frames
                if (last) future.complete(acc.toList())
            }

            override fun errorOccurred(errorMessage: String) {
                if (!future.isDone) future.complete(acc.toList())
            }

            override fun isObsolete(): Boolean = false
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    // ponytail: current frame, one level. Walk children when a field value is the question.
    private fun variables(project: Project, id: String): List<Map<String, Any?>> {
        val session = requireSession(project, id)
        if (safe { session.isSuspended } != true) throw ApiException(409, "session is running")
        val frame = session.currentStackFrame ?: return emptyList()
        return readChildren(frame).map { (name, value) ->
            val shown = present(value)
            mapOf(
                "name" to name,
                "type" to shown.first,
                "value" to mask(name, shown.second),
            )
        }
    }

    private fun evaluate(project: Project, id: String, json: JsonObject): Map<String, Any?> {
        val expression = json.str("expression") ?: throw ApiException(400, "expression is required")
        val session = requireSession(project, id)
        if (safe { session.isSuspended } != true) throw ApiException(409, "session is running")
        val frame = session.currentStackFrame ?: throw ApiException(409, "no stack frame")
        val evaluator = frame.evaluator ?: throw ApiException(409, "no evaluator")
        val future = CompletableFuture<XValue>()
        evaluator.evaluate(
            expression,
            object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    future.complete(result)
                }

                override fun errorOccurred(errorMessage: String) {
                    future.completeExceptionally(ApiException(400, errorMessage))
                }
            },
            frame.sourcePosition,
        )
        val value = future.get(5, TimeUnit.SECONDS)
        val shown = present(value)
        return mapOf(
            "type" to shown.first,
            "value" to mask("expression", shown.second),
            "warning" to "evaluate ran code in the app. it can change state, and it can hang if it needs a lock held " +
                "by a suspended thread. after a suspend=thread stop other threads keep running",
        )
    }

    private fun readChildren(frame: XStackFrame): List<Pair<String, XValue>> {
        val future = CompletableFuture<List<Pair<String, XValue>>>()
        val acc = mutableListOf<Pair<String, XValue>>()
        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) {
                    acc += children.getName(i) to children.getValue(i)
                }
                if (last && !future.isDone) future.complete(acc.toList())
            }

            override fun tooManyChildren(remaining: Int) {
                if (!future.isDone) future.complete(acc.toList())
            }

            override fun setAlreadySorted(alreadySorted: Boolean) {}

            override fun setErrorMessage(errorMessage: String) {
                if (!future.isDone) future.completeExceptionally(ApiException(500, errorMessage))
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                setErrorMessage(errorMessage)
            }

            override fun setMessage(
                message: String,
                icon: Icon?,
                attributes: SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?,
            ) {
            }

            override fun isObsolete(): Boolean = false
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun present(value: XValue): Pair<String?, String?> {
        val future = CompletableFuture<Pair<String?, String?>>()
        value.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                if (!future.isDone) future.complete(type to value)
            }

            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                if (future.isDone) return
                val renderer = TextRenderer()
                presentation.renderValue(renderer)
                future.complete(presentation.type to renderer.text)
            }

            override fun setFullValueEvaluator(fullValueEvaluator: com.intellij.xdebugger.frame.XFullValueEvaluator) {}

            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
        return try {
            future.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null to "<unavailable>"
        }
    }

    private fun requireSession(project: Project, id: String): XDebugSession {
        XDebuggerManager.getInstance(project).debugSessions.forEach { watch(it) }
        return sessions.get(id) ?: throw ApiException(404, "session not found")
    }

    private fun sessionDto(session: XDebugSession): Map<String, Any?> {
        val threads = JavaDebug.suspendedThreads(session)
        return mapOf(
            "id" to sessions.idOf(session),
            "name" to session.sessionName,
            "suspended" to (safe { session.isSuspended } ?: false),
            "threadSuspended" to threads?.isNotEmpty(),
            "suspendedThreads" to threads,
            "muted" to safe { session.areBreakpointsMuted() },
            "projectIndexing" to safe { DumbService.isDumb(session.project) },
            "position" to position(session),
        )
    }

    // ---- pause hooks (webhook / events file / onPauseCommand). Location only, never values.

    private fun emitJdiPause(session: XDebugSession, raw: Map<String, Any?>) = safe<Unit> {
        val id = sessions.idOf(session)
        val project = session.project
        val line = raw["line"] as? Int
        val thread = raw["thread"] as? String
        dedup.markJdi(id, line, System.currentTimeMillis(), thread)
        val file = (raw["sourcePath"] as? String)?.let { resolveSource(project, it) } ?: raw["sourcePath"] as? String
        val bp = if (raw["kind"] == "breakpoint") lineBreakpoint(project, file, line) else null
        val bpId = bp?.first
        val kind = when {
            raw["kind"] == "breakpoint" && bpId == null && recentAction(id) == "RUN_TO_LINE" -> "run_to_line"
            raw["kind"] != null -> raw["kind"] as String
            recentAction(id) == "PAUSE" -> "pause"
            else -> "other"
        }
        val p = PauseMapping.policy(kind, raw["policy"] as? String, bp?.second)
        val (cls, method) = PauseMapping.classAndMethod(raw["class"] as? String, raw["method"] as? String, null)
        hooks.emit(
            PauseEvent(
                kind = kind,
                source = "jdi",
                sessionId = id,
                sessionName = safe { session.sessionName },
                project = project.name,
                file = file,
                line = line,
                className = cls,
                method = method,
                thread = thread,
                threadLevel = p.threadLevel,
                policy = p.policy,
                contextPolicy = p.contextPolicy,
                breakpointPolicy = p.breakpointPolicy,
                breakpointId = bpId,
            ),
        )
    }

    // Fallback for stops the JDI listener did not report (or non-Java sessions). Waits briefly so the JDI event wins.
    private fun emitSessionPause(session: XDebugSession, pos: Map<String, Any?>?, stack: String?, frameText: String?) = safe<Unit> {
        Thread.sleep(300)
        val id = sessions.idOf(session)
        val line = pos?.get("line") as? Int
        val threadName = stack?.let { Regex("^\"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: it.substringBefore(' ') }
        if (dedup.seenByJdi(id, line, System.currentTimeMillis(), threadName)) return@safe
        val file = pos?.get("file") as? String
        val bp = lineBreakpoint(session.project, file, line)
        val bpId = bp?.first
        val kind = when (recentAction(id)) {
            "PAUSE" -> "pause"
            "STEP_OVER", "STEP_INTO", "STEP_OUT" -> "step"
            "RUN_TO_LINE" -> "run_to_line"
            else -> if (bpId != null) "breakpoint" else "other"
        }
        // Same JDI suspend contexts that `sessions` reads for threadSuspended, never a hard-coded policy.
        val facts = JavaDebug.stopFacts(session, threadName, line)
        val p = PauseMapping.policy(kind, facts?.get("policy") as? String, bp?.second)
        val (cls, method) = PauseMapping.classAndMethod(facts?.get("class") as? String, facts?.get("method") as? String, frameText)
        hooks.emit(
            PauseEvent(
                kind = kind,
                source = "session",
                sessionId = id,
                sessionName = safe { session.sessionName },
                project = session.project.name,
                file = file,
                line = line,
                className = cls,
                method = method,
                thread = threadName ?: facts?.get("thread") as? String,
                threadLevel = p.threadLevel,
                policy = p.policy,
                contextPolicy = p.contextPolicy,
                breakpointPolicy = p.breakpointPolicy,
                breakpointId = bpId,
            ),
        )
    }

    private fun recentAction(id: String): String? =
        lastAction[id]?.takeIf { System.currentTimeMillis() - it.second < 60_000 }?.first

    /** id and configured suspend policy ("all"/"thread"/"none") of the enabled line breakpoint at file:line. */
    private fun lineBreakpoint(project: Project, file: String?, line: Int?): Pair<String, String?>? {
        if (file == null || line == null) return null
        return safe {
            ReadAction.compute<Pair<String, String?>?, RuntimeException> {
                val matches = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .filter { it.line + 1 == line && relative(project, it.fileUrl) == file }
                val bp = matches.firstOrNull { it.isEnabled } ?: matches.firstOrNull()
                bp?.let { breakpointId(it) to PauseMapping.breakpointPolicy(it.suspendPolicy.name) }
            }
        }
    }

    private fun pausedEvent(session: XDebugSession, id: String): Map<String, Any?> = mapOf(
        "event" to "paused",
        "sessionId" to id,
        "position" to position(session),
    )

    private fun position(session: XDebugSession): Map<String, Any?>? = safe {
        val pos = session.currentPosition ?: session.topFramePosition ?: return@safe null
        mapOf(
            "file" to relative(session.project, pos.file.url),
            "line" to pos.line + 1,
        )
    }

    private fun frameDto(project: Project, index: Int, frame: XStackFrame): Map<String, Any?> {
        val pos = frame.sourcePosition
        val text = safe {
            val colored = SimpleColoredText()
            frame.customizeTextPresentation(colored)
            colored.toString()
        }.orEmpty()
        return mapOf(
            "index" to index,
            "file" to pos?.file?.let { relative(project, it.url) },
            "line" to pos?.let { it.line + 1 },
            "text" to text,
        )
    }

    private fun breakpointDto(project: Project, bp: XBreakpoint<*>): Map<String, Any?> {
        val id = breakpointId(bp)
        val lineBp = bp as? XLineBreakpoint<*>
        val dto = mutableMapOf<String, Any?>(
            "id" to id,
            "type" to kindOf(bp.type),
            "file" to lineBp?.let { relative(project, it.fileUrl) },
            "line" to lineBp?.let { it.line + 1 },
            "condition" to bp.conditionExpression?.expression,
            "temporary" to (lineBp?.isTemporary ?: false),
            "suspend" to bp.suspendPolicy.name.lowercase(),
            "enabled" to bp.isEnabled,
            "owned" to owned(id),
            "logMessage" to bp.isLogMessage,
            "logStack" to bp.isLogStack,
            "logExpression" to bp.logExpressionObject?.expression,
            "passCount" to passCount(bp),
        )
        if (kindOf(bp.type) == "exception") dto["className"] = exceptionName(bp).ifBlank { "*" }
        dto.putAll(verification(project, bp))
        return dto
    }

    private fun passCount(bp: XBreakpoint<*>): Int? = safe {
        val props = bp.properties ?: return@safe null
        val enabled = props.javaClass.methods.firstOrNull { it.name == "isCOUNT_FILTER_ENABLED" }?.invoke(props) as? Boolean
        if (enabled != true) return@safe 0
        props.javaClass.methods.firstOrNull { it.name == "getCOUNT_FILTER" }?.invoke(props) as? Int
    }

    private val verifiedIcons: Set<javax.swing.Icon> by lazy { debuggerIcons { "verified" in it } }
    private val invalidIcons: Set<javax.swing.Icon> by lazy { debuggerIcons { "invalid" in it } }

    private fun debuggerIcons(match: (String) -> Boolean): Set<javax.swing.Icon> =
        AllIcons.Debugger::class.java.fields
            .filter { match(it.name.lowercase()) && javax.swing.Icon::class.java.isAssignableFrom(it.type) }
            .mapNotNull { safe { it.get(null) as? javax.swing.Icon } }
            .toSet()

    /**
     * Whether the debugger registered the breakpoint in the VM, read from the gutter icon the session set.
     * verified: requests exist in the VM. invalid: the debugger rejected it (see verifyError).
     * pending: a session runs but has not set an icon yet, usually because the class is not loaded yet.
     * unknown: no running session, or the state could not be read.
     */
    private fun verification(project: Project, bp: XBreakpoint<*>): Map<String, Any?> {
        val running = XDebuggerManager.getInstance(project).debugSessions.filter { !it.isStopped }
        if (running.isEmpty()) return mapOf("verified" to "unknown")
        if (bp !is XLineBreakpoint<*>) return mapOf("verified" to "unknown")
        var pending = false
        for (session in running) {
            val presentation = safe {
                session.javaClass.methods.firstOrNull { it.name == "getBreakpointPresentation" && it.parameterCount == 1 }
                    ?.invoke(session, bp)
            }
            if (presentation == null) {
                pending = true
                continue
            }
            val icon = call(presentation, "getIcon") as? javax.swing.Icon
            val error = call(presentation, "getErrorMessage") as? String
            val name = icon?.toString()?.lowercase().orEmpty()
            val state = when {
                icon in invalidIcons || "invalid" in name -> "invalid"
                icon in verifiedIcons || "verified" in name || "valid" in name -> "verified"
                !error.isNullOrBlank() -> "invalid"
                else -> "unknown"
            }
            return mapOf("verified" to state, "verifyError" to error, "verifySession" to sessions.idOf(session))
        }
        return mapOf("verified" to if (pending) "pending" else "unknown")
    }

    private fun call(target: Any, name: String): Any? = safe {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
    }

    private fun breakpointId(bp: XBreakpoint<*>): String {
        val lineBp = bp as? XLineBreakpoint<*>
        val raw = if (lineBp != null) {
            "${bp.type.id}\n${lineBp.fileUrl}\n${lineBp.line}"
        } else {
            "${bp.type.id}\n${exceptionName(bp)}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun relative(project: Project, url: String): String {
        val path = VirtualFileManager.getInstance().findFileByUrl(url)?.path ?: url
        val base = project.basePath ?: return path
        return if (path.startsWith(base)) path.removePrefix(base).trimStart('/') else path
    }

    private fun owned(id: String): Boolean {
        if (!Files.exists(ownedFile)) return false
        return Files.readAllLines(ownedFile).any { it.trim() == id }
    }

    private fun markOwned(id: String) {
        synchronized(ownedFile) {
            if (!owned(id)) {
                Files.writeString(ownedFile, id + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    private fun unmarkOwned(id: String) {
        synchronized(ownedFile) {
            if (!Files.exists(ownedFile)) return@synchronized
            val left = Files.readAllLines(ownedFile).filter { it.trim() != id && it.isNotBlank() }
            Files.writeString(ownedFile, left.joinToString("\n", postfix = if (left.isEmpty()) "" else "\n"))
        }
    }

    private val secretName = Regex("(?i)(pass(word)?|secret|token|api[-_]?key|credential)")

    private fun mask(name: String, value: String?): String? {
        if (value == null) return null
        if (secretName.containsMatchIn(name)) return "***"
        return value
    }

    private fun body(exchange: HttpExchange): JsonObject {
        val text = exchange.requestBody.use { String(it.readNBytes(1_000_000), StandardCharsets.UTF_8) }
        if (text.isBlank()) return JsonObject()
        val parsed = JsonParser.parseString(text)
        if (!parsed.isJsonObject) throw ApiException(400, "json object required")
        return parsed.asJsonObject
    }

    private fun JsonObject.str(name: String): String? {
        if (!has(name) || get(name).isJsonNull) return null
        val value = get(name).asString
        return value.ifBlank { null }
    }

    private fun JsonObject.int(name: String): Int? {
        if (!has(name) || get(name).isJsonNull) return null
        return get(name).asInt
    }

    private fun JsonObject.long(name: String): Long? {
        if (!has(name) || get(name).isJsonNull) return null
        return get(name).asLong
    }

    private fun JsonObject.bool(name: String): Boolean {
        if (!has(name) || get(name).isJsonNull) return false
        return get(name).asBoolean
    }

    private fun query(exchange: HttpExchange, name: String): String? {
        val raw = exchange.requestURI.rawQuery ?: return null
        return raw.split("&").firstNotNullOfOrNull { part ->
            val bits = part.split("=", limit = 2)
            if (bits[0] == name) URLDecoder.decode(bits.getOrElse(1) { "" }, StandardCharsets.UTF_8) else null
        }
    }

    private fun send(exchange: HttpExchange, status: Int, payload: Any) {
        val bytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun <T> edtRead(block: () -> T): T = edt {
        ReadAction.compute<T, RuntimeException> { block() }
    }

    private fun <T> edtWrite(block: () -> T): T = edt {
        WriteAction.compute<T, RuntimeException> { block() }
    }

    private fun <T> edt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var result: T? = null
        var error: Throwable? = null
        app.invokeAndWait({
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }, ModalityState.defaultModalityState())
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}

private class TextRenderer : XValuePresentation.XValueTextRenderer {
    private val buffer = StringBuilder()
    val text: String get() = buffer.toString()

    override fun renderValue(value: String) {
        buffer.append(value)
    }

    override fun renderStringValue(value: String) {
        buffer.append(value)
    }

    override fun renderNumericValue(value: String) {
        buffer.append(value)
    }

    override fun renderKeywordValue(value: String) {
        buffer.append(value)
    }

    override fun renderValue(value: String, key: TextAttributesKey) {
        buffer.append(value)
    }

    override fun renderStringValue(value: String, quote: String?, length: Int) {
        buffer.append(value)
    }

    override fun renderComment(comment: String) {
        buffer.append(comment)
    }

    override fun renderSpecialSymbol(symbol: String) {
        buffer.append(symbol)
    }

    override fun renderError(error: String) {
        buffer.append(error)
    }
}

// The Java debugger engine sits in a content module this plugin does not link against,
// so it is reached by reflection on the live objects. Every call runs on the debugger manager thread.
private object JavaDebug {
    private const val TIMEOUT_MS = 3_000L

    /**
     * Registers a DebugProcessListener (through a reflective proxy, like the rest of this object) whose paused()
     * fires for every suspend context, including suspend=thread stops the XDebugSession does not report.
     * [onPaused] runs on the debugger manager thread with location-only data and must return fast.
     */
    fun addPauseListener(session: XDebugSession, onPaused: (Map<String, Any?>) -> Unit): Boolean = try {
        val process = process(session)
        if (process == null) {
            false
        } else {
            val iface = Class.forName("com.intellij.debugger.engine.DebugProcessListener", false, process.javaClass.classLoader)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, m, args ->
                when (m.name) {
                    "paused" -> {
                        try {
                            args?.firstOrNull()?.let { onPaused(pauseInfo(it)) }
                        } catch (_: Throwable) {
                        }
                        null
                    }
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "asdbg-pause-listener"
                    else -> null
                }
            }
            val add = process.javaClass.methods.first { it.name == "addDebugProcessListener" && it.parameterCount == 1 }
            add.invoke(process, proxy)
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun pauseInfo(context: Any): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        out.putAll(info(context))
        val eventSet = runCatching { call(context, "getEventSet") as? com.sun.jdi.event.EventSet }.getOrNull()
        out["kind"] = eventSet?.let { kindOf(it) }
        val located = eventSet?.firstOrNull { it is com.sun.jdi.event.LocatableEvent } as? com.sun.jdi.event.LocatableEvent
        val location = runCatching { located?.location() }.getOrNull() ?: frameLocation(context)
        out.putAll(locationFacts(location))
        return out
    }

    private fun frameLocation(context: Any): com.sun.jdi.Location? = runCatching {
        val proxy = call(context, "getEventThread") ?: call(context, "getThread")
        val thread = proxy?.let { call(it, "getThreadReference") } as? com.sun.jdi.ThreadReference
        thread?.takeIf { it.isSuspended && it.frameCount() > 0 }?.frame(0)?.location()
    }.getOrNull()

    private fun locationFacts(location: com.sun.jdi.Location?): Map<String, Any?> {
        if (location == null) return emptyMap()
        return mapOf(
            "class" to runCatching { location.declaringType().name() }.getOrNull(),
            "method" to runCatching { location.method().name() }.getOrNull(),
            "line" to location.lineNumber().takeIf { it > 0 },
            "sourcePath" to runCatching { location.sourcePath() }.getOrNull(),
        )
    }

    /**
     * Policy, thread and top-frame location of the suspend context that matches [threadName] (else [line], else the first).
     * Reads the same contexts as [suspendedThreads], which backs `sessions` threadSuspended. null when not Java or nothing is suspended.
     */
    fun stopFacts(session: XDebugSession, threadName: String?, line: Int?): Map<String, Any?>? = try {
        val process = process(session) ?: return null
        onManager(process) {
            val located = contexts(process).map { c -> Triple(c, info(c), frameLocation(c)) }
            val pick = located.firstOrNull { threadName != null && it.second["thread"] == threadName }
                ?: located.firstOrNull { line != null && it.third?.lineNumber() == line }
                ?: located.firstOrNull()
            pick?.let { (_, info, loc) -> info + locationFacts(loc) }
        }
    } catch (_: Exception) {
        null
    }

    private fun kindOf(set: com.sun.jdi.event.EventSet): String? {
        for (e in set) {
            when (e) {
                is com.sun.jdi.event.BreakpointEvent -> return "breakpoint"
                is com.sun.jdi.event.StepEvent -> return "step"
                is com.sun.jdi.event.ExceptionEvent -> return "exception"
                is com.sun.jdi.event.MethodEntryEvent, is com.sun.jdi.event.MethodExitEvent -> return "method"
                is com.sun.jdi.event.WatchpointEvent -> return "field"
            }
        }
        return null
    }

    /** Suspended contexts that the XDebugSession may not report, e.g. suspend=thread stops. null when unknown. */
    fun suspendedThreads(session: XDebugSession): List<Map<String, Any?>>? = try {
        val process = process(session) ?: return null
        onManager(process) { contexts(process).map { info(it) } }
    } catch (_: Exception) {
        null
    }

    /** Resumes every suspend context the same way the IDE Resume does. Returns what was resumed, null when not Java. */
    fun resumeThreads(session: XDebugSession): List<Map<String, Any?>>? {
        val process = process(session) ?: return null
        return onManager(process) {
            val manager = call(process, "getManagerThread")!!
            contexts(process).map { context ->
                val info = info(context)
                call(manager, "schedule", call(process, "createResumeCommand", context))
                info
            }
        }
    }

    /**
     * Pause snapshot of one suspended thread: the event thread of a suspend context, or the named thread.
     * JDI mirrors only, on the debugger manager thread. null when the session is not a Java session.
     */
    fun snapshot(
        session: XDebugSession,
        opts: SnapshotOptions,
        isSecret: (String) -> Boolean,
    ): Triple<MutableMap<String, Any?>, MirrorWalker, com.sun.jdi.ThreadReference>? {
        val process = process(session) ?: return null
        return onManager(process, opts.timeoutMs + 2_000L) {
            val ctxs = contexts(process)
            val infos = ctxs.map { info(it) }
            val withThread = ctxs.mapNotNull { c ->
                (call(c, "getEventThread") ?: call(c, "getThread"))?.let { c to it }
            }
            var policy = infos.firstOrNull()?.get("policy") as? String ?: "unknown"
            var thread: com.sun.jdi.ThreadReference? = null
            val wanted = opts.thread
            val match = withThread.firstOrNull { (_, proxy) -> wanted == null || call(proxy, "name") == wanted }
            if (match != null) {
                thread = call(match.second, "getThreadReference") as com.sun.jdi.ThreadReference
                policy = info(match.first)["policy"] as? String ?: policy
            } else {
                val vm = call(call(process, "getVirtualMachineProxy")!!, "getVirtualMachine") as com.sun.jdi.VirtualMachine
                thread = vm.allThreads().firstOrNull { it.name() == (wanted ?: "main") && it.isSuspended }
            }
            if (thread == null) throw ApiException(409, "no suspended thread${wanted?.let { " named $it" } ?: ""}")
            if (!thread.isSuspended) throw ApiException(409, "thread ${thread.name()} is running")
            val (body, walker) = PauseReader.read(thread, opts, isSecret)
            @Suppress("UNCHECKED_CAST")
            (body["thread"] as MutableMap<String, Any?>).apply {
                put("policy", policy)
                put("threadLevel", policy == "thread")
            }
            body["suspendedThreads"] = infos
            Triple(body, walker, thread)
        }
    }

    /** Every thread in the VM with its JDI status. frames > 0 adds that many top frames for suspended threads. */
    fun threads(session: XDebugSession, frames: Int): List<Map<String, Any?>>? {
        val process = process(session) ?: return null
        return onManager(process, 10_000L) {
            val ctxs = contexts(process)
            val eventThreads = ctxs.mapNotNull { (call(it, "getEventThread") ?: call(it, "getThread"))?.let { t -> call(t, "name") } }.toSet()
            val vm = call(call(process, "getVirtualMachineProxy")!!, "getVirtualMachine") as com.sun.jdi.VirtualMachine
            vm.allThreads().map { thread ->
                val suspended = thread.isSuspended
                val out = linkedMapOf<String, Any?>(
                    "name" to thread.name(),
                    "status" to MirrorWalker.threadStatus(thread),
                    "suspended" to suspended,
                    "suspendCount" to thread.suspendCount(),
                    "group" to runCatching { thread.threadGroup()?.name() }.getOrNull(),
                    "stoppedHere" to (thread.name() in eventThreads),
                )
                if (suspended) {
                    runCatching { thread.frameCount() }.getOrNull()?.let { out["frameCount"] = it }
                    if (frames > 0) {
                        val walker = MirrorWalker(SnapshotOptions(), { false })
                        out["frames"] = runCatching {
                            val count = thread.frameCount()
                            thread.frames(0, minOf(frames, count)).mapIndexed { i, f -> walker.frame(i, f) }
                        }.getOrElse { listOf(mapOf("error" to (it.message ?: it.javaClass.simpleName))) }
                    }
                }
                out
            }
        }
    }

    private fun proxyByName(process: Any, name: String): Any {
        val vmProxy = call(process, "getVirtualMachineProxy")!!
        return (call(vmProxy, "allThreads") as Collection<*>).filterNotNull()
            .firstOrNull { call(it, "name") == name }
            ?: throw ApiException(404, "thread not found: $name")
    }

    /** The suspend context that holds this thread, and the thread proxy. */
    private fun contextFor(process: Any, name: String?): Pair<Any, Any> {
        val ctxs = contexts(process)
        if (name == null) {
            val ctx = ctxs.firstOrNull { (call(it, "getEventThread") ?: call(it, "getThread")) != null }
                ?: throw ApiException(409, "no suspended thread")
            return ctx to (call(ctx, "getEventThread") ?: call(ctx, "getThread"))!!
        }
        val proxy = proxyByName(process, name)
        val own = ctxs.firstOrNull { (call(it, "getEventThread") ?: call(it, "getThread")) == proxy }
        if (own != null) return own to proxy
        val holder = ctxs.firstOrNull { safe { call(it, "suspends", proxy) } == true }
            ?: throw ApiException(409, "thread $name is not suspended by the debugger")
        return holder to proxy
    }

    fun step(session: XDebugSession, action: String, thread: String?): String? {
        val process = process(session) ?: return null
        return onManager(process) {
            val (ctx, proxy) = contextFor(process, thread)
            if ((call(ctx, "getThread")) != proxy) call(ctx, "resetThread", proxy)
            val command = when (action) {
                "STEP_OVER" -> call(process, "createStepOverCommand", ctx, false)
                "STEP_INTO" -> call(process, "createStepIntoCommand", ctx, false, null)
                else -> call(process, "createStepOutCommand", ctx)
            }
            call(call(process, "getManagerThread")!!, "schedule", command)
            call(proxy, "name") as String
        }
    }

    fun runToLine(session: XDebugSession, position: com.intellij.xdebugger.XSourcePosition, ignore: Boolean, thread: String?): String? {
        val process = process(session) ?: return null
        return onManager(process) {
            val (ctx, proxy) = contextFor(process, thread)
            if ((call(ctx, "getThread")) != proxy) call(ctx, "resetThread", proxy)
            val command = call(process, "createRunToCursorCommand", ctx, position, ignore)
            call(call(process, "getManagerThread")!!, "schedule", command)
            call(proxy, "name") as String
        }
    }

    /** Suspends one thread, like Suspend in the Threads view. */
    fun freezeThread(session: XDebugSession, name: String): String? {
        val process = process(session) ?: return null
        return onManager(process) {
            val proxy = proxyByName(process, name)
            call(call(process, "getManagerThread")!!, "schedule", call(process, "createFreezeThreadCommand", proxy))
            name
        }
    }

    /** Resumes one thread. When the thread owns its suspend context, the context is resumed like Resume. */
    fun resumeThread(session: XDebugSession, name: String): String? {
        val process = process(session) ?: return null
        return onManager(process) {
            val (ctx, proxy) = contextFor(process, name)
            val owns = (call(ctx, "getEventThread") ?: call(ctx, "getThread")) == proxy &&
                info(ctx)["policy"] == "thread"
            val command = if (owns) {
                call(process, "createResumeCommand", ctx)
            } else {
                call(process, "createResumeThreadCommand", ctx, proxy)
            }
            call(call(process, "getManagerThread")!!, "schedule", command)
            name
        }
    }

    private fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    /** Last resort: resumes the whole VM. The IDE may still show the old suspend state. */
    fun resumeVm(session: XDebugSession): Boolean {
        val process = process(session) ?: return false
        onManager(process) { call(call(process, "getVirtualMachineProxy")!!, "resume") }
        return true
    }

    // Where one path segment lives, so we can read the slot and, at the last segment, write it.
    private class Slot(
        val read: () -> com.sun.jdi.Value?,
        val write: (com.sun.jdi.Value?) -> Unit,
        val declaredType: String?,
        val finalField: Boolean = false,
    )

    class SetResult(
        val path: String,
        val thread: String,
        val frameIndex: Int,
        val type: String?,
        val old: String?,
        val new: String?,
        val warning: String?,
    )

    /**
     * Writes values into a suspended frame with JDI. Primitives, String and null need no method call, so it is safe in a
     * suspend=thread stop. Returns null when the session is not a Java session. Each set resolves its own fresh frame.
     */
    fun setValues(
        session: XDebugSession,
        wanted: String?,
        frameIndex: Int,
        sets: List<Pair<String, Map<String, Any?>>>,
    ): List<SetResult>? {
        val process = process(session) ?: return null
        return onManager(process, 15_000L) {
            val thread = suspendedThreadRef(process, wanted)
            if (!thread.isSuspended) throw ApiException(409, "thread ${thread.name()} is running")
            val count = thread.frameCount()
            if (frameIndex < 0 || frameIndex >= count) {
                throw ApiException(409, "frame $frameIndex not available (frames=$count)")
            }
            val vm = thread.virtualMachine()
            sets.map { (path, spec) ->
                // Re-fetch the frame for each set: a previous setValue can invalidate the old StackFrame mirror.
                val frame = thread.frame(frameIndex)
                applySet(vm, frame, thread.name(), frameIndex, path, spec)
            }
        }
    }

    private fun applySet(
        vm: com.sun.jdi.VirtualMachine,
        frame: com.sun.jdi.StackFrame,
        threadName: String,
        frameIndex: Int,
        path: String,
        spec: Map<String, Any?>,
    ): SetResult {
        val tokens = try {
            SetValue.parsePath(path)
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, e.message ?: "bad path: $path")
        }
        val valueSpec = try {
            SetValue.parseSpec(spec)
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, e.message ?: "bad value")
        }
        val (rootSlot, consumed) = rootSlot(vm, frame, tokens)
        var slot = rootSlot
        for (i in consumed until tokens.size) {
            val parent = slot.read()
            slot = childSlot(parent, tokens[i])
        }
        val oldValue = try {
            slot.read()
        } catch (e: Exception) {
            throw ApiException(409, "could not read ${path}: ${e.message ?: e.javaClass.simpleName}")
        }
        val newValue = toMirror(vm, valueSpec, slot.declaredType)
        try {
            slot.write(newValue)
        } catch (e: com.sun.jdi.InvalidTypeException) {
            throw ApiException(400, "type mismatch for $path (${slot.declaredType}): ${e.message}")
        } catch (e: ClassCastException) {
            throw ApiException(400, "type mismatch for $path (${slot.declaredType}): ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, "cannot set $path: ${e.message}")
        }
        val after = try {
            slot.read()
        } catch (_: Exception) {
            newValue
        }
        val warning = if (slot.finalField) {
            "the field is final. JDI may set it, but code that cached the value will not see the change"
        } else {
            null
        }
        return SetResult(path, threadName, frameIndex, slot.declaredType, render(oldValue), render(after), warning)
    }

    private fun rootSlot(
        vm: com.sun.jdi.VirtualMachine,
        frame: com.sun.jdi.StackFrame,
        tokens: List<PathToken>,
    ): Pair<Slot, Int> {
        val name = (tokens.first() as PathToken.Name).name
        if (name == "this") {
            val self = safe { frame.thisObject() }
                ?: throw ApiException(409, "no this in this frame (a static method?)")
            if (tokens.size == 1) throw ApiException(400, "cannot assign to this")
            return Slot({ self }, { throw ApiException(400, "cannot assign to this") }, self.referenceType().name()) to 1
        }
        val local = safe { frame.visibleVariables() }?.firstOrNull { it.name() == name }
        if (local != null) {
            return Slot(
                { frame.getValue(local) },
                { frame.setValue(local, it) },
                local.typeName(),
            ) to 1
        }
        val self = safe { frame.thisObject() }
        if (self != null) {
            val field = self.referenceType().fieldByName(name)
            if (field != null) {
                return if (field.isStatic) {
                    staticSlot(self.referenceType(), field) to 1
                } else {
                    Slot({ self.getValue(field) }, { self.setValue(field, it) }, field.typeName(), field.isFinal) to 1
                }
            }
        }
        // A fully-qualified static field, e.g. com.example.Foo.FIELD. Greedily match the longest class name.
        val names = tokens.takeWhile { it is PathToken.Name }.map { (it as PathToken.Name).name }
        for (len in (names.size - 1) downTo 1) {
            val className = names.subList(0, len).joinToString(".")
            val type = vm.classesByName(className).firstOrNull() ?: continue
            val field = type.fieldByName(names[len])
                ?: throw ApiException(404, "no field ${names[len]} on $className")
            if (!field.isStatic) throw ApiException(400, "${names[len]} on $className is not static")
            return staticSlot(type, field) to (len + 1)
        }
        throw ApiException(404, "no local, field of this, or static named '$name' in this frame")
    }

    private fun staticSlot(type: com.sun.jdi.ReferenceType, field: com.sun.jdi.Field): Slot {
        val classType = type as? com.sun.jdi.ClassType
        return Slot(
            { type.getValue(field) },
            {
                (classType ?: throw ApiException(400, "cannot set static on ${type.name()}")).setValue(field, it)
            },
            field.typeName(),
            field.isFinal,
        )
    }

    private fun childSlot(parent: com.sun.jdi.Value?, token: PathToken): Slot = when (token) {
        is PathToken.Index -> {
            val arr = parent as? com.sun.jdi.ArrayReference
                ?: throw ApiException(400, "cannot index into ${typeName(parent)}; not an array")
            val idx = token.index
            if (idx >= arr.length()) throw ApiException(400, "index $idx out of bounds (length ${arr.length()})")
            val component = (arr.referenceType() as? com.sun.jdi.ArrayType)?.componentTypeName()
            Slot({ arr.getValue(idx) }, { arr.setValue(idx, it) }, component)
        }
        is PathToken.Name -> {
            val obj = parent as? com.sun.jdi.ObjectReference
                ?: throw ApiException(400, "cannot read field '${token.name}' of ${typeName(parent)}; not an object")
            val field = obj.referenceType().fieldByName(token.name)
                ?: throw ApiException(404, "no field '${token.name}' on ${obj.referenceType().name()}")
            Slot({ obj.getValue(field) }, { obj.setValue(field, it) }, field.typeName(), field.isFinal)
        }
    }

    private fun toMirror(vm: com.sun.jdi.VirtualMachine, spec: ValueSpec, declaredType: String?): com.sun.jdi.Value? {
        try {
            return when (spec) {
                is ValueSpec.Null -> null
                is ValueSpec.Str -> vm.mirrorOf(spec.value)
                is ValueSpec.ObjId -> throw ApiException(400, "assigning an existing object by id is not supported")
                is ValueSpec.EnumConst -> resolveEnum(vm, declaredType, spec.name)
                is ValueSpec.Prim -> when (spec.kind) {
                    "int" -> vm.mirrorOf(SetValue.toInt(spec.raw))
                    "long" -> vm.mirrorOf(SetValue.toLong(spec.raw))
                    "short" -> vm.mirrorOf(SetValue.toShort(spec.raw))
                    "byte" -> vm.mirrorOf(SetValue.toByte(spec.raw))
                    "double" -> vm.mirrorOf(SetValue.toDouble(spec.raw))
                    "float" -> vm.mirrorOf(SetValue.toFloat(spec.raw))
                    "boolean" -> vm.mirrorOf(SetValue.toBoolean(spec.raw))
                    "char" -> vm.mirrorOf(SetValue.toChar(spec.raw))
                    else -> throw ApiException(400, "unknown primitive kind ${spec.kind}")
                }
            }
        } catch (e: NumberFormatException) {
            throw ApiException(400, "bad number for ${(spec as? ValueSpec.Prim)?.kind}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, e.message ?: "bad value")
        }
    }

    private fun resolveEnum(vm: com.sun.jdi.VirtualMachine, declaredType: String?, name: String): com.sun.jdi.Value {
        val typeName = declaredType ?: throw ApiException(400, "enum needs a known field type")
        val type = vm.classesByName(typeName).firstOrNull()
            ?: throw ApiException(404, "enum type $typeName is not loaded")
        val field = type.fieldByName(name)
            ?: throw ApiException(404, "no enum constant $name on $typeName")
        if (!field.isStatic) throw ApiException(400, "$name on $typeName is not an enum constant")
        return type.getValue(field) ?: throw ApiException(404, "enum constant $name is null on $typeName")
    }

    private fun typeName(value: com.sun.jdi.Value?): String = when (value) {
        null -> "null"
        is com.sun.jdi.ObjectReference -> value.referenceType().name()
        else -> value.type().name()
    }

    private fun render(value: com.sun.jdi.Value?): String? = when (value) {
        null -> "null"
        is com.sun.jdi.StringReference -> value.value()
        is com.sun.jdi.ObjectReference -> "${value.referenceType().name()}@${value.uniqueID()}"
        is com.sun.jdi.CharValue -> "'${value.value()}'"
        else -> safe { value.toString() } ?: value.type().name()
    }

    /** A suspended thread: the event thread of a suspend context, the named thread, or main. Mirrors snapshot. */
    private fun suspendedThreadRef(process: Any, wanted: String?): com.sun.jdi.ThreadReference {
        val ctxs = contexts(process)
        val withThread = ctxs.mapNotNull { c ->
            (call(c, "getEventThread") ?: call(c, "getThread"))?.let { c to it }
        }
        val match = withThread.firstOrNull { (_, proxy) -> wanted == null || call(proxy, "name") == wanted }
        if (match != null) {
            return call(match.second, "getThreadReference") as com.sun.jdi.ThreadReference
        }
        val vm = call(call(process, "getVirtualMachineProxy")!!, "getVirtualMachine") as com.sun.jdi.VirtualMachine
        return vm.allThreads().firstOrNull { it.name() == (wanted ?: "main") && it.isSuspended }
            ?: throw ApiException(409, "no suspended thread${wanted?.let { " named $it" } ?: ""}")
    }

    private fun process(session: XDebugSession): Any? = try {
        call(session.debugProcess, "getDebuggerSession")?.let { call(it, "getProcess") }
    } catch (_: Exception) {
        null
    }

    private fun contexts(process: Any): List<Any> {
        val manager = call(process, "getSuspendManager")!!
        val all = (call(manager, "getEventContexts") as List<*>) + (call(manager, "getPausedContexts") as List<*>)
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        return all.filterNotNull().filter { seen.add(it) && call(it, "isResumed") != true }
    }

    private fun info(context: Any): Map<String, Any?> {
        val thread = call(context, "getEventThread") ?: call(context, "getThread")
        val policy = PauseMapping.jdiPolicy(runCatching { call(context, "getSuspendPolicy") as? Int }.getOrNull())
        return mapOf("thread" to thread?.let { call(it, "name") as? String }, "policy" to policy)
    }

    private fun <T> onManager(process: Any, timeoutMs: Long = TIMEOUT_MS, block: () -> T): T {
        val manager = call(process, "getManagerThread")!!
        val schedule = manager.javaClass.methods.first {
            it.name == "schedule" && it.parameterCount == 2 && it.parameterTypes[1] == Runnable::class.java
        }
        val priority = schedule.parameterTypes[0].enumConstants.first { (it as Enum<*>).name == "NORMAL" }
        val future = CompletableFuture<T>()
        schedule.invoke(manager, priority, Runnable {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw ApiException(504, "debugger manager thread did not answer")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.indices.all { i ->
                    val type = m.parameterTypes[i]
                    val arg = args[i]
                    when {
                        arg == null -> !type.isPrimitive
                        type.isPrimitive -> type.kotlin.javaObjectType.isInstance(arg)
                        else -> type.isInstance(arg)
                    }
                }
        } ?: throw IllegalStateException("no $name on ${target.javaClass.name}")
        return try {
            method.invoke(target, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}

// ponytail: one lock per session. Split locks if two agents drive one session.
private class SessionBook {
    private val ids = java.util.IdentityHashMap<XDebugSession, String>()
    private val byId = ConcurrentHashMap<String, XDebugSession>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val watched = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<XDebugSession, Boolean>())
    private val waiters = java.util.IdentityHashMap<XDebugSession, CopyOnWriteArrayList<CompletableFuture<String>>>()

    fun markWatched(session: XDebugSession): Boolean = synchronized(watched) { watched.add(session) }

    fun idOf(session: XDebugSession): String = synchronized(ids) {
        ids.getOrPut(session) {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            byId[id] = session
            id
        }
    }

    fun get(id: String): XDebugSession? = byId[id]

    fun lock(session: XDebugSession): Any = locks.computeIfAbsent(idOf(session)) { Any() }

    fun remove(session: XDebugSession) {
        val id = synchronized(ids) { ids.remove(session) } ?: return
        byId.remove(id)
        locks.remove(id)
        synchronized(watched) { watched.remove(session) }
    }

    fun addWaiter(session: XDebugSession, future: CompletableFuture<String>) {
        synchronized(waiters) {
            waiters.getOrPut(session) { CopyOnWriteArrayList() }.add(future)
        }
    }

    fun removeWaiter(session: XDebugSession, future: CompletableFuture<String>) {
        synchronized(waiters) { waiters[session]?.remove(future) }
    }

    fun fire(session: XDebugSession, event: String) {
        val pending = synchronized(waiters) { waiters[session]?.toList().orEmpty() }
        pending.forEach { it.complete(event) }
        synchronized(waiters) { waiters[session]?.clear() }
    }
}
