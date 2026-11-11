package io.github.jonjonesbr.jangada

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Base das telas do app (exceto o terminal, que é sempre escuro): aplica o
 * tema escolhido nas configurações — claro, escuro ou o do sistema.
 */
open class JangadaActivity : ComponentActivity() {

    private var createdWithTheme = themeGeneration

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
