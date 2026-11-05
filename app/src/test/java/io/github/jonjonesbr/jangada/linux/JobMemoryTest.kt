package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class JobMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val agora = 1793800000000L

    private fun relatorio(job: String, texto: String, modificadoEm: Long = agora - 1_000): File {
        val arquivo = JobMemory.file(tmp.root, job)
        arquivo.parentFile.mkdirs()
        arquivo.writeText(texto)
        Files.setLastModifiedTime(arquivo.toPath(), FileTime.fromMillis(modificadoEm))
        return arquivo
    }

    @Test
    fun leDoisInteiros() {
        assertEquals(JobMemory.Usage(7516, 4), JobMemory.parse("7516 4\n"))
    }

    @Test
    fun vazioNaoEValido() {
        assertNull(JobMemory.parse(""))
    }

    @Test
    fun naoNumeroNaoEValido() {
        assertNull(JobMemory.parse("abc 1"))
    }

    @Test
    fun tresCamposNaoEValido() {
        assertNull(JobMemory.parse("1 2 3"))
    }

    @Test
    fun negativoNaoEValido() {
        assertNull(JobMemory.parse("-1 2"))
    }

    @Test
    fun relatorioRecenteEValido() {
        relatorio("trabalho-1", "7516 4\n")
        assertEquals(JobMemory.Usage(7516, 4), JobMemory.read(tmp.root, "trabalho-1", agora))
    }

    @Test
    fun relatorioNoLimiteDeIdadeAindaValeMasNoLimiteNao() {
        relatorio("trabalho-1", "7516 4", modificadoEm = agora - JobMemory.MAX_AGE_MS + 1)
        assertEquals(JobMemory.Usage(7516, 4), JobMemory.read(tmp.root, "trabalho-1", agora))
        relatorio("trabalho-1", "7516 4", modificadoEm = agora - JobMemory.MAX_AGE_MS)
        assertNull(JobMemory.read(tmp.root, "trabalho-1", agora))
    }

    @Test
    fun relatorioMaisVelhoQueLimiteViraNull() {
        relatorio("trabalho-1", "7516 4", modificadoEm = agora - JobMemory.MAX_AGE_MS - 5_000)
        assertNull(JobMemory.read(tmp.root, "trabalho-1", agora))
    }

    @Test
    fun dataNoFuturoViraNull() {
        relatorio("trabalho-1", "7516 4", modificadoEm = agora + 5_000)
        assertNull(JobMemory.read(tmp.root, "trabalho-1", agora))
    }

    @Test
    fun maiorQue64BytesViraNull() {
        // Conteúdo que seria válido se lido (dois inteiros), mas passa do tamanho aceito.
        relatorio("trabalho-1", "7516 4" + " ".repeat(70))
        assertNull(JobMemory.read(tmp.root, "trabalho-1", agora))
    }

    @Test
    fun linkSimbolicoViraNull() {
        val alvo = File(tmp.root, "alvo.txt")
        alvo.writeText("7516 4")
        File(tmp.root, "var/lib/jangada/jobs").mkdirs()
        try {
            Files.createSymbolicLink(JobMemory.file(tmp.root, "trabalho-1").toPath(), alvo.toPath())
        } catch (e: Exception) {
            // Sistema sem links simbólicos: nada a testar aqui.
            Assume.assumeNoException(e)
        }
        assertNull(JobMemory.read(tmp.root, "trabalho-1", agora))
    }

    @Test
    fun trabalhoInvalidoViraNullSemLerODisco() {
        // Existe um arquivo válido exatamente onde "../x" apontaria; mesmo assim o nome é recusado.
        relatorio("../x", "7516 4")
        assertNull(JobMemory.read(tmp.root, "../x", agora))
    }

    @Test
    fun arquivoAusenteViraNull() {
        assertNull(JobMemory.read(tmp.root, "nao-existe", agora))
    }
}
