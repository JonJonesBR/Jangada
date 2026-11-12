package io.github.jonjonesbr.jangada.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Foto em retrato: uma imagem 400x200 com a orientação EXIF "girar 90" (a câmera gravou em pé) tem que
 * sair do anexo em pé (altura maior que a largura), como a pessoa a viu.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentOrientationTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.ready
    }

    private val context = ChatUi.context
    private val arquivo = File(context.cacheDir, "retrato.jpg")
    private var copiado: Attachment? = null

    @After
    fun apagar() {
        copiado?.let { Fs.deleteTree(File(LinuxFixture.rootfs, it.guestPath.removePrefix("/")).toPath()) }
        arquivo.delete()
    }

    @Test
    fun fotoDeRetratoSaiEmPe() {
        val original = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.RED)
        FileOutputStream(arquivo).use { original.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        original.recycle()
        ExifInterface(arquivo.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        // Uri de arquivo não informa o tipo (as fotos de verdade vêm por content://, com image/jpeg): o
        // prepare copia para a pasta de anexos, e o restore refaz o anexo como imagem, pelo mesmo encodeImage.
        val linux = LinuxEnv(context)
        val copia = Attachments.prepare(context, linux, Uri.fromFile(arquivo))
        copiado = copia
        val anexo = Attachments.restore(linux, copia.name, copia.guestPath, "image/jpeg")
        assertNotNull("o anexo copiado não foi refeito", anexo)
        val codificada = anexo!!.imageBase64
        assertNotNull("o anexo não trouxe a imagem codificada", codificada)
        val bytes = Base64.decode(codificada!!, Base64.DEFAULT)
        val imagem = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("a imagem do anexo não abre", imagem)
        assertTrue(
            "a foto saiu deitada: ${imagem!!.width}x${imagem.height} (deveria ser em pé)",
            imagem.height > imagem.width,
        )
    }
}
