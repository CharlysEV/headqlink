package com.headqlink.link

import android.app.Application
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Dibuja en el PC el widget en cada estado a 4x2 y 2x2 (WidgetShots: widget_<estado>_<tamaño>.png, 3x), con las mismas
 * RemoteViews que pinta el launcher:
 *   gradlew :app:testGithubDebugUnitTest --tests "*WidgetRender*" -Ppreview [-PpreviewSuffix=_despues]
 * Deja los PNG en app/build/preview. Sin -Ppreview se salta. En el móvil, PreviewActivity (--es render widget).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-xxhdpi")
class WidgetRenderTest {
    @Test
    fun renderWidgets() {
        assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        val suffix = System.getProperty("hql.preview.suffix") ?: ""
        dir.mkdirs()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val ctx: Application = RuntimeEnvironment.getApplication()
        val only = (System.getProperty("hql.preview.only") ?: "").split(",").filter { it.isNotBlank() }
        for (shot in WidgetShots.all()) {
            if (only.isNotEmpty() && only.none { it == shot.name || it == "widget" }) continue
            WidgetShots.save(WidgetShots.render(ctx, shot), File(dir, shot.name + suffix + ".png"))
        }
    }
}
