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
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext


class UnoService {
    fun convert() {
        Thread.sleep(2000); // todo fix later;

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

        val lProperties = mutableListOf<PropertyValue>();
        val prop0 = PropertyValue();
        prop0.Name = "ReadOnly";
        prop0.Value = true;
        lProperties.add(prop0);

        val xDocument = xLoader.loadComponentFromURL(
            "file:///home/moalhaddar/credit_advice.docx", "_default", FrameSearchFlag.CHILDREN, lProperties.toTypedArray()
        )

        val export_type = xType.queryTypeByURL("file:///dummy.pdf")
        val exportPath = "file:///home/moalhaddar/dummy.pdf"

        val xStorable =  UnoRuntime.queryInterface(XStorable::class.java, xDocument)

        val storeProps = mutableListOf<PropertyValue>();

        val prop1 = PropertyValue();
        prop1.Name = "FilterName";
        prop1.Value = "writer_pdf_Export"

        val prop2 = PropertyValue();
        prop2.Name = "Overwrite";
        prop2.Value = true;

        storeProps.add(prop1)
        storeProps.add(prop2)

        xStorable.storeToURL(exportPath, storeProps.toTypedArray())

//        val componentContext = createInitialComponentContext(null);
//        val service = componentContext.serviceManager
        // Create a new URL resolver
//        val resolver = UnoRuntime.queryInterface(
//            XUnoUrlResolver::class.java,
//            service.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", componentContext)
//        )

//        val localMachineContext = UnoRuntime.queryInterface(
//            XComponentContext::class.java,
//            resolver.resolve("uno:socket,host=127.0.0.1,port=2002;urp;StarOffice.ComponentContext")
//        ) as XComponentContext


//        val desktop = UnoRuntime.queryInterface(
//            XDesktop::class.java,
//            service.createInstanceWithContext("com.sun.star.frame.Desktop", localMachineContext)
//        )
//
//        val filterService = UnoRuntime.queryInterface(
//            XFilter::class.java,
//            service.createInstanceWithContext("com.sun.star.document.FilterFactory", localMachineContext)
//        )
//
//        val typeService = UnoRuntime.queryInterface(
//            XTypeDetection::class.java,
//            service.createInstanceWithContext("com.sun.star.document.TypeDetection", localMachineContext)
//        )
    }
}
