package dev.asdbg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SetValueTest {
    private fun names(path: String) = SetValue.parsePath(path).map {
        when (it) {
            is PathToken.Name -> it.name
            is PathToken.Index -> "[${it.index}]"
        }
    }

    @Test
    fun parsesDottedAndIndexedPaths() {
        assertEquals(listOf("responsePay", "result", "code"), names("responsePay.result.code"))
        assertEquals(listOf("arr", "[2]"), names("arr[2]"))
        assertEquals(listOf("this", "payment", "amount"), names("this.payment.amount"))
        assertEquals(listOf("grid", "[0]", "[1]", "cell"), names("grid[0][1].cell"))
        assertEquals(listOf("com", "example", "Foo", "FIELD"), names("com.example.Foo.FIELD"))
        assertEquals(listOf("x"), names("  x  "))
    }

    @Test
    fun rejectsBadPaths() {
        for (bad in listOf("", "   ", "[0]", "a..b", "a.", "arr[x]", "arr[-1]", "arr[2")) {
            try {
                SetValue.parsePath(bad)
                fail("expected failure for '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun parsesExactlyOneValueKind() {
        assertEquals(ValueSpec.Null, SetValue.parseSpec(mapOf("null" to true)))
        assertEquals(ValueSpec.Str("hi"), SetValue.parseSpec(mapOf("string" to "hi")))
        assertEquals(ValueSpec.EnumConst("PAID"), SetValue.parseSpec(mapOf("enum" to "PAID")))
        assertEquals(ValueSpec.Prim("int", "5"), SetValue.parseSpec(mapOf("int" to "5")))
        assertEquals(ValueSpec.Prim("boolean", "true"), SetValue.parseSpec(mapOf("boolean" to true)))
        assertEquals(ValueSpec.ObjId(42L), SetValue.parseSpec(mapOf("objectId" to "42")))
        // null:false is not a null assignment, so it is treated as no kind.
        try {
            SetValue.parseSpec(mapOf("null" to false))
            fail("expected no-kind failure")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SetValue.parseSpec(mapOf("int" to "1", "long" to "2"))
            fail("expected two-kind failure")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SetValue.parseSpec(emptyMap())
            fail("expected empty failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun convertsPrimitivesWithChecking() {
        assertEquals(5, SetValue.toInt("5"))
        assertEquals(5, SetValue.toInt("  5 "))
        assertEquals(10L, SetValue.toLong("10"))
        assertEquals(1.5, SetValue.toDouble("1.5"), 0.0)
        assertTrue(SetValue.toBoolean("TRUE"))
        assertEquals('A', SetValue.toChar("A"))
        assertEquals('A', SetValue.toChar("65"))
        for (bad in listOf("x", "", "1.5")) {
            try {
                SetValue.toInt(bad)
                fail("expected number failure for '$bad'")
            } catch (_: NumberFormatException) {
            }
        }
        try {
            SetValue.toBoolean("yes")
            fail("expected boolean failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
