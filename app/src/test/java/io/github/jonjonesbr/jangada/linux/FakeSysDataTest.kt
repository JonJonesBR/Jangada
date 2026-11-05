package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeSysDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Nome do substituto (na pasta dele) e o arquivo real de /proc que ele troca. */
    private val substitutos = listOf(
        "loadavg" to "/proc/loadavg",
        "stat" to "/proc/stat",
        "uptime" to "/proc/uptime",
        "version" to "/proc/version",
        "vmstat" to "/proc/vmstat",
        "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
        "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
        "overflowuid" to "/proc/sys/kernel/overflowuid",
        "overflowgid" to "/proc/sys/kernel/overflowgid",
    )

    @Test
    fun primeiroBindEsvaziaOSelinuxEOsOutrosApontamParaADentroDaPasta() {
        val dir = tmp.newFolder("sys")
        val binds = FakeSysData.bindings(dir)
        assertEquals("--bind=${File(dir, "sys_empty").absolutePath}:/sys/fs/selinux", binds.first())
        assertTrue("sys_empty não foi criada", File(dir, "sys_empty").isDirectory)
        for (bind in binds.drop(1)) {
            assertTrue("bind fora da pasta: $bind", bind.startsWith("--bind=${dir.absolutePath}${File.separator}"))
        }
    }

    @Test
    fun arquivoLegivelEhOmitidoEIlegivelGanhaSubstituto() {
        val dir = tmp.newFolder("sys")
        val binds = FakeSysData.bindings(dir)
        // Conforme o que este sistema deixa ler: o teste vale nos dois casos.
        for ((name, real) in substitutos) {
            val bind = binds.firstOrNull { it.endsWith(":$real") }
            if (leAoLer(real)) {
                assertNull("$real é legível e não devia ter substituto", bind)
                assertFalse("$name foi criado sem precisar", File(dir, name).exists())
            } else {
                assertEquals("substituto de $real", "--bind=${File(dir, name).absolutePath}:$real", bind)
                assertTrue("$name não foi escrito", File(dir, name).isFile)
            }
        }
    }

    @Test
    fun conteudoDoSubstitutoEFalsoMasPlausivel() {
        val dir = tmp.newFolder("sys")
        FakeSysData.bindings(dir)
        val esperado = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "cap_last_cap" to "40\n",
            "max_user_watches" to "4096\n",
            "overflowuid" to "65534\n",
            "overflowgid" to "65534\n",
        )
        for ((name, conteudo) in esperado) {
            // Só confere os que foram escritos (os legíveis não têm substituto).
            val file = File(dir, name)
            if (file.exists()) assertEquals(name, conteudo, file.readText())
        }
        File(dir, "version").takeIf { it.exists() }?.let {
            assertTrue(it.readText().startsWith("Linux version ${FakeSysData.KERNEL_RELEASE} "))
        }
        File(dir, "stat").takeIf { it.exists() }?.let { stat ->
            val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val linhasDeCpu = stat.readLines().count { Regex("^cpu\\d+ ").containsMatchIn(it) }
            assertEquals("uma linha por CPU", cpus, linhasDeCpu)
        }
    }

    @Test
    fun chamarDeNovoNaMesmaPastaDaOMesmoResultado() {
        val dir = tmp.newFolder("sys")
        val primeiro = FakeSysData.bindings(dir)
        assertEquals(primeiro, FakeSysData.bindings(dir))
    }

    /** Se o arquivo de /proc pode ser lido aqui (o mesmo teste que o FakeSysData faz). */
    private fun leAoLer(caminho: String): Boolean = try {
        File(caminho).inputStream().use { it.read() }
        true
    } catch (e: Exception) {
        false
    }
}
