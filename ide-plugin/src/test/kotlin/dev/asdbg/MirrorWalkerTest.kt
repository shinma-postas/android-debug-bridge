package dev.asdbg

import com.google.gson.GsonBuilder
import com.sun.jdi.ArrayReference
import com.sun.jdi.ClassType
import com.sun.jdi.Field
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** JDI interfaces faked with java.lang.reflect.Proxy. Only the calls MirrorWalker makes are answered. */
class MirrorWalkerTest {
    private var nextId = 100L

    private inline fun <reified T> fake(answers: Map<String, (Array<Any?>) -> Any?>): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { self, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "fake ${T::class.java.simpleName}"
                else -> answers[m.name]?.invoke(args ?: emptyArray())
                    ?: if (m.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as T

    private fun int(v: Int): IntegerValue = fake(mapOf("value" to { _ -> v }))

    private fun str(v: String): StringReference {
        val id = nextId++
        return fake(mapOf("value" to { _ -> v }, "uniqueID" to { _ -> id }))
    }

    private fun field(name: String, type: String): Field =
        fake(mapOf("name" to { _ -> name }, "typeName" to { _ -> type }))

    private fun obj(typeName: String, values: List<Triple<String, String, Value?>>): ObjectReference {
        val id = nextId++
        val fields = values.map { field(it.first, it.second) }
        val byField = fields.zip(values.map { it.third }).toMap()
        val type: ClassType = fake(mapOf(
            "name" to { _ -> typeName },
            "allFields" to { _ -> fields },
            "fieldByName" to { a -> fields.firstOrNull { it.name() == a[0] } },
        ))
        return fake(mapOf(
            "referenceType" to { _ -> type },
            "uniqueID" to { _ -> id },
            "getValue" to { a -> byField[a[0]] },
            "getValues" to { a -> (a[0] as List<*>).associateWith { byField[it] } },
        ))
    }

    private fun array(typeName: String, items: List<Value?>): ArrayReference {
        val id = nextId++
        val type: ClassType = fake(mapOf("name" to { _ -> typeName }))
        return fake(mapOf(
            "referenceType" to { _ -> type },
            "uniqueID" to { _ -> id },
            "length" to { _ -> items.size },
            "getValues" to { a -> if (a.isEmpty()) items else items.subList(a[0] as Int, (a[0] as Int) + (a[1] as Int)) },
        ))
    }

    private val secret = Regex("(?i)(pass(word)?|secret|token|api[-_]?key|credential)")

    private fun sample(): ObjectReference {
        val list = obj("java.util.ArrayList", listOf(
            Triple("size", "int", int(2)),
            Triple("elementData", "java.lang.Object[]", array("java.lang.Object[]", listOf(str("A001"), str("A002"), null))),
        ))
        val payment = obj("com.example.Payment", listOf(
            Triple("amount", "int", int(1200)),
            Triple("authToken", "java.lang.String", str("dummy-not-real")),
            Triple("items", "java.util.List", list),
        ))
        return obj("jp.example.SampleActivity", listOf(
            Triple("state", "java.lang.String", str("PAYING")),
            Triple("payment", "com.example.Payment", payment),
            Triple("note", "java.lang.String", str("x".repeat(40))),
        ))
    }

    @Test
    fun readsFieldsToDepthWithMaskAndLimits() {
        val walker = MirrorWalker(SnapshotOptions(depth = 2, maxString = 16), { secret.containsMatchIn(it) })
        val self = sample()
        val node = walker.node("this", "jp.example.SampleActivity", self, 0, emptySet(), top = true)

        @Suppress("UNCHECKED_CAST")
        val children = node["children"] as List<Map<String, Any?>>
        assertEquals("PAYING", children[0]["value"])
        val payment = children[1]
        @Suppress("UNCHECKED_CAST")
        val paymentChildren = payment["children"] as List<Map<String, Any?>>
        assertEquals("1200", paymentChildren[0]["value"])
        assertEquals("***", paymentChildren[1]["value"])
        assertEquals(true, paymentChildren[1]["masked"])
        // depth 2: the list is at level 2, so it shows its size but no children.
        assertEquals(2, paymentChildren[2]["size"])
        assertEquals(true, paymentChildren[2]["hasChildren"])
        assertEquals(16, (children[2]["value"] as String).length)
        assertEquals(true, children[2]["truncated"])
        assertTrue(walker.truncated)
        assertEquals("maxString", walker.truncatedReason)
        assertEquals(1, walker.topObjects.size)

        val example = linkedMapOf<String, Any?>(
            "sessionId" to "(session id)",
            "thread" to mapOf("name" to "main", "policy" to "thread", "threadLevel" to true),
            "frameIndex" to 0,
            "variables" to mapOf("this" to node, "locals" to listOf(walker.node("count", "int", int(3), 0, emptySet()))),
            "nodes" to walker.nodes,
            "truncated" to walker.truncated,
            "truncatedReason" to walker.truncatedReason,
        )
        val json = GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(example)
        System.getProperty("asdbg.exampleOut")?.let { File(it).writeText(json) }
    }

    @Test
    fun expandsArrayListElementsWhenDeepEnough() {
        val walker = MirrorWalker(SnapshotOptions(depth = 3), { false })
        val node = walker.node("this", null, sample(), 0, emptySet())
        @Suppress("UNCHECKED_CAST")
        val items = ((node["children"] as List<Map<String, Any?>>)[1]["children"] as List<Map<String, Any?>>)[2]
        @Suppress("UNCHECKED_CAST")
        val elements = items["children"] as List<Map<String, Any?>>
        assertEquals(listOf("A001", "A002"), elements.map { it["value"] })
        assertEquals(listOf("[0]", "[1]"), elements.map { it["name"] })
    }

    @Test
    fun stopsAtMaxChildrenAndMaxNodes() {
        val many = obj("jp.example.Wide", (1..10).map { Triple("f$it", "int", int(it) as Value?) })
        val walker = MirrorWalker(SnapshotOptions(maxChildren = 3), { false })
        val node = walker.node("w", null, many, 0, emptySet())
        assertEquals(3, (node["children"] as List<*>).size)
        assertEquals(10, node["childCount"])
        assertEquals(true, node["truncated"])

        val tight = MirrorWalker(SnapshotOptions(maxNodes = 10).clamp(), { false })
        tight.node("w", null, many, 0, emptySet())
        assertTrue(tight.nodes <= 11)
        assertEquals("maxNodes", tight.truncatedReason)
    }
}
