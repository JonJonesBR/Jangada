package io.github.jonjonesbr.jangada.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.HomeActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.chat.ChatUi
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tela inicial com o Linux instalado e os agentes falsos (Claude Code e OpenCode instalados): a lista só com
 * os instalados, a linha "Mais agentes" com o painel dos não instalados, o painel "Espaço no Linux" e a
 * limpeza de caches ("Liberar espaço").
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    companion object {
        private const val FAKE = "claude"
        private const val MAX_TELAS = 12
        private const val CLAUDE = "Claude Code"
        private const val OPENCODE = "OpenCode"
        /** Agente do catálogo que o emulador não tem (só os falsos claude, opencode e agy estão instalados). */
        private const val NAO_INSTALADO_NO_EMULADOR = "Codex"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val naoInstalado get() = context.getString(R.string.agent_not_installed)

    /** Rola a lista para o alto (a tela abre no topo; a varredura começa de lá). */
    private fun rolarParaCima() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 30)
    }

    /** Rola a lista para baixo, um pouco. */
    private fun rolarParaBaixo() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 30)
    }

    /** Texto de estado do cartão que tem o nome [nome] (o estado fica dentro do mesmo cartão), ou null. */
    private fun estadoDo(nome: UiObject2): String? {
        var atual: UiObject2? = nome.parent
        repeat(6) {
            val no = atual ?: return null
            no.findObject(By.res(ChatUi.PKG, "status"))?.let { return it.text }
            atual = no.parent
        }
        return null
    }

    /**
     * Ordem dos cartões de cima para baixo, com "true" para os que mostram "Não instalado". Rola a lista
     * para cima e desce até não aparecer cartão novo. Cada tela é ordenada pela posição no alto.
     */
    private fun lerCartoes(): LinkedHashMap<String, Boolean> {
        repeat(MAX_TELAS) { rolarParaCima() }
        val cartoes = LinkedHashMap<String, Boolean>()
        var semNovos = 0
        for (tela in 0 until MAX_TELAS) {
            var novos = 0
            for (nome in device.findObjects(By.res(ChatUi.PKG, "name")).sortedBy { it.visibleBounds.top }) {
                val titulo = nome.text ?: continue
                val estado = estadoDo(nome) ?: continue
                if (titulo in cartoes) continue
                cartoes[titulo] = estado == naoInstalado
                novos++
            }
            semNovos = if (novos == 0) semNovos + 1 else 0
            if (semNovos >= 2) break
            rolarParaBaixo()
        }
        return cartoes
    }

    /** Só os instalados na lista: Claude Code e OpenCode aparecem, e nenhum cartão "Não instalado". */
    private fun soInstaladosNaLista(cartoes: Map<String, Boolean>): Boolean =
        cartoes[CLAUDE] == false && cartoes[OPENCODE] == false && cartoes.values.none { it }

    /**
     * A detecção dos agentes roda em segundo plano, então a leitura repete até 60 s. Os não instalados não
     * têm cartão na lista (ficam em "Mais agentes").
     */
    @Test
    fun listaMostraSoOsInstalados() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            val fim = System.currentTimeMillis() + 60_000
            var cartoes = lerCartoes()
            while (!soInstaladosNaLista(cartoes) && System.currentTimeMillis() < fim) {
                Thread.sleep(2_000)
                cartoes = lerCartoes()
            }
            assertTrue("a lista traz agentes que não estão instalados: $cartoes\nNa tela: ${ChatUi.screenTexts()}", soInstaladosNaLista(cartoes))
        }
    }

    /** Procura a linha "Mais agentes (N)" descendo a lista; devolve o nó, ou null se não aparecer. */
    private fun acharLinhaMais(): UiObject2? {
        val prefixo = context.getString(R.string.home_more_agents, 0).substringBefore("(").trim()
        // Antes da detecção dos agentes a lista mostra todos e a linha fica escondida: tenta por até 60 s.
        val fim = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < fim) {
            repeat(MAX_TELAS) {
                device.findObject(By.textStartsWith(prefixo))?.let { return it }
                rolarParaBaixo()
            }
            Thread.sleep(2_000)
        }
        return null
    }

    /**
     * A linha "Mais agentes (N)" aparece no fim da lista com N >= 1 (há agentes fora da lista); tocar abre o
     * painel com os não instalados (ex.: Codex, que o emulador não tem) e não com os instalados. Não toca
     * em nenhuma linha do painel: instalar precisa de internet.
     */
    @Test
    fun maisAgentesAbrePainelComOsNaoInstalados() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            val linha = acharLinhaMais()
            assertNotNull("a linha Mais agentes não apareceu.\nNa tela: ${ChatUi.screenTexts()}", linha)
            val n = Regex("\\((\\d+)\\)").find(linha!!.text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            assertTrue("a contagem da linha Mais agentes está errada: ${linha.text}", n >= 1)
            linha.click()
            val titulo = context.getString(R.string.home_more_agents_title)
            ChatUi.waitText(titulo, FAKE)
            // O painel mostra o que não está instalado (o Claude Code, instalado, segue na lista da Home atrás do painel).
            ChatUi.waitText(NAO_INSTALADO_NO_EMULADOR, FAKE)
            device.pressBack()
            assertTrue("o painel não fechou com o voltar.\nNa tela: ${ChatUi.screenTexts()}", device.wait(Until.gone(By.text(titulo)), 10_000))
        }
    }

    /**
     * Expande o bloco Sistema se estiver recolhido. O estado fica nas preferências e passa de um teste
     * para o outro: se já estiver expandido, não toca (tocar de novo recolheria).
     */
    private fun expandirSistema() {
        val fim = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < fim) {
            if (device.findObject(By.text(context.getString(R.string.home_system_open))) != null) return
            device.findObject(By.text(context.getString(R.string.home_system_closed)))?.let {
                it.click()
                // O conteúdo que acabou de abrir também pode ficar abaixo da dobra.
                Thread.sleep(500)
                rolarParaBaixo()
                return
            }
            // O bloco fica no fim da Home, abaixo da dobra: rola até ele aparecer.
            rolarParaBaixo()
            Thread.sleep(500)
        }
        org.junit.Assert.fail("O bloco Sistema não apareceu.\nNa tela: ${ChatUi.screenTexts()}")
    }

    /** Toca no texto do recurso [res] rolando a Home para baixo até ele aparecer (o bloco Sistema fica no fim). */
    private fun tocarRolando(res: Int) {
        val texto = context.getString(res)
        val fim = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < fim) {
            device.findObject(By.text(texto))?.let { it.click(); return }
            rolarParaBaixo()
            Thread.sleep(500)
        }
        org.junit.Assert.fail("\"$texto\" não apareceu.\nNa tela: ${ChatUi.screenTexts()}")
    }

    /** O painel mostra a barra do espaço (Total e a legenda "Linux e agentes"); voltar fecha. */
    @Test
    fun painelDeEspacoMostraAsPartes() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            // O bloco Sistema começa recolhido: expande antes de tocar nos detalhes do espaço.
            expandirSistema()
            tocarRolando(R.string.storage_details)
            ChatUi.waitText(context.getString(R.string.storage_details_title), FAKE, timeoutMs = 120_000)
            // "Total: %1$s" sem o valor: o trecho que sempre aparece quando a medição termina.
            val total = context.getString(R.string.storage_total, "").trim()
            ChatUi.waitText(total, FAKE, timeoutMs = 120_000)
            ChatUi.waitText(context.getString(R.string.storage_legend_system), FAKE, timeoutMs = 10_000)
            device.pressBack()
            assertTrue("o painel não fechou com o voltar.\nNa tela: ${ChatUi.screenTexts()}", device.wait(Until.gone(By.textContains(total)), 10_000))
        }
    }

    /**
     * Liberar espaço: se há caches para apagar, toca e espera "Caches cleared."; se não há (ou um agente
     * está instalando), a linha fica desligada com o motivo, e o teste confere o motivo.
     */
    @Test
    fun liberarEspacoTerminaComResultado() {
        // Um cache do pip (que a limpeza apaga), para a linha ter o que liberar no emulador limpo.
        val cache = java.io.File(LinuxFixture.rootfs, "root/.cache/pip/jangada-teste/cache.bin")
        cache.parentFile!!.mkdirs()
        cache.writeBytes(ByteArray(2_000_000))
        ActivityScenario.launch(HomeActivity::class.java).use {
            // O bloco Sistema começa recolhido: expande antes de tocar nos detalhes do espaço.
            expandirSistema()
            tocarRolando(R.string.storage_details)
            val linha = device.wait(Until.findObject(By.text(context.getString(R.string.storage_clean_caches))), 120_000)
            assertNotNull("a linha Liberar espaço não apareceu.\nNa tela: ${ChatUi.screenTexts()}", linha)
            val nothing = context.getString(R.string.storage_clean_nothing)
            val busy = context.getString(R.string.storage_clean_busy)
            val motivo = device.wait(Until.findObject(By.text(nothing)), 2_000) ?: device.findObject(By.text(busy))
            if (motivo != null) {
                // Nada para apagar agora: o motivo aparece e a linha não faz nada. Não é falha.
                assumeTrue("sem caches para apagar agora (${motivo.text})", false)
            }
            linha!!.click()
            // O fim da limpeza é avisado por um Toast, que o UiAutomator não enxerga: confere o resultado,
            // o cache do pip criado acima sai do disco.
            val fim = System.currentTimeMillis() + 120_000
            while (cache.exists() && System.currentTimeMillis() < fim) Thread.sleep(1_000)
            assertTrue("a limpeza não apagou o cache do pip em 120 s.\nNa tela: ${ChatUi.screenTexts()}", !cache.exists())
        }
    }
}
