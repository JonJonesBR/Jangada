package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Arquitetura do Linux pela ABI principal do Android: pasta do proot, imagem do Docker Hub e uname. */
class ArchTest {

    @Test
    fun arm64VaiParaOProotAarch64EAImagemArm64() {
        val arch = LinuxEnv.archFor("arm64-v8a")!!
        assertEquals("aarch64", arch.assetArch)
        assertEquals("arm64", arch.image)
        assertEquals("aarch64", arch.machine)
        // A imagem padrão continua sendo a dos celulares ARM.
        assertEquals(LinuxEnv.ARCHITECTURE, arch.image)
    }

    @Test
    fun x86_64VaiParaOProotX86_64EAImagemAmd64() {
        val arch = LinuxEnv.archFor("x86_64")!!
        assertEquals("x86_64", arch.assetArch)
        assertEquals("amd64", arch.image)
        assertEquals("x86_64", arch.machine)
    }

    @Test
    fun outrasAbisNaoTemLinux() {
        for (abi in listOf("armeabi-v7a", "armeabi", "x86", "", null)) assertNull(LinuxEnv.archFor(abi))
    }
}
