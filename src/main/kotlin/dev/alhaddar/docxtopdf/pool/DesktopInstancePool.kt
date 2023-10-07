package dev.alhaddar.docxtopdf.pool

import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.logger
import kotlinx.coroutines.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/***
    UNO is not thread safe, so we need to have one server / one desktop instance pairs
    in order to make it work concurrently.

    Read: https://wiki.openoffice.org/wiki/Effort/Revise_OOo_Multi-Threading
 */
@Component
class DesktopInstancePool(
    val processPool: LibreOfficeProcessPool
) {
    private val desktopDescriptorsPool: ArrayList<DesktopDescriptor> = ArrayList(processPool.size)

    private val lock = ReentrantLock()
    private val availableCondition = lock.newCondition()

    init {
        fillPool()
    }

    fun fillPool() {
        val deferredList = mutableListOf<Deferred<Any>>()

        runBlocking {
            processPool.processDescriptors.forEach {
                val deferred = async(Dispatchers.IO) {
                    val context = getUnoRemoteContext(it.process.host, it.process.port.toString())
                    val desktopInstance = getDesktopInstanceForContext(context)
                    lock.withLock {
                        desktopDescriptorsPool.add(
                            DesktopDescriptor(desktopInstance, DesktopStatus.AVAILABLE)
                        )
                    }
                }
                deferredList.add(deferred)
            }

            deferredList.awaitAll()
        }
    }

    fun borrow(): DesktopDescriptor {
        lock.withLock {
            if (desktopDescriptorsPool.size == 0) {
                logger().error("[Pool] No enough workers available.")
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)
            }
            var dd: DesktopDescriptor? = desktopDescriptorsPool.firstOrNull { it.status == DesktopStatus.AVAILABLE }
            while (dd == null) {
                availableCondition.await() // Wait until a pair becomes available
                dd = desktopDescriptorsPool.firstOrNull { it.status == DesktopStatus.AVAILABLE }
            }
            dd.status = DesktopStatus.BLOCKED
            return dd
        }
    }

    fun giveBack(dd: DesktopDescriptor) {
        lock.withLock {
            val pair = desktopDescriptorsPool.first() { it == dd }
            pair.status = DesktopStatus.AVAILABLE
            availableCondition.signal()
        }
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
                "uno:socket,host=${host},port=${port};urp;StarOffice.ServiceManager"
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

enum class DesktopStatus {
    AVAILABLE, BLOCKED
}

data class DesktopDescriptor(
    val desktopInstance: XComponent,
    var status: DesktopStatus
)