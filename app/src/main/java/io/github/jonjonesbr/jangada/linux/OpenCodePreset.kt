package io.github.jonjonesbr.jangada.linux

import android.util.Log
import io.github.jonjonesbr.jangada.chat.OpenCodeEconomy
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Arquivo de configuração do preset de economia do OpenCode (ver [OpenCodeEconomy]), gravado em
 * `/root/.config/opencode/jangada-economia.json` dentro do Debian. O app passa o caminho ao
 * OpenCode por `OPENCODE_CONFIG`; o `opencode.json` do usuário nunca é lido nem alterado.
 */
object OpenCodePreset {
    /** Caminho relativo à home do root. */
    const val FILE = ".config/opencode/jangada-economia.json"

    /** Caminho como o OpenCode o vê dentro do Debian. */
    const val GUEST = "/root/$FILE"

    private const val TAG = "OpenCodePreset"

    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /**
     * Garante o arquivo do preset no Debian de [linux]. Devolve [GUEST] quando ele ficou certo;
     * null sem o Debian instalado ou se não deu para gravar (avisa e segue: o chat abre sem o preset).
     */
    fun ensure(linux: LinuxEnv): String? {
        if (!runCatching { linux.isReady }.getOrDefault(false)) return null
        val path = runCatching { ensure(linux.rootfs.toPath()) }
            .onFailure { Log.w(TAG, "preset de economia não foi gravado: ${it.message}") }
            .getOrNull()
        if (path == null) Log.w(TAG, "preset de economia não foi gravado; o chat segue sem ele")
        return if (path != null) GUEST else null
    }

    /**
     * Versão testável sobre um rootfs qualquer: cria `.config` e `.config/opencode` se faltarem
     * (nunca `/root`) e grava o preset de forma atômica, modo 600. Não segue link em nenhuma parte
     * do caminho e não regrava quando o conteúdo já é o mesmo.
     */
    internal fun ensure(root: Path): Path? {
        if (!Files.isDirectory(root, NOFOLLOW)) return null
        // Sem /root o Debian está sendo apagado ou reinstalado: não recria a home.
        val home = root.resolve("root")
        if (!Files.isDirectory(home, NOFOLLOW)) return null
        return writeFile(root, home.resolve(FILE), OpenCodeEconomy.configJson())
    }

    /**
     * Grava [content] em [file] (dentro do rootfs) sem seguir link, de forma atômica e 600. Devolve
     * [file] quando já está igual ou quando gravou; null se não deu para gravar.
     */
    internal fun writeFile(root: Path, file: Path, content: String): Path? {
        if (!Files.isDirectory(root, NOFOLLOW)) return null
        // makeParents para na home: sem ela, não cria pasta nenhuma.
        if (!Files.isDirectory(root.resolve("root"), NOFOLLOW)) return null
        // Nunca grava através de link: nem no arquivo, nem numa pasta entre o rootfs e ele.
        if (!parentsAreReal(root, file)) return null
        if (Files.exists(file, NOFOLLOW) && !Files.isRegularFile(file, NOFOLLOW)) return null
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (Files.isRegularFile(file, NOFOLLOW)) {
            val same = try {
                Files.readAllBytes(file).contentEquals(bytes)
            } catch (e: IOException) {
                false
            }
            if (same) return file
        }
        if (!makeParents(root, file)) return null
        writeAtomic(file, bytes)
        return file
    }

    /**
     * Cria as pastas que faltam até [file], uma a uma e sem seguir links. A parada é a home do
     * root: as pastas do topo do rootfs nunca são criadas.
     */
    private fun makeParents(root: Path, file: Path): Boolean {
        val home = root.resolve("root")
        val missing = ArrayList<Path>()
        var dir: Path? = file.parent
        while (dir != null && dir != home) {
            if (Files.isSymbolicLink(dir)) return false
            if (!Files.isDirectory(dir, NOFOLLOW)) {
                if (Files.exists(dir, NOFOLLOW)) return false
                missing.add(dir)
            }
            dir = dir.parent
        }
        if (dir != home) return false
        for (d in missing.asReversed()) {
            Files.createDirectory(d)
            Fs.setMode(d, "700".toInt(8))
        }
        return true
    }

    /** Grava num temporário da mesma pasta (modo 600) e troca pelo alvo com rename, que não segue link. */
    private fun writeAtomic(file: Path, content: ByteArray) {
        val temp = Files.createTempFile(file.parent, ".jangada-", ".tmp")
        try {
            Files.write(temp, content)
            Fs.setMode(temp, "600".toInt(8))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Nenhuma pasta entre o rootfs e [file] é link simbólico. */
    private fun parentsAreReal(root: Path, file: Path): Boolean {
        var dir: Path? = file.parent
        while (dir != null && dir != root) {
            if (Files.isSymbolicLink(dir)) return false
            dir = dir.parent
        }
        return true
    }
}
