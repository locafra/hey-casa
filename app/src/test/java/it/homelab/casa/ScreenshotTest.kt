package it.homelab.casa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Disegna la schermata principale in docs/screenshots: inglese e italiano, tema chiaro e scuro.
 * `./gradlew testPubblicaReleaseUnitTest`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

  @Test @Config(sdk = [35], qualifiers = "en-w412dp-h915dp-notnight-420dpi") fun light() = shoot("light")

  @Test @Config(sdk = [35], qualifiers = "en-w412dp-h915dp-night-420dpi") fun dark() = shoot("dark")

  @Test @Config(sdk = [35], qualifiers = "it-w412dp-h915dp-notnight-420dpi") fun chiaro() = shoot("chiaro")

  @Test @Config(sdk = [35], qualifiers = "it-w412dp-h915dp-night-420dpi") fun scuro() = shoot("scuro")

  private fun shoot(name: String) {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val app = activity.application as CasaApp
    // Qualche riga di registro di esempio: senza occhiali resterebbe quasi vuoto
    val italian = app.getString(R.string.lang) == "it"
    app.log(app.getString(R.string.log_listen_start, "hey-meta"))
    app.log(app.getString(R.string.log_command, if (italian) "Spegni luce studio." else "Turn off the study light."))
    app.log(app.getString(R.string.log_reply, if (italian) "Ho spento luce studio" else "Turned off study light"))
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    val scroll = findScroll(activity.window.decorView)!!
    val content = scroll.getChildAt(0)
    val w = scroll.width
    content.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    content.layout(0, 0, w, content.measuredHeight)
    val bitmap = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(activity.getColor(R.color.fondo))
    content.draw(canvas)
    val out = File("../docs/screenshots/$name.png")
    out.parentFile!!.mkdirs()
    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun findScroll(v: View): ScrollView? {
    if (v is ScrollView) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
    return null
  }
}
