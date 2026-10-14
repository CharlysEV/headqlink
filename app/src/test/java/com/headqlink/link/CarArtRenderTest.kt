package com.headqlink.link

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Captura del T03 visto desde arriba (CarArt.topT03) en sus tres colores, tal como lo pinta Android:
 *   gradlew :app:testGithubDebugUnitTest --tests "*CarArtRender*" -Ppreview
 * Deja app/build/preview/t03_arriba_<color>.png. Sin -Ppreview se salta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CarArtRenderTest {
    @Test
    fun renderT03FromAbove() {
        assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        val names = listOf("blanco", "azul", "gris")
        for ((i, paint) in CarArt.T03_PAINTS.withIndex()) {
            // El mismo encuadre que la pestaña Estado: alto 420, ancho 46 % del alto más un 10 % de margen por lado.
            val h = 420
            val w = Math.round(h * 0.46f / 0.8f)
            val b = Bitmap.createBitmap(w * 3, h * 3, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(3f, 3f)
            c.drawColor(0xFF101620.toInt())
            val pad = w * 0.1f
            CarArt.topT03(c, RectF(pad, 4f, w - pad, h - 4f), paint, Paint(Paint.ANTI_ALIAS_FLAG))
            FileOutputStream(File(dir, "t03_arriba_${names[i]}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
