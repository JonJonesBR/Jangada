package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Roda o CACHE_CLEAN_SCRIPT de verdade com bash, numa pasta temporária no lugar de /root, /tmp e
 * /var/cache, e confere o que sobra. O script do app não muda: o teste troca os caminhos no texto e se
 * recusa a rodar se sobrar algum caminho absoluto de verdade (para nunca apagar nada fora da pasta).
 */
class CacheCleanRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var base: File

    private fun bash(script: String, path: String? = null): Pair<Int, String> {
        val builder = ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true)
        builder.environment()["HOME"] = File(base, "home").absolutePath
        if (path != null) builder.environment()["PATH"] = path + ":" + System.getenv("PATH")
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("bash não terminou", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() to out
    }

    /** O script com os caminhos trocados para dentro de [base]. */
    private fun sandboxed(): String {
        // Marcadores primeiro: a pasta temporária pode estar ela mesma em /tmp.
        val marked = CACHE_CLEAN_SCRIPT
            .replace("/etc/profile", "/dev/null")
            .replace("~/.profile", "/dev/null")
            .replace("~/.bashrc", "/dev/null")
            .replace("apt-get clean", "true")
            .replace("/root/", "\u0001R/")
            .replace("/tmp", "\u0001T")
            .replace("/var/cache", "\u0001V")
        // Nenhum caminho de verdade pode sobrar (o /proc só é lido, e /dev/null é inofensivo).
        for (real in listOf("/root", "/tmp", "/var/", "/etc/", "~/")) {
            assertFalse("o script ainda usa $real fora da pasta do teste", marked.contains(real))
        }
        return marked
            .replace("\u0001R/", "${base.absolutePath}/root/")
            .replace("\u0001T", "${base.absolutePath}/tmp")
            .replace("\u0001V", "${base.absolutePath}/var/cache")
    }

    @Test
    fun limpezaApagaSoOQueDeve() {
        assumeTrue(File("/bin/bash").canExecute())
        base = tmp.newFolder("rootfs")
        // Monta o Debian de mentira com o próprio bash (touch -h muda a data de links sem seguir).
        val (setupCode, setupOut) = bash(
            """
            set -e
            cd '${base.absolutePath}'
            mkdir -p home root/.npm/_cacache root/.npm/_npx root/.cache/pip root/.cache/ms-playwright \
              root/projetos/app root/.cache/chromium-headless/scoped_dir1 root/.cache/chromium-headless/scoped_dir2 \
              tmp/velho tmp/novo tmp/misto 'tmp/com espaço' tmp/.X11-unix tmp/claude-0/proj/sessao-velha \
              tmp/claude-0/proj/sessao-nova var/cache/apt/archives fora bin root/.local/share/claude/versions
            head -c 20000 /dev/zero > root/.npm/_cacache/blob
            echo x > root/.npm/_npx/pacote
            echo x > root/.cache/pip/whl
            echo x > root/.cache/ms-playwright/chromium
            echo x > root/projetos/app/codigo.kt
            echo x > root/.cache/chromium-headless/scoped_dir1/perfil
            echo x > root/.cache/chromium-headless/scoped_dir2/perfil
            echo x > tmp/velho/a
            echo x > tmp/novo/a
            echo x > tmp/misto/velho
            echo x > tmp/misto/novo
            echo x > 'tmp/com espaço/a'
            echo x > tmp/.X11-unix/X0
            echo x > tmp/claude-0/proj/sessao-velha/saida
            echo x > tmp/claude-0/proj/sessao-nova/saida
            echo x > fora/importante
            ln -s '${base.absolutePath}/fora' tmp/link-para-fora
            for v in 1.0.0 2.0.1 3.0.0; do echo x > root/.local/share/claude/versions/${'$'}v; chmod +x root/.local/share/claude/versions/${'$'}v; done
            ln -s '${base.absolutePath}/root/.local/share/claude/versions/2.0.1' bin/claude
            antigo='10 days ago'
            touch -d '2 hours ago' root/.cache/chromium-headless/scoped_dir1/perfil root/.cache/chromium-headless/scoped_dir1
            for p in tmp/velho/a tmp/velho tmp/misto/velho 'tmp/com espaço/a' 'tmp/com espaço' tmp/.X11-unix/X0 tmp/.X11-unix \
              tmp/claude-0/proj/sessao-velha/saida tmp/claude-0/proj/sessao-velha root/projetos/app/codigo.kt fora/importante; do
              touch -d "${'$'}antigo" "${'$'}p"
            done
            touch -h -d "${'$'}antigo" tmp/link-para-fora
            """.trimIndent(),
        )
        assertEquals(setupOut, 0, setupCode)

        val (code, out) = bash(sandboxed(), path = File(base, "bin").absolutePath)
        assertEquals(out, 0, code)

        fun exists(path: String) = File(base, path).let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }
        // Caches de download e o que está parado: saem.
        for (gone in listOf(
            "root/.npm/_cacache", "root/.cache/pip", "root/.cache/chromium-headless/scoped_dir1",
            "tmp/velho", "tmp/com espaço", "tmp/claude-0/proj/sessao-velha", "tmp/link-para-fora",
            "root/.local/share/claude/versions/1.0.0",
        )) assertFalse("devia apagar $gone", exists(gone))
        // O que alguém usa, ou não volta sozinho: fica (projeto antigo, alvo do link, versão atual e a mais nova).
        for (kept in listOf(
            "root/.npm/_npx/pacote", "root/.cache/ms-playwright/chromium", "root/projetos/app/codigo.kt",
            "root/.cache/chromium-headless/scoped_dir2/perfil", "tmp/novo/a", "tmp/misto/velho", "tmp/misto/novo",
            "tmp/.X11-unix/X0", "tmp/claude-0", "tmp/claude-0/proj/sessao-nova/saida", "fora/importante",
            "root/.local/share/claude/versions/2.0.1", "root/.local/share/claude/versions/3.0.0",
        )) assertTrue("não devia apagar $kept", exists(kept))
        // Quanto liberou: o cache de 20 kB entra na conta.
        val freed = out.lineSequence().firstOrNull { it.startsWith(CACHE_FREED_PREFIX) }
            ?.removePrefix(CACHE_FREED_PREFIX)?.trim()?.toLongOrNull()
        assertTrue("sem $CACHE_FREED_PREFIX na saída: $out", freed != null && freed >= 20)
    }
}
