package io.github.jonjonesbr.jangada

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Base das telas do app (exceto o terminal, que é sempre escuro): aplica o
 * tema escolhido nas configurações — claro, escuro ou o do sistema.
 */
open class JangadaActivity : ComponentActivity() {

    private var createdWithTheme = themeGeneration

    /**
     * Largura máxima do conteúdo, em dp (0 = sem limite). Em tela larga (tablet, celular deitado) o conteúdo
     * fica centralizado nessa largura, em vez de esticar de ponta a ponta; abaixo dela nada muda.
     */
    protected open val maxContentWidthDp: Int = 720

    /** Largura útil do conteúdo em pixels: a da tela, limitada por [maxContentWidthDp]. */
    internal fun contentWidthPx(): Int {
        val screen = resources.displayMetrics.widthPixels
        val cap = (maxContentWidthDp * resources.displayMetrics.density).toInt()
        return if (cap > 0) minOf(screen, cap) else screen
    }

    override fun setContentView(layoutResID: Int) {
        val frame = CenteredFrame()
        layoutInflater.inflate(layoutResID, frame, true)
        super.setContentView(frame)
    }

    override fun setContentView(view: View?) {
        val frame = CenteredFrame()
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        super.setContentView(frame)
    }

    /** Moldura que centraliza o filho quando a tela é mais larga que [maxContentWidthDp] (margens iguais dos dois lados). */
    private inner class CenteredFrame : FrameLayout(this@JangadaActivity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val side = ((MeasureSpec.getSize(widthMeasureSpec) - contentWidthPx()) / 2).coerceAtLeast(0)
            for (i in 0 until childCount) {
                (getChildAt(i).layoutParams as? MarginLayoutParams)?.let {
                    it.marginStart = side
                    it.marginEnd = side
                    it.leftMargin = side
                    it.rightMargin = side
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onResume() {
        super.onResume()
        // O tema mudou em outra tela (configurações): redesenha esta também.
        if (createdWithTheme != themeGeneration) {
            createdWithTheme = themeGeneration
            recreate()
        }
    }

    /**
     * Tema do sistema: as telas declaram uiMode em configChanges (não são recriadas sozinhas), então a
     * troca claro/escuro do Android com a tela aberta a redesenha aqui. Com tema fixo, nada muda.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val theme = AppSettings(this).appTheme
        if (theme == AppSettings.THEME_LIGHT || theme == AppSettings.THEME_DARK) return
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night != createdNight) {
            createdNight = night
            recreate()
        }
    }

    /** Modo noturno com que a tela foi desenhada (ver [onConfigurationChanged]). */
    private var createdNight = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    override fun attachBaseContext(newBase: Context) {
        val night = when (AppSettings(newBase).appTheme) {
            AppSettings.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AppSettings.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> null
        }
        if (night == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    companion object {
        /** Muda sempre que o usuário troca o tema; telas abertas se atualizam ao voltar. */
        @Volatile var themeGeneration = 0
    }
}
