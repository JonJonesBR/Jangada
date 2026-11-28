package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.nio.file.Files

class LinuxEnvSymlinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ensureRealDirCriaCadeiaRealEConfirmaIsDir() {
        val root = tmp.newFolder("rootfs").toPath()
        val bin = root.resolve("usr/local/bin")

        assertTrue(LinuxEnv.ensureRealDir(root, bin))
        assertTrue(Fs.isDir(root.resolve("usr")))
        assertTrue(Fs.isDir(root.resolve("usr/local")))
        assertTrue(Fs.isDir(bin))
    }

    @Test
    fun ensureRealDirBloqueiaAncestralSymlink() {
        val root = tmp.newFolder("rootfs").toPath()
        val outside = tmp.newFolder("fora").toPath()
        Files.createSymbolicLink(root.resolve("usr"), outside)

        assertFalse(LinuxEnv.ensureRealDir(root, root.resolve("usr/local/bin")))
        assertFalse(Files.exists(outside.resolve("local")))
    }

    @Test
    fun ensureRealDirBloqueiaAlvoSymlinkArquivoComumEFugaDoRootfs() {
        val root = tmp.newFolder("rootfs").toPath()
        val outside = tmp.newFolder("fora").toPath()

        Files.createSymbolicLink(root.resolve("link-alvo"), outside)
        assertFalse(LinuxEnv.ensureRealDir(root, root.resolve("link-alvo")))

        Files.writeString(root.resolve("arquivo"), "x")
        assertFalse(LinuxEnv.ensureRealDir(root, root.resolve("arquivo")))
        assertFalse(LinuxEnv.ensureRealDir(root, root.resolve("arquivo/sub")))

        assertFalse(LinuxEnv.ensureRealDir(root, root.resolve("../fora")))

        val rootLink = tmp.root.toPath().resolve("root-link")
        Files.createSymbolicLink(rootLink, root)
        assertFalse(LinuxEnv.ensureRealDir(rootLink, rootLink.resolve("etc")))
    }

    @Test
    fun writeDnsConfBloqueiaEtcSymlink() {
        val root = tmp.newFolder("rootfs").toPath()
        val outside = tmp.newFolder("fora").toPath()
        Files.createSymbolicLink(root.resolve("etc"), outside)

        assertFalse(LinuxEnv.writeDnsConf(root, listOf(InetAddress.getByName("192.168.0.1"))))
        assertFalse(Files.exists(outside.resolve("resolv.conf")))
    }

    @Test
    fun writeDnsConfSubstituiResolvLinkSemAlterarAlvo() {
        val root = tmp.newFolder("rootfs").toPath()
        val etc = Files.createDirectory(root.resolve("etc"))
        val outside = tmp.newFolder("fora").toPath()
        val target = outside.resolve("alvo.txt")
        Files.writeString(target, "intacto")
        Files.createSymbolicLink(etc.resolve("resolv.conf"), target)

        assertTrue(LinuxEnv.writeDnsConf(root, listOf(InetAddress.getByName("192.168.0.1"))))
        val resolv = etc.resolve("resolv.conf")
        assertFalse(Files.isSymbolicLink(resolv))
        assertTrue(Files.readString(resolv).contains("nameserver 192.168.0.1"))
        assertEquals("intacto", Files.readString(target))
    }

    @Test
    fun prepareTmpDirBloqueiaAncestralOuAlvoSymlinkEArquivo() {
        val root = tmp.newFolder("rootfs").toPath()
        val outside = tmp.newFolder("fora").toPath()
        var chmodCalls = 0
        var lastMode = 0

        Files.createSymbolicLink(root.resolve("var"), outside)
        assertFalse(LinuxEnv.prepareTmpDir(root, "var/tmp") { _, mode ->
            chmodCalls++
            lastMode = mode
        })
        assertEquals(0, chmodCalls)
        assertFalse(Files.exists(outside.resolve("tmp")))

        Files.createSymbolicLink(root.resolve("tmp"), outside)
        assertFalse(LinuxEnv.prepareTmpDir(root, "tmp") { _, mode ->
            chmodCalls++
            lastMode = mode
        })
        assertEquals(0, chmodCalls)

        Files.delete(root.resolve("tmp"))
        Files.delete(root.resolve("var"))
        Files.writeString(root.resolve("tmp"), "arquivo")
        assertFalse(LinuxEnv.prepareTmpDir(root, "tmp") { _, mode ->
            chmodCalls++
            lastMode = mode
        })
        assertEquals(0, chmodCalls)

        Files.delete(root.resolve("tmp"))
        assertTrue(LinuxEnv.prepareTmpDir(root, "tmp") { _, mode ->
            chmodCalls++
            lastMode = mode
        })
        assertTrue(LinuxEnv.prepareTmpDir(root, "var/tmp") { _, mode ->
            chmodCalls++
            lastMode = mode
        })
        assertEquals(2, chmodCalls)
        assertEquals("1777".toInt(8), lastMode)
        assertTrue(Fs.isDir(root.resolve("tmp")))
        assertTrue(Fs.isDir(root.resolve("var/tmp")))
    }
}
