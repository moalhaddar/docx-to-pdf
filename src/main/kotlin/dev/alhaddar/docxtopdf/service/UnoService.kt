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
import com.sun.star.lib.uno.adapter.OutputStreamToXOutputStreamAdapter
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.logger
import java.io.ByteArrayOutputStream

class UnoService {
    val logger = logger();
    fun convert(inputFilePath: String): ByteArrayOutputStream {
        val xLocalContext = createInitialComponentContext(null)
        val xLocalServiceManager = xLocalContext.serviceManager

        val xUnoUrlResolver = UnoRuntime.queryInterface(
            XUnoUrlResolver::class.java,
            xLocalServiceManager.createInstanceWithContext(
                "com.sun.star.bridge.UnoUrlResolver", xLocalContext
            )
        );

        val xPropertySet = UnoRuntime.queryInterface(
            XPropertySet::class.java,
            xUnoUrlResolver.resolve(
                "uno:socket,host=127.0.0.1,port=2002;urp;StarOffice.ServiceManager"
            )
        ) as XPropertySet

        val context = xPropertySet.getPropertyValue("DefaultContext")

        val xRemoteContext = UnoRuntime.queryInterface<XComponentContext>(
            XComponentContext::class.java, context
        ) as XComponentContext

        val xRemoteServiceManager = xRemoteContext.serviceManager

        // get Desktop instance
        val desktop = xRemoteServiceManager.createInstanceWithContext(
            "com.sun.star.frame.Desktop", xRemoteContext
        )

        val filter = xRemoteServiceManager.createInstanceWithContext(
            "com.sun.star.document.FilterFactory", xRemoteContext
        )

        val typeService = xRemoteServiceManager.createInstanceWithContext(
            "com.sun.star.document.TypeDetection", xRemoteContext
        )

        val xDesktop = UnoRuntime.queryInterface(XDesktop::class.java, desktop)
        val xFilter = UnoRuntime.queryInterface(XFilter::class.java, filter)
        val xType = UnoRuntime.queryInterface(XTypeDetection::class.java, typeService)
        val xStore = UnoRuntime.queryInterface(XStorable::class.java, typeService)

        val xLoader =  UnoRuntime.queryInterface(XComponentLoader::class.java, desktop)

        val loadProps = mutableListOf<PropertyValue>();
        val prop0 = PropertyValue();
        prop0.Name = "ReadOnly";
        prop0.Value = true;
        loadProps.add(prop0);

        // Not using an input stream because it's horribly slow.
        // The reason is that the byte array will be copied to the C++ side of the UNO.
        // Read: https://libreoffice.freedesktop.narkive.com/2sujB3BI/loader-loadcomponentfromurl-works-slow-when-we-are-restoring-calc-sheet-from-byte-array-loader
        val importPath = "file://${inputFilePath}";

        logger.info("[UNO] Loading input: ${importPath}.")
        val xDocument = xLoader.loadComponentFromURL(
            importPath, "_default", FrameSearchFlag.CHILDREN, loadProps.toTypedArray()
        )
        logger.info("[UNO] End loading input.")

//        val export_type = xType.queryTypeByURL("file:///dummy.pdf")
//        val exportPath = "file:///home/moalhaddar/dummy.pdf"
        val exportPath = "private:stream"
        val outputStream = ByteArrayOutputStream();
        val xOutputStream = OutputStreamToXOutputStreamAdapter(outputStream);

        val xStorable =  UnoRuntime.queryInterface(XStorable::class.java, xDocument)

        val storeProps = mutableListOf<PropertyValue>();
        val prop1 = PropertyValue();
        prop1.Name = "FilterName";
        prop1.Value = "writer_pdf_Export"
        val prop2 = PropertyValue();
        prop2.Name = "Overwrite";
        prop2.Value = true;
        val prop3 = PropertyValue();
        prop3.Name = "OutputStream"
        prop3.Value = xOutputStream;

        storeProps.add(prop1)
        storeProps.add(prop2)
        storeProps.add(prop3)

        logger.info("[UNO] Storing document.")
        xStorable.storeToURL(exportPath, storeProps.toTypedArray())
        logger.info("[UNO] End Storing document.")

        return outputStream;

//        xDocument.dispose(); TODO

    }
}
