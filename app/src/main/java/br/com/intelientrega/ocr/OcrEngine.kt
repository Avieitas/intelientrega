package br.com.intelientrega.ocr

import android.graphics.Bitmap
import br.com.intelientrega.parsing.OcrLine
import br.com.intelientrega.parsing.OcrText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** OCR isolado: devolve só texto e posições. A interpretação dos campos fica nos parsers. */
interface OcrEngine : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap): OcrText
}

/**
 * ML Kit Text Recognition v2, modelo latino embutido (com.google.mlkit:text-recognition).
 * Processamento no aparelho; a imagem não sai do celular.
 */
class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrText {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        // Mantém a ordem de leitura do ML Kit (blocos → linhas); os parsers usam a linha anterior como rótulo.
        val lines = result.textBlocks.flatMap { block ->
            block.lines.map { l -> OcrLine(l.text, l.boundingBox?.top ?: 0, l.boundingBox?.left ?: 0) }
        }
        return OcrText(lines)
    }

    override fun close() = recognizer.close()
}
