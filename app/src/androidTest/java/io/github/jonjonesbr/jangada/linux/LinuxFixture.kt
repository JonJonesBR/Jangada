package io.github.jonjonesbr.jangada.linux

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instala o Linux do app no emulador uma vez para todos os testes (os testes rodam num processo só).
 * Cada classe que precisa dele chama [ready] no @BeforeClass. O Docker Hub anônimo às vezes recusa
 * (429) nos IPs do GitHub: até 3 tentativas, com pausa.
 */
object LinuxFixture {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val ready: Unit by lazy { install() }

    /** Linux instalado e os agentes falsos (assets/fakes do teste) em /usr/local/bin, no PATH do Debian. */
    val withFakes: Unit by lazy {
        ready
        installFakes()
    }

    /** Pasta do Debian (o rootfs) vista pelo app. */
    val rootfs: File get() = LinuxEnv(context).rootfs

    /** Log do agente falso [name] (o que ele recebeu do app), ou "" se ainda não existe. */
    fun fakeLog(name: String): String = File(rootfs, "var/log/jangada-fake/$name.log").let { if (it.exists()) it.readText() else "" }

    /** Apaga os logs dos falsos (cada teste começa do zero). */
    fun clearFakeLogs() {
        File(rootfs, "var/log/jangada-fake").listFiles()?.forEach { it.delete() }
    }

    private fun installFakes() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        for (name in assets.list("fakes").orEmpty()) {
            val target = File(bin, name)
            assets.open("fakes/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            assertTrue("não deu para tornar $name executável", target.setExecutable(true, false))
        }
        File(rootfs, "var/log/jangada-fake").mkdirs()
    }

    private fun install() {
        assertTrue("o Linux do app não é suportado neste aparelho", LinuxEnv(context).isDeviceSupported)
        var lastLog = ""
        repeat(ATTEMPTS) { attempt ->
            if (LinuxEnv(context).isReady) return
            if (attempt > 0) Thread.sleep(RETRY_PAUSE_MS)
            val latch = CountDownLatch(1)
            val listener: (Setup.State) -> Unit = { state ->
                if (state is Setup.State.Done || state is Setup.State.Failed) latch.countDown()
            }
            // Começa antes de ouvir: uma falha antiga (de outra tentativa) não conta como fim desta.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                Setup.reset()
                Setup.start(context)
            }
            Setup.addListener(listener)
            try {
                latch.await(INSTALL_TIMEOUT_MIN, TimeUnit.MINUTES)
            } finally {
                Setup.removeListener(listener)
            }
            // Só o fim do registro da instalação (o emulador é limpo: não há dado de ninguém).
            lastLog = Setup.logText().takeLast(LOG_TAIL)
        }
        if (!LinuxEnv(context).isReady) fail("o Linux não instalou depois de $ATTEMPTS tentativas:\n$lastLog")
    }

    private const val ATTEMPTS = 3
    private const val RETRY_PAUSE_MS = 20_000L
    private const val INSTALL_TIMEOUT_MIN = 15L
    private const val LOG_TAIL = 4000
}
