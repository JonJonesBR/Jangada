package io.github.jonjonesbr.jangada.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** O emulador do CI é x86_64: com o proot dessa arquitetura no APK, o Linux do app é suportado. */
@RunWith(AndroidJUnit4::class)
class LinuxSupportTest {
    @Test
    fun linuxSuportadoNoEmulador() {
        val env = LinuxEnv(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("o Linux do app não é suportado neste aparelho", env.isDeviceSupported)
    }
}
