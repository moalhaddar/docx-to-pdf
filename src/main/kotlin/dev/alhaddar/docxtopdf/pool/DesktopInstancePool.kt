package dev.alhaddar.docxtopdf.pool

import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.server.LibreOfficeServer
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/***
    UNO is not thread safe, so we need to have one server / one desktop instance pairs
    in order to make it work concurrently.

    Read: https://wiki.openoffice.org/wiki/Effort/Revise_OOo_Multi-Threading
 */
@Component
@EnableScheduling
class DesktopInstancePool(
    @Value("\${pool.size:1}")
    val size: Int
) {
    private val serverInstancePairs: ArrayList<ServerInstancePair> = ArrayList(size)
    private val availablePorts: BlockingQueue<Int> = LinkedBlockingQueue((5000 until 5000 + size).toList())

    private val lock = ReentrantLock()
    private val availableCondition = lock.newCondition()

    init {
        fillPool()
    }

    fun fillPool() {
        val deferredList = mutableListOf<Deferred<Any>>()

        runBlocking {
            repeat(size - serverInstancePairs.size) {
                val deferred = async(Dispatchers.IO) {
                    initServerInstancePair()
                }
                deferredList.add(deferred)
            }
            deferredList.awaitAll()
        }
    }

    fun initServerInstancePair() {
        val port = availablePorts.take()
        val server = LibreOfficeServer(
            host = "127.0.0.1",
            port,
            onExit = {
                availablePorts.put(it.port)
                lock.withLock {
                    serverInstancePairs.removeIf() {pair -> pair.server == it }
                }
            }
        )

        val context = getUnoRemoteContext(server.host, server.port.toString())
        val instance = getDesktopInstanceForContext(context)
        lock.withLock {
            serverInstancePairs.add(
                ServerInstancePair(server,instance, Status.AVAILABLE)
            )
        }
    }

    @Scheduled(fixedDelay = 1000)
    fun poolHealthCheck() {
        logger().debug("[Health Check] Current pool size is ${serverInstancePairs.size}/$size")
        fillPool()
    }

    fun borrow(): XComponent {
        lock.withLock {
            if (serverInstancePairs.size == 0) {
                logger().error("[Pool] No enough workers available.")
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)
            }
            var pair: ServerInstancePair? = serverInstancePairs.firstOrNull { it.status == Status.AVAILABLE }
            while (pair == null) {
                availableCondition.await() // Wait until a pair becomes available
                pair = serverInstancePairs.firstOrNull { it.status == Status.AVAILABLE }
            }
            pair.status = Status.BLOCKED
            return pair.desktopInstance
        }
    }

    fun giveBack(instance: XComponent) {
        lock.withLock {
            val pair = serverInstancePairs.first() { it.desktopInstance == instance }
            pair.status = Status.AVAILABLE
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

enum class Status {
    AVAILABLE, BLOCKED
}

data class ServerInstancePair(
    val server: LibreOfficeServer,
    val desktopInstance: XComponent,
    var status: Status
)