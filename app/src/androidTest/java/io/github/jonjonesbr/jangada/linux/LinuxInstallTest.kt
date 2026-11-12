package io.github.jonjonesbr.jangada.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** O Debian instala no emulador x86_64 e roda comandos pelo proot. */
@RunWith(AndroidJUnit4::class)
class LinuxInstallTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun instalar() = LinuxFixture.ready
    }

    @Test
    fun linuxInstaladoRodaComandos() {
        val env = LinuxEnv(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(env.isReady)
        val out = StringBuilder()
        val code = env.runBash("echo jangada-ok; uname -m; git --version") { out.append(it).append('\n') }
        assertEquals("saída: $out", 0, code)
        assertTrue("saída: $out", out.contains("jangada-ok"))
        assertTrue("saída: $out", out.contains("x86_64"))
        assertTrue("saída: $out", out.contains("git version"))
    }
}
