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
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
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

    fun start() {
        if (server != null) return
        Files.createDirectories(home)
        val token = loadOrCreateToken()
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
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                sessions.fire(session, "paused")
            }

            override fun sessionStopped() {
                sessions.fire(session, "stopped")
                sessions.remove(session)
            }
        })
    }

    private fun route(exchange: HttpExchange, token: String) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod.uppercase()
        if (method == "GET" && path == "/health") {
            send(exchange, 200, mapOf("ok" to true))
            return
        }
        if (!path.startsWith("/v1/")) {
            send(exchange, 404, mapOf("error" to "not found"))
            return
        }
        authorize(exchange, token)
        val project = project(exchange)
        when {
            method == "POST" && path == "/v1/breakpoints" -> send(exchange, 200, addBreakpoint(project, body(exchange)))
            method == "GET" && path == "/v1/breakpoints" -> send(exchange, 200, mapOf("breakpoints" to listBreakpoints(project)))
            method == "DELETE" && path == "/v1/breakpoints" -> send(exchange, 200, clearAllBreakpoints(project))
            method == "DELETE" && path.startsWith("/v1/breakpoints/") -> {
                removeBreakpoint(project, path.removePrefix("/v1/breakpoints/"))
                send(exchange, 200, mapOf("removed" to true))
            }
            method == "POST" && path == "/v1/sessions" -> send(exchange, 200, startSession(project, body(exchange)))
            method == "POST" && path == "/v1/attach" -> send(exchange, 200, attachToProcess(project, body(exchange)))
            method == "GET" && path == "/v1/sessions" -> send(exchange, 200, mapOf("sessions" to listSessions(project)))
            method == "POST" && path.matches(Regex("/v1/sessions/[^/]+/control")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/control")
                send(exchange, 200, control(project, id, body(exchange)))
            }
            method == "GET" && path.matches(Regex("/v1/sessions/[^/]+/stack")) -> {
                val id = path.removePrefix("/v1/sessions/").removeSuffix("/stack")
                send(exchange, 200, mapOf("frames" to stack(project, id)))
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
        val packageName = json.str("package") ?: "jp.co.persol.postas.selfregi"
        val timeout = json.long("timeout") ?: 60_000L
        if (timeout < 1_000L) {
            throw ApiException(400, "timeout is in milliseconds. got $timeout ms. Use 30000 for 30 seconds")
        }
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
                withTimeout(timeout) {
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
                withTimeout(timeout) {
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
            throw ApiException(504, "attach timed out after $timeout ms. stopped ${leftover.size} half-open session(s)")
        }
        watch(session)
        return sessionDto(session) + mapOf(
            "attached" to true,
            "serial" to device.serialNumber,
            "package" to packageName,
            "pid" to client.clientData.pid,
        )
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

    private fun control(project: Project, id: String, json: JsonObject): Map<String, Any?> {
        val action = json.str("action") ?: throw ApiException(400, "action is required")
        val timeout = json.long("timeout") ?: 60_000L
        val session = requireSession(project, id)
        val sessionId = sessions.idOf(session)
        val extra = mutableMapOf<String, Any?>()
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
                "STEP_OVER" -> {
                    if (!session.isSuspended) throw ApiException(409, "session is running")
                    edt { session.stepOver(false) }
                }
                "STEP_INTO" -> {
                    if (!session.isSuspended) throw ApiException(409, "session is running")
                    edt { session.stepInto() }
                }
                "STEP_OUT" -> {
                    if (!session.isSuspended) throw ApiException(409, "session is running")
                    edt { session.stepOut() }
                }
                "STOP" -> edt { session.stop() }
                "WAIT_FOR_PAUSE" -> return waitPause(session, timeout)
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

    private fun stack(project: Project, id: String): List<Map<String, Any?>> {
        val session = requireSession(project, id)
        if (safe { session.isSuspended } != true) throw ApiException(409, "session is running")
        val active = session.suspendContext?.activeExecutionStack
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
        return mapOf("type" to shown.first, "value" to mask("expression", shown.second))
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
            "position" to position(session),
        )
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
        )
        if (kindOf(bp.type) == "exception") dto["className"] = exceptionName(bp).ifBlank { "*" }
        return dto
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

    /** Last resort: resumes the whole VM. The IDE may still show the old suspend state. */
    fun resumeVm(session: XDebugSession): Boolean {
        val process = process(session) ?: return false
        onManager(process) { call(call(process, "getVirtualMachineProxy")!!, "resume") }
        return true
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
        val policy = when (call(context, "getSuspendPolicy") as? Int) {
            0 -> "none"
            1 -> "thread"
            2 -> "all"
            else -> "unknown"
        }
        return mapOf("thread" to thread?.let { call(it, "name") as? String }, "policy" to policy)
    }

    private fun <T> onManager(process: Any, block: () -> T): T {
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
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw ApiException(504, "debugger manager thread did not answer")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.indices.all { args[it] == null || m.parameterTypes[it].isInstance(args[it]) }
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
