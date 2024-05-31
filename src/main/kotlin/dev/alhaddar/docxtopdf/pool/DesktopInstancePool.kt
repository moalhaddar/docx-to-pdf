package dev.alhaddar.docxtopdf.pool

import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.server.LibreOfficeServer
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue


/***
    UNO is not thread safe, so we need to have one server / one desktop instance pairs
    in order to make it work concurrently.

    Read: https://wiki.openoffice.org/wiki/Effort/Revise_OOo_Multi-Threading
 */
@Component
class DesktopInstancePool(
    @Value("\${pool.size:1}")
    val size: Int
) {
    private val instancePool: BlockingQueue<XComponent> = LinkedBlockingQueue(size)
    val servers: ArrayList<LibreOfficeServer> = ArrayList(size)

    init {
        val deferredList = mutableListOf<Deferred<Unit>>()

        runBlocking {
            repeat(size) {
                val deferred = async(Dispatchers.Default) {
                    val server = LibreOfficeServer("127.0.0.1", 2000 + it, it)
                    servers.add(server)
                    val context = getUnoRemoteContext(server.host, server.port.toString())
                    val instance = getDesktopInstanceForContext(context)
                    instancePool.put(instance)
                }
                deferredList.add(deferred)
            }

            deferredList.awaitAll()
        }
    }

    fun borrow(): XComponent {
        return instancePool.take()
    }

    fun giveBack(instance: XComponent) {
        instancePool.put(instance)
    }

    private fun getUnoRemoteContext(host: String, port: String): XComponentContext {
        val xLocalContext = Bootstrap.createInitialComponentContext(null)
        val xLocalServiceManager = xLocalContext.serviceManager

        val xUnoUrlResolver = UnoRuntime.queryInterface(
            XUnoUrlResolver::class.java,
            xLocalServiceManager.createInstanceWithContext(
                "com.sun.star.bridge.UnoUrlResolver", xLocalContext
            )
        )

        val xPropertySet = UnoRuntime.queryInterface(
            XPropertySet::class.java,
            xUnoUrlResolver.resolve(
                "uno:socket,host=${host},port=${port},tcpNoDelay=1;urp;StarOffice.ServiceManager"
            )
        ) as XPropertySet

        val context = xPropertySet.getPropertyValue("DefaultContext")

        return UnoRuntime.queryInterface(
            XComponentContext::class.java, context
        ) as XComponentContext
    }

    private fun getDesktopInstanceForContext(context: XComponentContext): XComponent {
        val xDesktopInstance = context.serviceManager.createInstanceWithContext(
            "com.sun.star.frame.Desktop", context
        )
        return UnoRuntime.queryInterface(XComponent::class.java, xDesktopInstance)
    }
}
