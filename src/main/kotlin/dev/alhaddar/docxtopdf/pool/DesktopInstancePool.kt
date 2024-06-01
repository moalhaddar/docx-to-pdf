package dev.alhaddar.docxtopdf.pool

import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.server.LibreOfficeServer
import dev.alhaddar.docxtopdf.types.DesktopInstanceWrapper
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
    private val desktopInstanceWrappers: BlockingQueue<DesktopInstanceWrapper> = LinkedBlockingQueue(size)
    val servers: ArrayList<LibreOfficeServer> = ArrayList(size)

    init {
        val deferredList = mutableListOf<Deferred<Unit>>()

        runBlocking {
            repeat(size) {
                val deferred = async(Dispatchers.Default) {
                    construct(it)
                }
                deferredList.add(deferred)
            }

            deferredList.awaitAll()
        }
    }

    fun construct(serverId: Int) {
        val server = LibreOfficeServer("127.0.0.1", 2000 + serverId, serverId)
        servers.add(server)
        val instance = getDesktopInstanceForServer(server)
        desktopInstanceWrappers.put(instance)
    }

    /**
     * Destructs a server and it's instance.
     * This causes zombie processes for now...
     */
    fun destruct(serverId: Int) {
        servers.removeAt(serverId)
        desktopInstanceWrappers.removeIf {
            it.serverId == serverId
        }
    }

    fun borrow(): DesktopInstanceWrapper {
        return desktopInstanceWrappers.take()
    }


    fun giveBack(instance: DesktopInstanceWrapper) {
        desktopInstanceWrappers.put(instance)
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

    private fun getDesktopInstanceForServer(server: LibreOfficeServer): DesktopInstanceWrapper {
        val context = getUnoRemoteContext(server.host, server.port.toString())
        val xDesktopInstance = UnoRuntime.queryInterface(
            XComponent::class.java,
            context.serviceManager.createInstanceWithContext(
                "com.sun.star.frame.Desktop", context
            )
        )

        return DesktopInstanceWrapper(xDesktopInstance, server.id)
    }
}
