package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.Locale

/**
 * Dibuja en el PC las pantallas del móvil (como CarPanelRenderTest con las del coche), para revisar el diseño sin el
 * móvil: gradlew :app:testGithubDebugUnitTest --tests "*PhoneScreensRender*" -Ppreview. PNG de 1080x2340 en
 * app/build/preview/movil_*.png. Sin -Ppreview se salta. Una pantalla que no arranque en Robolectric se salta sola.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-w411dp-h891dp-port-xxhdpi")
class PhoneScreensRenderTest {
    @Test
    fun renderPhoneScreens() {
        assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Str.init(app)
        // Con la configuración inicial hecha, Inicio no salta al asistente.
        Config(app).setSetupDone(true)
        val screens: List<Pair<String, Class<out Activity>>> = listOf(
            "movil_inicio" to HomeActivity::class.java,
            "movil_comprobacion" to ChecklistActivity::class.java,
            "movil_diagnostico" to LogActivity::class.java,
            "movil_cuenta" to CarCloudActivity::class.java,
            "movil_configuracion" to SetupActivity::class.java,
            "movil_casa" to HomeAssistantActivity::class.java,
        )
        for ((name, cls) in screens) {
            try {
                val act = Robolectric.buildActivity(cls).setup().get()
                act.setTheme(com.andrerinas.openheadunit.R.style.Theme_HeadQLink)
                idle(800)
                save(act.window.decorView, File(dir, "$name.png"))
                println("PhoneScreensRender: $name OK")
            } catch (e: Throwable) {
                println("PhoneScreensRender: $name no se pudo dibujar: $e")
            }
        }
    }

    private fun idle(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun save(v: View, f: File) {
        val w = 1080
        val h = 2340
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
