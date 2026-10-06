package com.headqlink.link

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * El widget de verdad: las RemoteViews de LinkWidgetViews aplicadas como lo hace el launcher, en cada estado de
 * demostración y tamaño, con sus textos (en español), los colores del selector y los toques puestos.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES")
class LinkWidgetViewsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    private fun view(state: String, size: LinkGlance.Size): View =
        LinkWidgetViews.build(ctx, WidgetShots.glance(ctx, state), size).apply(ctx, FrameLayout(ctx))

    private fun text(v: View, id: Int) = v.findViewById<TextView>(id).text.toString()

    private fun color(v: View, id: Int) = v.findViewById<TextView>(id).currentTextColor

    @Test
    fun everyStateAndSizeInflatesWithItsTexts() {
        for (shot in WidgetShots.all()) {
            val g = WidgetShots.glance(ctx, shot.state)
            val v = view(shot.state, shot.size)
            assertEquals(shot.name, LinkWidgetViews.title(ctx, g), text(v, R.id.hql_w_title))
            assertTrue(shot.name, v.findViewById<View>(R.id.hql_w_power).hasOnClickListeners())
            assertNotNull(shot.name, v.findViewById<View>(R.id.hql_w_power).contentDescription)
        }
    }

    @Test
    fun offAt4x2() {
        val v = view("apagado", LinkGlance.Size.WIDE)
        assertEquals("Apagado", text(v, R.id.hql_w_title))
        assertEquals("Toca para conectar", text(v, R.id.hql_w_detail))
        assertEquals("Conectar", text(v, R.id.hql_w_action))
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.hql_w_header).visibility)
        // Selector: las tres conexiones, con la elegida (zona Wi-Fi) en cian.
        assertEquals("Zona Wi-Fi", text(v, R.id.hql_w_link_hotspot))
        assertEquals("Wi-Fi Direct", text(v, R.id.hql_w_link_p2p))
        assertEquals("Cable USB", text(v, R.id.hql_w_link_usb))
        assertEquals(ctx.getColor(R.color.hql_accent), color(v, R.id.hql_w_link_hotspot))
        assertEquals(ctx.getColor(R.color.hql_text_dim), color(v, R.id.hql_w_link_usb))
        assertEquals("Zona Wi-Fi, opción elegida", v.findViewById<View>(R.id.hql_w_link_hotspot).contentDescription)
        // Modo: Auto elegido; el chip del extendido, corto, con su nombre completo para la accesibilidad.
        assertEquals(ctx.getColor(R.color.hql_accent), color(v, R.id.hql_w_mode_aa))
        assertEquals(ctx.getColor(R.color.hql_text_dim), color(v, R.id.hql_w_mode_ext))
        assertEquals("Extendido", text(v, R.id.hql_w_mode_ext))
        assertEquals("Auto extendido", v.findViewById<View>(R.id.hql_w_mode_ext).contentDescription)
        assertEquals("Auto, opción elegida", v.findViewById<View>(R.id.hql_w_mode_aa).contentDescription)
        for (id in intArrayOf(R.id.hql_w_link_hotspot, R.id.hql_w_link_p2p, R.id.hql_w_link_usb, R.id.hql_w_mode_aa,
            R.id.hql_w_mode_ext, R.id.hql_w_brand)) {
            assertTrue(v.findViewById<View>(id).hasOnClickListeners())
        }
    }

    @Test
    fun connectedByWifiIsGreenWithTheFigures() {
        val v = view("conectado_wifi", LinkGlance.Size.WIDE)
        assertEquals("Coche conectado", text(v, R.id.hql_w_title))
        assertEquals("30 fps · 4,8 Mbit/s", text(v, R.id.hql_w_detail))
        assertEquals("Desconectar", text(v, R.id.hql_w_action))
        assertEquals(ctx.getColor(R.color.hql_ok), color(v, R.id.hql_w_title))
        assertEquals("Coche conectado · 30 fps · 4,8 Mbit/s", LinkWidgetViews.oneLine(ctx, WidgetShots.glance(ctx, "conectado_wifi")))
    }

    @Test
    fun connectedByCableMarksTheCable() {
        val v = view("conectado_usb", LinkGlance.Size.WIDE)
        assertEquals(ctx.getColor(R.color.hql_accent), color(v, R.id.hql_w_link_usb))
        assertEquals(ctx.getColor(R.color.hql_text_dim), color(v, R.id.hql_w_link_hotspot))
        // 2x2: sin selector; el icono de la conexión, que se toca para cambiarla.
        val c = view("conectado_usb", LinkGlance.Size.COMPACT)
        assertNull(c.findViewById<View>(R.id.hql_w_link_hotspot))
        assertNull(c.findViewById<View>(R.id.hql_w_mode_aa))
        val cycle = c.findViewById<View>(R.id.hql_w_link_cycle)
        assertTrue(cycle.hasOnClickListeners())
        assertEquals("Conexión: Cable USB. Toca para cambiarla", cycle.contentDescription)
        assertEquals("Coche conectado", text(c, R.id.hql_w_title))
    }

    @Test
    fun shortSizesHideWhatDoesNotFitAndProblemsAreRed() {
        val v = view("problema", LinkGlance.Size.WIDE_SHORT)
        assertEquals(View.GONE, v.findViewById<View>(R.id.hql_w_header).visibility)
        assertEquals(View.GONE, v.findViewById<View>(R.id.hql_w_action).visibility)
        assertEquals(1, v.findViewById<TextView>(R.id.hql_w_title).maxLines)
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.hql_w_detail).visibility)
        // 2x2 muy bajo: sin detalle.
        val c = view("conectado_wifi", LinkGlance.Size.COMPACT_SHORT)
        assertEquals(View.GONE, c.findViewById<View>(R.id.hql_w_detail).visibility)
        assertEquals("Coche conectado", text(c, R.id.hql_w_title))
        assertEquals(2, view("esperando", LinkGlance.Size.COMPACT).findViewById<TextView>(R.id.hql_w_title).maxLines)
        assertEquals("Cierra QDLink", text(v, R.id.hql_w_title))
        assertEquals("El puerto 18463 está ocupado", text(v, R.id.hql_w_detail))
        assertEquals(ctx.getColor(R.color.hql_error), color(v, R.id.hql_w_title))
        val w = view("esperando", LinkGlance.Size.WIDE)
        assertEquals("Esperando a que vuelva el coche", text(w, R.id.hql_w_title))
        assertEquals("Android Auto en pausa", text(w, R.id.hql_w_detail))
        assertEquals(ctx.getColor(R.color.hql_warn), color(w, R.id.hql_w_title))
        val s = view("buscando", LinkGlance.Size.WIDE)
        assertEquals("Buscando el coche…", text(s, R.id.hql_w_title))
        assertEquals("Zona Wi-Fi activa (swlan0 10.42.0.1)", text(s, R.id.hql_w_detail))
    }

    @Test
    fun thePickerPreviewIsTheWholeWidgetFittedIntoAnySlot() {
        // El proveedor: la imagen del widget de verdad a 4x2 y una disposición que la encaja entera. Con la disposición de
        // verdad, el diálogo de Samsung «¿Quieres añadirlo a la pantalla Inicio?», más bajo que un 4x2, la recortaba a la
        // cabecera.
        val ns = "http://schemas.android.com/apk/res/android"
        val xml = ctx.resources.getXml(R.xml.hql_widget_info)
        var previewLayout = 0
        var previewImage = 0
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "appwidget-provider") {
                previewLayout = xml.getAttributeResourceValue(ns, "previewLayout", 0)
                previewImage = xml.getAttributeResourceValue(ns, "previewImage", 0)
            }
        }
        assertEquals(R.layout.hql_widget_preview, previewLayout)
        assertEquals(R.drawable.hql_widget_preview, previewImage)
        // La imagen: el 4x2 entero (340 x 180 dp), en su proporción.
        val d = ctx.getDrawable(R.drawable.hql_widget_preview)!!
        assertEquals(WidgetShots.WIDE_W * d.intrinsicHeight, WidgetShots.WIDE_H * d.intrinsicWidth)
        // La disposición: solo esa imagen, en todo el hueco y encajada sin recortar, sea el hueco que sea.
        val v = android.widget.RemoteViews(ctx.packageName, R.layout.hql_widget_preview).apply(ctx, FrameLayout(ctx))
        val img = (v as android.view.ViewGroup).getChildAt(0) as android.widget.ImageView
        assertEquals(1, v.childCount)
        assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, img.scaleType)
        for ((w, h) in listOf(900 to 150, 1020 to 540, 600 to 900)) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            assertEquals("${w}x$h", w, img.width)
            assertEquals("${w}x$h", h, img.height)
            val r = android.graphics.RectF(0f, 0f, img.drawable.intrinsicWidth.toFloat(), img.drawable.intrinsicHeight.toFloat())
            img.imageMatrix.mapRect(r)
            // Entera dentro del hueco y tocando sus bordes por un lado (la mayor posible).
            assertTrue("${w}x$h: $r", r.left >= -0.5f && r.top >= -0.5f && r.right <= w + 0.5f && r.bottom <= h + 0.5f)
            assertTrue("${w}x$h: $r", Math.abs(r.width() - w) < 1f || Math.abs(r.height() - h) < 1f)
        }
    }
}
