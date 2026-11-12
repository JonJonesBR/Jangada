package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jonjonesbr.jangada.AppSettings
import io.github.jonjonesbr.jangada.JangadaActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Barra de cima das telas: legível nos dois temas e redesenhada quando o Android troca claro/escuro. */
@RunWith(AndroidJUnit4::class)
class ThemeBarTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setTheme(theme: String) {
        AppSettings(context).appTheme = theme
        JangadaActivity.themeGeneration++
    }

    /** Roda um comando de shell e espera ele terminar (lendo a saída até o fim). */
    private fun shell(cmd: String) {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Devolve (texto, fundo) da barra de cima de uma tela de ajustes. */
    private fun barColors(activity: Activity): Pair<Int, Int> {
        val back = activity.findViewById<TextView>(R.id.settings_back)
        val bar = back.parent as View
        val fundo = (bar.background as ColorDrawable).color
        return Pair(back.currentTextColor, fundo)
    }

    private fun barBg(scenario: ActivityScenario<SettingsActivity>): Int {
        var bg = 0
        scenario.onActivity { bg = barColors(it).second }
        return bg
    }

    private val nightBarBg: Int
        get() {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            return context.createConfigurationContext(config).getColor(R.color.bar_bg)
        }

    @Test
    fun barraLegivelNoTemaClaro() {
        setTheme(AppSettings.THEME_LIGHT)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            it.onActivity { a ->
                val (fg, bg) = barColors(a)
                assertTrue("contraste ${Contrast.ratio(fg, bg)}", Contrast.ratio(fg, bg) >= 4.5)
            }
        }
    }

    @Test
    fun barraLegivelNoTemaEscuro() {
        setTheme(AppSettings.THEME_DARK)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            it.onActivity { a ->
                val (fg, bg) = barColors(a)
                assertTrue("contraste ${Contrast.ratio(fg, bg)}", Contrast.ratio(fg, bg) >= 4.5)
            }
        }
    }

    @Test
    fun temaDoSistemaRedesenhaAoTrocar() {
        setTheme(AppSettings.THEME_SYSTEM)
        shell("cmd uimode night no")
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                val antes = barBg(scenario)
                shell("cmd uimode night yes")
                var depois = antes
                val limite = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < limite) {
                    depois = barBg(scenario)
                    if (depois != antes) break
                    Thread.sleep(250)
                }
                assertTrue("o fundo da barra não mudou ao trocar para o tema escuro", depois != antes)
                assertEquals(nightBarBg, depois)
            }
        } finally {
            shell("cmd uimode night no")
        }
    }

    @After
    fun restaurarTema() {
        setTheme(AppSettings.THEME_SYSTEM)
    }
}
