package dev.asdbg

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ShortValue
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VoidValue

/** Limits for one pause snapshot. depth counts child levels below each variable. */
internal data class SnapshotOptions(
    val frameIndex: Int = 0,
    val depth: Int = 2,
    val maxChildren: Int = 50,
    val maxString: Int = 1000,
    val maxNodes: Int = 2000,
    val maxFrames: Int = 200,
    val timeoutMs: Long = 10_000L,
    val thread: String? = null,
    val toStringCalls: Boolean = false,
) {
    fun clamp() = copy(
        frameIndex = frameIndex.coerceAtLeast(0),
        depth = depth.coerceIn(0, 6),
        maxChildren = maxChildren.coerceIn(1, 1000),
        maxString = maxString.coerceIn(16, 100_000),
        maxNodes = maxNodes.coerceIn(10, 20_000),
        maxFrames = maxFrames.coerceIn(1, 2000),
        timeoutMs = timeoutMs.coerceIn(1_000L, 60_000L),
    )

    fun dto(): Map<String, Any?> = mapOf(
        "frameIndex" to frameIndex,
        "depth" to depth,
        "maxChildren" to maxChildren,
        "maxString" to maxString,
        "maxNodes" to maxNodes,
        "maxFrames" to maxFrames,
        "timeoutMs" to timeoutMs,
        "toString" to toStringCalls,
    )
}

/**
 * Reads JDI mirror values only. It never invokes a method in the debuggee, so it is safe in a suspend=thread stop.
 * Call it on the debugger manager thread while the thread is suspended.
 */
internal class MirrorWalker(
    private val opts: SnapshotOptions,
    private val isSecret: (String) -> Boolean,
) {
    private val deadline = System.nanoTime() + opts.timeoutMs * 1_000_000
    var nodes = 0
        private set
    var truncated = false
        private set
    var truncatedReason: String? = null
        private set

    /** Top-level object nodes, kept for the opt-in toString pass. */
    val topObjects = mutableListOf<Pair<MutableMap<String, Any?>, ObjectReference>>()

    fun frame(index: Int, frame: StackFrame): MutableMap<String, Any?> {
        val loc = frame.location()
        val method = runCatching { loc.method() }.getOrNull()
        return linkedMapOf(
            "index" to index,
            "class" to runCatching { loc.declaringType().name() }.getOrNull(),
            "method" to method?.name(),
            "line" to loc.lineNumber().takeIf { it > 0 },
            "sourceName" to runCatching { loc.sourceName() }.getOrNull(),
            "sourcePath" to runCatching { loc.sourcePath() }.getOrNull(),
            "native" to (method?.isNative ?: false),
        )
    }

    /** `this` and the visible locals of one frame. */
    fun variables(frame: StackFrame): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val self = runCatching { frame.thisObject() }.getOrNull()
        out["this"] = self?.let { node("this", it.referenceType().name(), it, 0, emptySet(), top = true) }
        try {
            // Kotlin inline markers ($i$a$..., $i$f$...) are hidden in the IDE too.
            val vars = frame.visibleVariables().filter { !it.name().startsWith("\$i\$") }
            val values = frame.getValues(vars)
            out["locals"] = vars.map { v ->
                node(v.name(), v.typeName(), values[v], 0, emptySet(), top = true).also {
                    if (v.isArgument) it["argument"] = true
                }
            }
        } catch (_: AbsentInformationException) {
            out["locals"] = emptyList<Any>()
            out["localsError"] = "no local variable table (built without debug info?)"
        }
        return out
    }

    fun node(
        name: String,
        declaredType: String?,
        value: Value?,
        level: Int,
        path: Set<Long>,
        top: Boolean = false,
    ): MutableMap<String, Any?> {
        nodes++
        val out = linkedMapOf<String, Any?>("name" to name)
        if (isSecret(name)) {
            out["type"] = declaredType
            out["value"] = "***"
            out["masked"] = true
            return out
        }
        when (value) {
            null -> {
                out["type"] = declaredType
                out["value"] = "null"
            }
            is StringReference -> {
                val text = value.value()
                out["type"] = "java.lang.String"
                out["id"] = value.uniqueID()
                out["length"] = text.length
                out["value"] = clip(text, out)
            }
            is ArrayReference -> array(value, out, level, path)
            is ObjectReference -> {
                obj(value, out, level, path)
                if (top) topObjects += out to value
            }
            is VoidValue -> {
                out["type"] = "void"
                out["value"] = "void"
            }
            else -> {
                val (type, text) = primitive(value)
                out["type"] = type
                out["value"] = text
            }
        }
        return out
    }

    private fun array(value: ArrayReference, out: MutableMap<String, Any?>, level: Int, path: Set<Long>) {
        val length = value.length()
        val type = value.referenceType().name()
        out["type"] = type
        out["id"] = value.uniqueID()
        out["length"] = length
        out["value"] = "{${simple(type)}@${value.uniqueID()}} length = $length"
        if (length == 0) return
        if (!canExpand(value, out, level, path)) return
        val n = minOf(length, opts.maxChildren)
        val component = type.removeSuffix("[]")
        val items = value.getValues(0, n)
        out["children"] = items.mapIndexedNotNull { i, item ->
            if (!budget()) null else node("[$i]", component, item, level + 1, path + value.uniqueID())
        }
        if (length > n) markTruncated(out, "maxChildren")
    }

    private fun obj(value: ObjectReference, out: MutableMap<String, Any?>, level: Int, path: Set<Long>) {
        val type = value.referenceType()
        val typeName = type.name()
        out["type"] = typeName
        out["id"] = value.uniqueID()
        val label = "{${simple(typeName)}@${value.uniqueID()}}"
        if (typeName in BOXED) {
            val inner = type.fieldByName("value")?.let { value.getValue(it) }
            out["value"] = inner?.let { primitive(it).second } ?: label
            return
        }
        if (type is ClassType && type.isEnum) {
            val enumName = type.fieldByName("name")?.let { value.getValue(it) as? StringReference }?.value()
            out["value"] = if (enumName != null) "$label $enumName" else label
        }
        val elements = elementsOf(value, typeName)
        if (elements != null) {
            out["size"] = elements.size
            out["value"] = "$label size = ${elements.size}"
            if (elements.size == 0) return
            if (!canExpand(value, out, level, path)) return
            out["children"] = elements.read(opts.maxChildren).mapIndexedNotNull { i, entry ->
                if (!budget()) return@mapIndexedNotNull null
                if (entry.key == null) {
                    node("[$i]", null, entry.value, level + 1, path + value.uniqueID())
                } else {
                    entryNode(i, entry, level + 1, path + value.uniqueID())
                }
            }
            if (elements.size > opts.maxChildren) markTruncated(out, "maxChildren")
            return
        }
        if (!out.containsKey("value")) out["value"] = label
        val fields = type.allFields().filter { !it.isStatic }
        if (fields.isEmpty()) return
        if (!canExpand(value, out, level, path)) return
        val shown = fields.take(opts.maxChildren)
        val values = value.getValues(shown)
        out["children"] = shown.mapNotNull { f ->
            if (!budget()) null else node(f.name(), f.typeName(), values[f], level + 1, path + value.uniqueID())
        }
        if (fields.size > shown.size) {
            out["childCount"] = fields.size
            markTruncated(out, "maxChildren")
        }
    }

    private fun entryNode(i: Int, entry: Element, level: Int, path: Set<Long>): MutableMap<String, Any?> {
        nodes++
        val out = linkedMapOf<String, Any?>("name" to "[$i]", "type" to "entry")
        val key = node("key", null, entry.key, level + 1, path)
        val keyText = key["value"]?.toString().orEmpty()
        val shownValue = if (isSecret(keyText)) {
            linkedMapOf<String, Any?>("name" to "value", "value" to "***", "masked" to true)
        } else {
            node("value", null, entry.value, level + 1, path)
        }
        out["value"] = "$keyText -> ${shownValue["value"]}"
        out["children"] = listOf(key, shownValue)
        return out
    }

    private class Element(val key: Value?, val value: Value?)

    private class Elements(val size: Int, val read: (Int) -> List<Element>)

    /** Element access for common JDK collections by their fields. No method is invoked. */
    private fun elementsOf(value: ObjectReference, typeName: String): Elements? {
        val type = value.referenceType()
        fun field(name: String): Value? = type.fieldByName(name)?.let { value.getValue(it) }
        fun int(name: String): Int? = (field(name) as? IntegerValue)?.value()
        return when (typeName) {
            "java.util.ArrayList", "java.util.Vector" -> {
                val size = int("size") ?: int("elementCount") ?: return null
                val data = field("elementData") as? ArrayReference ?: return null
                Elements(size) { max -> data.getValues(0, minOf(size, max, data.length())).map { Element(null, it) } }
            }
            "java.util.Arrays\$ArrayList" -> {
                val data = field("a") as? ArrayReference ?: return null
                Elements(data.length()) { max -> data.getValues(0, minOf(data.length(), max)).map { Element(null, it) } }
            }
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.concurrent.ConcurrentHashMap" -> {
                val size = int("size") ?: return null
                Elements(size) { max -> hashEntries(field("table") as? ArrayReference, max) }
            }
            "java.util.HashSet", "java.util.LinkedHashSet" -> {
                val map = field("map") as? ObjectReference ?: return null
                val mapType = map.referenceType()
                val size = mapType.fieldByName("size")?.let { (map.getValue(it) as? IntegerValue)?.value() } ?: return null
                val table = mapType.fieldByName("table")?.let { map.getValue(it) as? ArrayReference }
                Elements(size) { max -> hashEntries(table, max).map { Element(null, it.key) } }
            }
            else -> null
        }
    }

    private fun hashEntries(table: ArrayReference?, max: Int): List<Element> {
        if (table == null) return emptyList()
        val out = mutableListOf<Element>()
        for (bucket in table.values) {
            var cur = bucket as? ObjectReference
            while (cur != null && out.size < max) {
                val t = cur.referenceType()
                fun f(vararg names: String): Value? =
                    names.firstNotNullOfOrNull { t.fieldByName(it) }?.let { cur!!.getValue(it) }
                out += Element(f("key"), f("value", "val"))
                cur = f("next") as? ObjectReference
            }
            if (out.size >= max) break
        }
        return out
    }

    private fun canExpand(value: ObjectReference, out: MutableMap<String, Any?>, level: Int, path: Set<Long>): Boolean {
        if (value.uniqueID() in path) {
            out["cycle"] = true
            return false
        }
        if (level >= opts.depth) {
            out["hasChildren"] = true
            return false
        }
        if (!budget()) {
            out["hasChildren"] = true
            return false
        }
        return true
    }

    private fun budget(): Boolean {
        if (nodes >= opts.maxNodes) return stop("maxNodes")
        if (System.nanoTime() > deadline) return stop("timeout")
        return true
    }

    private fun stop(reason: String): Boolean {
        truncated = true
        if (truncatedReason == null) truncatedReason = reason
        return false
    }

    private fun markTruncated(out: MutableMap<String, Any?>, reason: String) {
        out["truncated"] = true
        stop(reason)
    }

    private fun clip(text: String, out: MutableMap<String, Any?>): String {
        if (text.length <= opts.maxString) return text
        markTruncated(out, "maxString")
        return text.take(opts.maxString)
    }

    companion object {
        private val BOXED = setOf(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Boolean", "java.lang.Character", "java.lang.Float", "java.lang.Double",
        )

        fun simple(typeName: String): String = typeName.substringAfterLast('.')

        fun primitive(value: Value): Pair<String, String> = when (value) {
            is BooleanValue -> "boolean" to value.value().toString()
            is ByteValue -> "byte" to value.value().toString()
            is CharValue -> "char" to "'${value.value()}'"
            is ShortValue -> "short" to value.value().toString()
            is IntegerValue -> "int" to value.value().toString()
            is LongValue -> "long" to value.value().toString()
            is FloatValue -> "float" to value.value().toString()
            is DoubleValue -> "double" to value.value().toString()
            else -> (runCatching { value.type().name() }.getOrNull() ?: "?") to value.toString()
        }

        fun threadStatus(thread: ThreadReference): String = when (runCatching { thread.status() }.getOrNull()) {
            ThreadReference.THREAD_STATUS_RUNNING -> "RUNNING"
            ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING"
            ThreadReference.THREAD_STATUS_MONITOR -> "MONITOR"
            ThreadReference.THREAD_STATUS_WAIT -> "WAIT"
            ThreadReference.THREAD_STATUS_ZOMBIE -> "ZOMBIE"
            ThreadReference.THREAD_STATUS_NOT_STARTED -> "NOT_STARTED"
            else -> "UNKNOWN"
        }
    }
}

/** Reads one suspended thread into a snapshot map. Runs on the debugger manager thread. */
internal object PauseReader {
    fun read(thread: ThreadReference, opts: SnapshotOptions, isSecret: (String) -> Boolean): Pair<MutableMap<String, Any?>, MirrorWalker> {
        val walker = MirrorWalker(opts, isSecret)
        val all = thread.frames()
        if (all.isEmpty()) throw IllegalStateException("thread ${thread.name()} has no frames")
        val index = opts.frameIndex.coerceAtMost(all.size - 1)
        val frames = all.take(opts.maxFrames).mapIndexed { i, f -> walker.frame(i, f) }
        val selected = all[index]
        val out = linkedMapOf<String, Any?>(
            "thread" to linkedMapOf(
                "name" to thread.name(),
                "status" to MirrorWalker.threadStatus(thread),
                "suspended" to thread.isSuspended,
                "suspendCount" to runCatching { thread.suspendCount() }.getOrNull(),
                "atBreakpoint" to runCatching { thread.isAtBreakpoint }.getOrNull(),
            ),
            "frameIndex" to index,
            "frameCount" to all.size,
            "frames" to frames,
            "framesTruncated" to (all.size > frames.size),
            "location" to (if (index < frames.size) frames[index] else walker.frame(index, selected)),
            "variables" to walker.variables(selected),
        )
        return out to walker
    }

    /** Opt-in. Invokes toString() on top-level objects. Runs debuggee code, so call it after every mirror read. */
    fun addToStrings(
        thread: ThreadReference,
        walker: MirrorWalker,
        maxString: Int,
        perCallMs: Long = 2_000L,
        maxCalls: Int = 20,
    ): String? {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "asdbg-toString").apply { isDaemon = true }
        }
        try {
            for ((node, obj) in walker.topObjects.take(maxCalls)) {
                if (node["masked"] == true) continue
                val method = (obj.referenceType() as? ClassType)
                    ?.concreteMethodByName("toString", "()Ljava/lang/String;") ?: continue
                val future = pool.submit<String?> {
                    (obj.invokeMethod(thread, method, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED) as? StringReference)?.value()
                }
                try {
                    val text = future.get(perCallMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    node["toString"] = text?.let { if (it.length > maxString) it.take(maxString) else it }
                } catch (_: java.util.concurrent.TimeoutException) {
                    node["toStringError"] = "timeout"
                    return "toString timed out on ${node["name"]}. The thread may be stopped inside toString; stopped further calls"
                } catch (e: java.util.concurrent.ExecutionException) {
                    node["toStringError"] = e.cause?.javaClass?.simpleName ?: "error"
                }
            }
            return null
        } finally {
            pool.shutdown()
        }
    }
}

/** A step in a value path: a field or local name, or an array index. */
internal sealed class PathToken {
    data class Name(val name: String) : PathToken()
    data class Index(val index: Int) : PathToken()
}

/** What value to write. Kept free of JDI so it can be unit tested. */
internal sealed class ValueSpec {
    object Null : ValueSpec()
    data class Str(val value: String) : ValueSpec()
    data class EnumConst(val name: String) : ValueSpec()
    data class ObjId(val id: Long) : ValueSpec()

    /** kind is one of the PRIMITIVES names; raw is the textual form from JSON or the CLI. */
    data class Prim(val kind: String, val raw: String) : ValueSpec()
}

/** Pure parsing and conversion for SET_VALUE. No JDI, so unit tests cover it. */
internal object SetValue {
    val PRIMITIVES = setOf("int", "long", "short", "byte", "double", "float", "boolean", "char")

    /** `responsePay.result.code`, `arr[2]`, `this.payment.amount`, `com.example.Foo.FIELD`. */
    fun parsePath(path: String): List<PathToken> {
        val s = path.trim()
        require(s.isNotEmpty()) { "empty path" }
        val tokens = mutableListOf<PathToken>()
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '.' -> {
                    i++
                    require(i < s.length && s[i] != '.' && s[i] != '[') { "unexpected '.' in path: $path" }
                }
                '[' -> {
                    val close = s.indexOf(']', i)
                    require(close > i) { "missing ']' in path: $path" }
                    val num = s.substring(i + 1, close).trim()
                    val idx = num.toIntOrNull() ?: throw IllegalArgumentException("array index must be a number: [$num]")
                    require(idx >= 0) { "array index must be >= 0: [$num]" }
                    tokens += PathToken.Index(idx)
                    i = close + 1
                }
                else -> {
                    val start = i
                    while (i < s.length && s[i] != '.' && s[i] != '[') i++
                    val name = s.substring(start, i).trim()
                    require(name.isNotEmpty()) { "empty name in path: $path" }
                    tokens += PathToken.Name(name)
                }
            }
        }
        require(tokens.isNotEmpty()) { "empty path" }
        require(tokens.first() is PathToken.Name) { "path must start with a name, not an index" }
        return tokens
    }

    /** Exactly one value kind must be present. */
    fun parseSpec(spec: Map<String, Any?>): ValueSpec {
        val kinds = mutableListOf<ValueSpec>()
        if (spec["null"] == true) kinds += ValueSpec.Null
        spec["string"]?.let { kinds += ValueSpec.Str(it.toString()) }
        spec["enum"]?.let { kinds += ValueSpec.EnumConst(it.toString()) }
        spec["objectId"]?.let { kinds += ValueSpec.ObjId(it.toString().trim().toLong()) }
        for (kind in PRIMITIVES) spec[kind]?.let { kinds += ValueSpec.Prim(kind, it.toString()) }
        if (kinds.isEmpty()) {
            throw IllegalArgumentException("no value kind. Use null, string, enum, or one of $PRIMITIVES")
        }
        if (kinds.size > 1) throw IllegalArgumentException("give exactly one value kind, got ${kinds.size}")
        return kinds.first()
    }

    fun toBoolean(raw: String): Boolean = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("boolean must be true or false: $raw")
    }

    /** A single character, or an integer code point for it. */
    fun toChar(raw: String): Char = when {
        raw.length == 1 -> raw[0]
        else -> {
            val code = raw.trim().toIntOrNull()
                ?: throw IllegalArgumentException("char must be one character or a number: $raw")
            require(code in 0..0xFFFF) { "char code out of range: $code" }
            code.toChar()
        }
    }

    fun toInt(raw: String): Int = raw.trim().toInt()
    fun toLong(raw: String): Long = raw.trim().toLong()
    fun toShort(raw: String): Short = raw.trim().toShort()
    fun toByte(raw: String): Byte = raw.trim().toByte()
    fun toDouble(raw: String): Double = raw.trim().toDouble()
    fun toFloat(raw: String): Float = raw.trim().toFloat()
}
