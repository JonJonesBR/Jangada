package io.github.jonjonesbr.jangada.linux

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
