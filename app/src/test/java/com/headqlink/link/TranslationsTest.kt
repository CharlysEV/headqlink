package com.headqlink.link

import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Los 8 idiomas de la app: las mismas claves en todos, los mismos huecos (%1$d, %2$s…) que en inglés, y los plurales
 * con «one» y «other» (y «many» donde Android la pide: español, francés, italiano y portugués).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class TranslationsTest {
    private val langs = listOf("values", "values-es", "values-pt-rBR", "values-pt-rPT", "values-it", "values-fr", "values-de", "values-nl",
        "values-zh-rCN")
    /** Sin singular: un solo «other» (chino). */
    private val onlyOther = setOf("values-zh-rCN")
    private val withMany = setOf("values-es", "values-pt-rBR", "values-pt-rPT", "values-it", "values-fr")

    /** Huecos de formato; sin «% » (un % suelto en un texto sin argumentos) ni «%%». */
    private val spec = Regex("%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[a-zA-Z]")

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun elements(dir: String, file: String, tag: String): List<Element> {
        val f = File(res(), "$dir/$file")
        if (!f.isFile) return emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.documentElement.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun specs(s: String) = spec.findAll(s).map { it.value }.sorted().toList()

    private fun strings(dir: String): Map<String, String> =
        listOf("hql_strings.xml", "hql_cloud_strings.xml", "hql_strings_i18n.xml")
            .flatMap { elements(dir, it, "string") }.associate { it.getAttribute("name") to it.textContent }

    /** nombre → (cantidad → texto) */
    private fun plurals(dir: String): Map<String, Map<String, String>> =
        elements(dir, "hql_plurals.xml", "plurals").associate { p ->
            val items = p.getElementsByTagName("item")
            p.getAttribute("name") to (0 until items.length).map { items.item(it) as Element }
                .associate { it.getAttribute("quantity") to it.textContent }
        }

    @Test
    fun everyLanguageHasTheSameStringsAndPlaceholders() {
        val en = strings("values")
        assertTrue(en.size > 1000)
        for (d in langs.drop(1)) {
            val m = strings(d)
            assertEquals("$d: claves", en.keys.sorted(), m.keys.sorted())
            for ((k, v) in en) assertEquals("$d/$k", specs(v), specs(m.getValue(k)))
        }
    }

    @Test
    fun pluralsInAllLanguagesWithOneAndOther() {
        val en = plurals("values")
        assertTrue(en.containsKey("hql_trips_total") && en.containsKey("hql_plan_summary"))
        for (d in langs) {
            val m = plurals(d)
            assertEquals("$d: plurales", en.keys.sorted(), m.keys.sorted())
            for ((k, q) in m) {
                if (d !in onlyOther) assertTrue("$d/$k sin one", q.containsKey("one"))
                assertTrue("$d/$k sin other", q.containsKey("other"))
                if (d in withMany) assertTrue("$d/$k sin many", q.containsKey("many"))
                // «other» con los mismos huecos que en inglés; «one» puede decir «un cargador» sin la cifra.
                assertEquals("$d/$k", specs(en.getValue(k).getValue("other")), specs(q.getValue("other")))
                q["one"]?.let { assertTrue("$d/$k one", specs(q.getValue("other")).containsAll(specs(it))) }
                q["many"]?.let { assertEquals("$d/$k many", specs(q.getValue("other")), specs(it)) }
            }
        }
    }

    @Test
    fun singularAndPluralInEnglish() {
        Str.init(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals("1 trip", Str.plural(R.plurals.hql_ins_n_trips, 1))
        assertEquals("3 trips", Str.plural(R.plurals.hql_ins_n_trips, 3))
        assertEquals("trip", Str.plural(R.plurals.hql_ins_trips, 1))
        assertEquals("1 stop · 25 min charging · arrive with 40 %",
            Str.plural(R.plurals.hql_plan_summary, 1, 1, "25 min", 40.0))
        assertEquals("12 ahead · 1 matches the filter", Str.plural(R.plurals.hql_chargers_list_count, 1, 12, 1))
        assertEquals("1 trip · 42 km · ~€3.10", Str.plural(R.plurals.hql_trips_total, 1, 1, 42.0, Money.format(3.1)))
    }

    @Test
    @RoboConfig(qualifiers = "es")
    fun singularAndPluralInSpanish() {
        Str.init(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals("1 viaje", Str.plural(R.plurals.hql_ins_n_trips, 1))
        assertEquals("2 viajes", Str.plural(R.plurals.hql_ins_n_trips, 2))
        assertEquals("1 parada", Str.plural(R.plurals.hql_ins_stops, 1))
        assertEquals("Consejo: a 110 km/h, 1 parada en vez de 2 (5 min más conduciendo, 9 min menos cargando).",
            Str.plural(R.plurals.hql_speed_advice_stops, 1, 110, 1, 2, "5 min", "9 min"))
        val total = Str.plural(R.plurals.hql_trips_total, 1, 1, 42.0, Money.format(3.1)).replace(' ', ' ')
        assertEquals("1 viaje · 42 km · ~3,10 €", total)
    }
}
