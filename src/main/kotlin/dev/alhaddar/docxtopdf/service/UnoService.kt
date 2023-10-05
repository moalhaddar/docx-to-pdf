package dev.alhaddar.docxtopdf.service

import com.sun.star.beans.PropertyValue
import com.sun.star.frame.FrameSearchFlag
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XStorable
import com.sun.star.lang.XComponent
import com.sun.star.lib.uno.adapter.OutputStreamToXOutputStreamAdapter
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.logger
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.outputStream

@Service
class UnoService(val xRemoteContext: XComponentContext) {
    val logger = logger();

    fun convert(inputStream: InputStream): ByteArray {
        val desktop = getDesktopInstance()
        val document = loadDocumentIntoDesktopInstance(desktop, inputStream)
        val outputStream = saveDocumentToStream(document)
        document.dispose()
        return outputStream.toByteArray();
    }

    private fun saveDocumentToStream(document: XComponent): ByteArrayOutputStream {
        val outputStream = ByteArrayOutputStream();
        val exportPath = "private:stream"
        val xOutputStream = OutputStreamToXOutputStreamAdapter(outputStream);

        val storeProps = listOf(
            PropertyValue("FilterName", 0, "writer_pdf_Export", null),
            PropertyValue("Overwrite", 0, true, null),
            PropertyValue("OutputStream", 0, xOutputStream, null)
        );

        val xStorable =  UnoRuntime.queryInterface(XStorable::class.java, document)
        logger.debug("[UNO] Storing document.")
        xStorable.storeToURL(exportPath, storeProps.toTypedArray())
        logger.debug("[UNO] End Storing document.")
        return outputStream;
    }

    /**
     * Takes the input stream and stores it temporarily on disk.
     * The reason is that the byte array will be copied to the C++ side of the UNO.
     * Read: https://libreoffice.freedesktop.narkive.com/2sujB3BI/loader-loadcomponentfromurl-works-slow-when-we-are-restoring-calc-sheet-from-byte-array-loader
     */
    private fun loadDocumentIntoDesktopInstance(desktop: Any, inputStream: InputStream): XComponent {
        val xLoader =  UnoRuntime.queryInterface(XComponentLoader::class.java, desktop)

        val tempFile = createTempFileFromInput(inputStream)
        val tempFilePath = tempFile.toUri().toString()

        val loadProps = listOf(
            PropertyValue("ReadOnly", 0, true, null)
        )

        logger.debug("[UNO] Loading input: ${tempFilePath}.")
        val xDocument = xLoader.loadComponentFromURL(
            tempFilePath, "_default", FrameSearchFlag.CHILDREN, loadProps.toTypedArray()
        )
        logger.debug("[UNO] End loading input.")

        tempFile.deleteExisting()

        return xDocument;
    }

    fun getDesktopInstance(): Any {
        return xRemoteContext.serviceManager.createInstanceWithContext(
            "com.sun.star.frame.Desktop", xRemoteContext
        )
    }

    fun createTempFileFromInput(inputStream: InputStream): Path {
        val tempFile = kotlin.io.path.createTempFile(suffix = ".docx")
        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile;
    }
}
