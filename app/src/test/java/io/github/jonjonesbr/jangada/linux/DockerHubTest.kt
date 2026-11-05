package io.github.jonjonesbr.jangada.linux

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockerHubTest {

    /** Índice (manifest list) no formato que o Docker Hub devolve para debian:trixie-slim. */
    private val indice = JSONObject(
        """
        {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
         {"mediaType":"application/vnd.oci.image.manifest.v1+json","size":529,
          "digest":"sha256:1111111111111111111111111111111111111111111111111111111111111111",
          "platform":{"architecture":"amd64","os":"linux"}},
         {"mediaType":"application/vnd.oci.image.manifest.v1+json","size":529,
          "digest":"sha256:2222222222222222222222222222222222222222222222222222222222222222",
          "platform":{"architecture":"arm64","os":"linux","variant":"v8"}},
         {"mediaType":"application/vnd.oci.image.manifest.v1+json","size":529,
          "digest":"sha256:3333333333333333333333333333333333333333333333333333333333333333",
          "platform":{"architecture":"arm","os":"linux","variant":"v7"}},
         {"mediaType":"application/vnd.oci.image.manifest.v1+json","size":566,
          "digest":"sha256:4444444444444444444444444444444444444444444444444444444444444444",
          "platform":{"architecture":"unknown","os":"unknown"}}
        ]}
        """.trimIndent(),
    )

    /** Manifesto de imagem (não índice) com duas camadas. */
    private val manifesto = JSONObject(
        """
        {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
         "config":{"mediaType":"application/vnd.oci.image.config.v1+json","size":1469,
          "digest":"sha256:5555555555555555555555555555555555555555555555555555555555555555"},
         "layers":[
          {"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","size":29133404,
           "digest":"sha256:6666666666666666666666666666666666666666666666666666666666666666"},
          {"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","size":1024,
           "digest":"sha256:7777777777777777777777777777777777777777777777777777777777777777"}
         ]}
        """.trimIndent(),
    )

    @Test
    fun escolheArm64DoIndice() {
        val digest = DockerHub.pickManifest(indice, LinuxEnv.ARCHITECTURE)
        assertEquals("sha256:2222222222222222222222222222222222222222222222222222222222222222", digest)
    }

    @Test
    fun arquiteturaAusenteViraNull() {
        assertNull(DockerHub.pickManifest(indice, "s390x"))
        assertNull(DockerHub.pickManifest(JSONObject("""{"manifests":[]}"""), "arm64"))
    }

    @Test
    fun entradaSemPlataformaEIgnorada() {
        val semPlataforma = JSONObject(
            """{"manifests":[{"digest":"sha256:8888888888888888888888888888888888888888888888888888888888888888"}]}""",
        )
        assertNull(DockerHub.pickManifest(semPlataforma, "arm64"))
    }

    @Test
    fun camadasDoManifesto() {
        val layers = DockerHub.parseLayers(manifesto)
        assertEquals(2, layers.size)
        assertEquals("sha256:6666666666666666666666666666666666666666666666666666666666666666", layers[0].digest)
        assertEquals(29133404L, layers[0].size)
        assertEquals("application/vnd.oci.image.layer.v1.tar+gzip", layers[0].mediaType)
        assertEquals(1024L, layers[1].size)
    }

    // Sem "layers" o JSON não serve: a leitura falha em vez de devolver lista vazia.
    @Test(expected = JSONException::class)
    fun manifestoSemCamadasDaErro() {
        DockerHub.parseLayers(JSONObject("""{"schemaVersion":2}"""))
    }

    @Test
    fun sha256DeTextoConhecido() {
        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DockerHub.sha256("abc".toByteArray()),
        )
    }
}
