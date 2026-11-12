package io.github.jonjonesbr.jangada.ui

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jonjonesbr.jangada.AboutActivity
import io.github.jonjonesbr.jangada.ApiKeysActivity
import io.github.jonjonesbr.jangada.HomeActivity
import io.github.jonjonesbr.jangada.SettingsActivity
import io.github.jonjonesbr.jangada.ShareActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Telas que não precisam do Linux: abrem e não caem. Um travamento derruba o processo e o teste falha. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun telasAbremSemTravar() {
        ActivityScenario.launch(AboutActivity::class.java).use {
            assertEquals(Lifecycle.State.RESUMED, it.state)
        }
        ActivityScenario.launch(ApiKeysActivity::class.java).use {
            assertEquals(Lifecycle.State.RESUMED, it.state)
        }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            assertEquals(Lifecycle.State.RESUMED, it.state)
        }
    }

    /** Sem Linux instalado (emulador x86_64), a tela inicial manda para a SetupActivity; só garante que nada cai. */
    @Test
    fun telaInicialSemLinuxNaoTrava() {
        ActivityScenario.launch(HomeActivity::class.java).use { Thread.sleep(2000) }
    }

    /** Antes da correção, um extra de tipo errado lançava ClassCastException e derrubava o app. */
    @Test
    fun compartilharComExtraErradoNaoDerruba() {
        val simples = Intent(context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("*/*")
            .putExtra(Intent.EXTRA_STREAM, "não é um endereço")
        ActivityScenario.launch<ShareActivity>(simples).use { Thread.sleep(1500) }

        val varios = Intent(context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putStringArrayListExtra(Intent.EXTRA_STREAM, arrayListOf("a", "b"))
        ActivityScenario.launch<ShareActivity>(varios).use { Thread.sleep(1500) }
    }
}
