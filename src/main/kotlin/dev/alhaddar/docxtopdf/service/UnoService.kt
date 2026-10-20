package dev.alhaddar.docxtopdf.service

import com.sun.star.beans.PropertyValue
import com.sun.star.frame.FrameSearchFlag
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XStorable
import com.sun.star.lang.DisposedException
import com.sun.star.lang.XComponent
import com.sun.star.lib.uno.adapter.OutputStreamToXOutputStreamAdapter
import com.sun.star.uno.UnoRuntime
import dev.alhaddar.docxtopdf.exception.LibreOfficeDeadProcessException
import dev.alhaddar.docxtopdf.exception.DocumentNotLoadedNullException
import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.pool.DesktopInstancePool
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.jvm.Throws

@Service
class UnoService(
    val pool: DesktopInstancePool
) {
    val logger = logger()

    fun convert(inputStream: InputStream, isRetry: Boolean): ByteArray {
        val tempFile = createTempFileFromInput(inputStream)
        try {
            return try {
                convertOnce(tempFile)
            } catch (e: LibreOfficeDeadProcessException) {
                if (!isRetry) {
                    convertOnce(tempFile)
                } else {
                    throw e
                }
            }
        } finally {
            tempFile.deleteIfExists()
        }
    }

    private fun convertOnce(tempFile: Path): ByteArray {
        val desktopInstanceWrapper = pool.borrow()
        try {
            val document = loadDocumentIntoDesktopInstance(desktopInstanceWrapper.instance, tempFile)
            try {
                return saveDocument(document).toByteArray()
            } finally {
                runCatching { document.dispose() } // Needed to avoid memory leak.
            }
        } catch (e: DocumentNotLoadedNullException) {
            throw e
        } catch (e: LibreOfficeDeadProcessException) {
            pool.discard(desktopInstanceWrapper)
            throw e
        } catch (e: Exception) {
            if (e is DisposedException || e is IOException || !pool.isLive(desktopInstanceWrapper)) {
                pool.discard(desktopInstanceWrapper)
                throw LibreOfficeDeadProcessException()
            }
            logger.error("Unknown exception was caught.", e)
            throw e
        } finally {
            pool.giveBack(desktopInstanceWrapper)
        }
    }

    private fun saveDocument(document: XComponent): ByteArrayOutputStream {
        val outputStream = ByteArrayOutputStream()
        val exportPath = "private:stream"
        val xOutputStream = OutputStreamToXOutputStreamAdapter(outputStream)

        val storeProps = listOf(
            PropertyValue("FilterName", 0, "writer_pdf_Export", null),
            PropertyValue("Overwrite", 0, true, null),
            PropertyValue("OutputStream", 0, xOutputStream, null)
        )

        val xStorable =  UnoRuntime.queryInterface(XStorable::class.java, document)
        logger.debug("[UNO] Storing document.")
        xStorable.storeToURL(exportPath, storeProps.toTypedArray())
        logger.debug("[UNO] End Storing document.")
        return outputStream
    }

    /**
     * Takes the input stream and stores it temporarily on disk.
     * The reason is that if we send the input stream directly, then the bytes will be read by the libreoffice
     * process extremely slowly (poor stream reading implementation).
     * Read: https://libreoffice.freedesktop.narkive.com/2sujB3BI/loader-loadcomponentfromurl-works-slow-when-we-are-restoring-calc-sheet-from-byte-array-loader
     */
    @Throws(DocumentNotLoadedNullException::class, LibreOfficeDeadProcessException::class)
    private fun loadDocumentIntoDesktopInstance(desktop: XComponent, tempFile: Path): XComponent {
        val xLoader = try {
            UnoRuntime.queryInterface(XComponentLoader::class.java, desktop)
        } catch (e: DisposedException) {
            throw LibreOfficeDeadProcessException()
        }

        val tempFilePath = tempFile.toUri().toString()

        val loadProps = listOf(
            PropertyValue("ReadOnly", 0, true, null)
        )

        logger.debug("[UNO] Loading input: ${tempFilePath}.")
        val xDocument = xLoader.loadComponentFromURL(
            tempFilePath, "_default", FrameSearchFlag.CHILDREN, loadProps.toTypedArray()
        ) ?: throw DocumentNotLoadedNullException()

        logger.debug("[UNO] End loading input.")

        return xDocument
    }

    fun createTempFileFromInput(inputStream: InputStream): Path {
        val tempFile = kotlin.io.path.createTempFile(suffix = ".docx")
        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }
}
