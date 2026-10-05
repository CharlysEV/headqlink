package dev.qdauto.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun parsesAndPreservesTypesAndOrder() {
        val text = """{"z":1,"a":-2147483649,"m":1.5,"e":1e3,"s":"x","t":true,"f":false,"n":null,"o":{"k":[1,"2",{}]},"big":12345678901234567890}"""
        val o = JsonParser.parseObject(text)
        assertEquals(listOf("z", "a", "m", "e", "s", "t", "f", "n", "o", "big"), o.keys.toList())
        assertEquals(JsonNumber.of(1), o["z"])
        assertTrue((o["z"] as JsonNumber).value is Int)
        assertTrue((o["a"] as JsonNumber).value is Long)
        assertEquals(-2147483649L, (o["a"] as JsonNumber).toLong())
        assertTrue((o["m"] as JsonNumber).value is Double)
        assertTrue((o["e"] as JsonNumber).value is Double) // con exponente → Double, como org.json
        assertTrue((o["big"] as JsonNumber).value is Double)
        assertEquals(JsonNull, o["n"])
        assertEquals(JsonBool(true), o["t"])
        assertEquals(3, o.obj("o")!!.array("k")!!.size)
    }

    @Test
    fun roundTripIsExact() {
        val samples = listOf(
            """{"PARA":{"VideoFormat":3,"VideoSupport":1},"CMD":"VIDEO_SUP_RSP"}""",
            """{"a":[1,2.5,-3,"x",null,true,false,{"b":{}},[]],"c":-9223372036854775808}""",
            """{"esc":"\"\\\/\b\f\n\r\t\u0001\u001f","uni":"ñ€😀"}""",
            "[]",
            "{}",
        )
        for (s in samples) assertEquals(s, JsonParser.parse(s).toJson(), "round trip de $s")
    }

    @Test
    fun serializerMatchesAndroidOrgJson() {
        // JSONStringer de Android: escapa / y los controles; integrales sin decimales; -0.0 → "-0".
        val o = buildJsonObject {
            put("slash", "a/b")
            put("ctl", "\u0000\u0007\b\t\n\u000B\u000C\r\u001F")
            put("quote", "\"\\")
            put("intD", 5.0)
            put("frac", 0.1)
            put("negZero", -0.0)
            put("long", 1L shl 40)
            put("float", 0.1f)
            put("exp", 1e20)
            put("nul", null)
            put("u2028", "\u2028ñ")
        }
        assertEquals(
            """{"slash":"a\/b","ctl":"\u0000\u0007\b\t\n\u000b\f\r\u001f","quote":"\"\\","intD":5,"frac":0.1,""" +
                """"negZero":-0,"long":1099511627776,"float":0.1,"exp":1.0E20,"nul":null,"u2028":"""" + "\u2028ñ\"}",
            o.toJson(),
        )
        assertFailsWith<IllegalArgumentException> { JsonObject.of("nan" to Double.NaN).toJson() }
    }

    @Test
    fun coercingGettersFollowOrgJson() {
        val o = JsonParser.parseObject("""{"i":"12.7","n":7,"d":7.9,"s":"txt","b":"TRUE","nul":null,"obj":{"x":1}}""")
        assertEquals(12, o.int("i")) // (int) Double.parseDouble("12.7")
        assertEquals(7, o.int("d")) // intValue() trunca
        assertNull(o.int("s"))
        assertNull(o.int("nul"))
        assertNull(o.int("missing"))
        assertEquals("7", o.string("n"))
        assertEquals("7.9", o.string("d"))
        assertEquals("null", o.string("nul")) // getString de un JSON null devuelve "null"
        assertEquals("""{"x":1}""", o.string("obj"))
        assertNull(o.string("missing"))
        assertEquals(true, o.bool("b"))
        assertTrue(o.isNull("nul"))
        assertTrue(o.isNull("missing"))
        assertEquals(12L, o.long("i"))
        assertEquals(12.7, o.double("i"))
    }

    @Test
    fun trailingContentLikeAndroid() {
        val text = "{\"CMD\":\"X\"}\u0000\u0000garbage"
        assertFailsWith<JsonParseException> { JsonParser.parse(text) }
        assertEquals("X", JsonParser.parseObject(text, allowTrailing = true).string("CMD"))
        assertEquals("X", JsonParser.parseObject("\uFEFF  {\"CMD\":\"X\"}  ").string("CMD"))
    }

    @Test
    fun rejectsMalformedInput() {
        val bad = listOf("", "{", "{\"a\"}", "{\"a\":}", "[1,]", "{'a':1}", "{\"a\":01x}", "\"\\x\"", "-", "1.", "1e", "tru", "{\"a\":1,}")
        for (s in bad) assertFailsWith<JsonParseException>("debería fallar: $s") { JsonParser.parse(s) }
        assertFailsWith<JsonParseException> { JsonParser.parse("[".repeat(1000)) }
    }

    @Test
    fun equalityAndBuilders() {
        val a = JsonObject.of("x" to 1, "y" to listOf(1, "a"), "z" to mapOf("k" to null))
        val b = JsonParser.parse("""{"x":1,"y":[1,"a"],"z":{"k":null}}""")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(JsonNumber.of(5) != JsonNumber.of(5.0))
        assertEquals(JsonNumber.of(5L), JsonNumber.of(5))
        assertEquals("""{"x":2,"y":[1,"a"],"z":{"k":null},"n":"v"}""", a.with("x", 2).with("n", "v").toJson())
    }
}
