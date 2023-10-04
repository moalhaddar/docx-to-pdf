package dev.alhaddar.docxtopdf.service

import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap.createInitialComponentContext
import com.sun.star.document.XFilter
import com.sun.star.document.XTypeDetection
import com.sun.star.frame.FrameSearchFlag
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XDesktop
import com.sun.star.frame.XStorable
import com.sun.star.lang.XComponent
import com.sun.star.lib.uno.adapter.OutputStreamToXOutputStreamAdapter
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import com.sun.star.xml.dom.XDocument
import dev.alhaddar.docxtopdf.logger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

@Service
class UnoService(val xRemoteContext: XComponentContext) {
    val logger = logger();

    fun convert(inputFilePath: String): ByteArrayOutputStream {
        val desktop = getDesktopInstance()
        val document = loadFileIntoDesktopInstance(desktop, inputFilePath)
        val outputStream = ByteArrayOutputStream();
        saveDocumentToStream(document, outputStream)
        document.dispose()
        return outputStream;

    }

    private fun saveDocumentToStream(document: XComponent, outputStream: ByteArrayOutputStream) {
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

    }

    private fun loadFileIntoDesktopInstance(desktop: Any, inputFilePath: String): XComponent {
        val xLoader =  UnoRuntime.queryInterface(XComponentLoader::class.java, desktop)

        val loadProps = listOf(
            PropertyValue("ReadOnly", 0, true, null)
        )

        // Not using an input stream because it's horribly slow.
        // The reason is that the byte array will be copied to the C++ side of the UNO.
        // Read: https://libreoffice.freedesktop.narkive.com/2sujB3BI/loader-loadcomponentfromurl-works-slow-when-we-are-restoring-calc-sheet-from-byte-array-loader

        logger.debug("[UNO] Loading input: ${inputFilePath}.")
        val xDocument = xLoader.loadComponentFromURL(
            inputFilePath, "_default", FrameSearchFlag.CHILDREN, loadProps.toTypedArray()
        )
        logger.debug("[UNO] End loading input.")
        return xDocument;
    }

    fun getDesktopInstance(): Any {
        return xRemoteContext.serviceManager.createInstanceWithContext(
            "com.sun.star.frame.Desktop", xRemoteContext
        )
    }
}
