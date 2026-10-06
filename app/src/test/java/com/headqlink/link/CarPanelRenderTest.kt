package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertTrue
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
 * Dibuja en el PC los paneles del modo extendido con los datos de demostración, como PNG de 1920x882 (el tamaño de la
 * pantalla del C10), para ver los cambios sin coche ni móvil:
 *   gradlew :app:testGithubDebugUnitTest --tests "*CarPanelRender*" -Ppreview [-PpreviewSuffix=_despues]
 * Deja los PNG en app/build/preview. Sin -Ppreview se salta. Usa Robolectric con gráficos nativos (Skia de Android),
 * así que el resultado es muy parecido al del móvil; las capturas exactas, con PreviewActivity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-w1536dp-h705dp-land")
class CarPanelRenderTest {
    @Test
    fun renderCarPanels() {
        assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        val suffix = System.getProperty("hql.preview.suffix") ?: ""
        dir.mkdirs()
        // En español, como en el coche del usuario (números con coma decimal incluidos).
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        Str.init(act)
        assertTrue(DemoMode.enable(false, 0.0))
        val ui = CarUi(act, W, H, 320, 200) { }
        try {
            val container = FrameLayout(act)
            act.setContentView(container)
            val root = ui.attachOffscreen(act)
            container.addView(root, FrameLayout.LayoutParams(W, H))
            idle(500)
            val only = System.getProperty("hql.preview.only") ?: ""
            val wanted = only.split(",").filter { it.isNotBlank() }
            for (shot in PreviewShots.ALL) {
                // Por defecto, la sección Coche y Auto; las demás pantallas (red, galería…), solo si se piden.
                if (wanted.isEmpty() && !shot.name.startsWith("coche_") && shot.name != "auto") continue
                if (wanted.isNotEmpty() && !wanted.contains(shot.name)) continue
                DemoMode.applyState(shot.state)
                DemoMode.seek(shot.seekSec)
                ui.showForPreview(shot.screen)
                idle(shot.waitMs)
                save(root, File(dir, shot.name + suffix + ".png"))
            }
        } finally {
            ui.stop()
            idle(200)
            DemoMode.disable()
        }
    }

    private fun idle(ms: Long) {
        // A pasos: así corren los ticks de los paneles (cada 100-1000 ms) como en el móvil.
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun save(v: View, f: File) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val W = 1920
        const val H = 882
    }
}
