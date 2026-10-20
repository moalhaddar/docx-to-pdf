package dev.alhaddar.docxtopdf.pool

import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.server.LibreOfficeServer
import dev.alhaddar.docxtopdf.types.DesktopInstanceWrapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.IdentityHashMap
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException


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
    private val logger = logger()
    private val lock = Any()
    private val desktopInstanceWrappers: BlockingQueue<DesktopInstanceWrapper> = LinkedBlockingQueue()
    private val liveWrappers: MutableMap<DesktopInstanceWrapper, LibreOfficeServer> = IdentityHashMap()
    private val activeThreads: MutableMap<DesktopInstanceWrapper, Thread> = IdentityHashMap()
    val servers: MutableList<LibreOfficeServer> = CopyOnWriteArrayList()

    private val replenisher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "libreoffice-replenisher").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    init {
        val deferredList = mutableListOf<Deferred<Unit>>()

        try {
            runBlocking {
                repeat(size) {
                    val deferred = async(Dispatchers.Default) {
                        construct(it)
                    }
                    deferredList.add(deferred)
                }

                deferredList.awaitAll()
            }
        } catch (e: Exception) {
            shutdown()
            throw e
        }
    }

    fun construct(serverId: Int) {
        val server = LibreOfficeServer("127.0.0.1", 2000 + serverId, serverId)
        val instance = try {
            getDesktopInstanceForServer(server)
        } catch (e: Exception) {
            server.destroy()
            throw e
        }

        synchronized(lock) {
            if (closed) {
                server.destroy()
                return
            }
            val existingIndex = servers.indexOfFirst { it.id == serverId }
            if (existingIndex >= 0) {
                servers[existingIndex] = server
            } else {
                servers.add(server)
            }
            liveWrappers[instance] = server
            desktopInstanceWrappers.put(instance)
        }

        server.process.onExit().thenRun {
            discard(instance)
        }
    }

    /**
     * Discards a broken desktop instance once, kills its LibreOffice process, interrupts any
     * thread still blocked in a UNO call on it, and schedules a single replacement.
     */
    fun discard(instance: DesktopInstanceWrapper) {
        val server = synchronized(lock) {
            val removed = liveWrappers.remove(instance) ?: return
            desktopInstanceWrappers.remove(instance)
            activeThreads.remove(instance)?.takeIf { it !== Thread.currentThread() }?.interrupt()
            removed
        }

        logger.warn("Discarding ${server.logPrefix} and scheduling replacement.")

        if (!closed) {
            try {
                replenisher.execute {
                    runCatching { server.destroy() }
                    replenish(instance.serverId)
                }
            } catch (_: RejectedExecutionException) {
                runCatching { server.destroy() }
            }
        } else {
            runCatching { server.destroy() }
        }
    }

    fun isLive(instance: DesktopInstanceWrapper): Boolean = synchronized(lock) {
        liveWrappers[instance]?.process?.isAlive == true
    }

    fun borrow(): DesktopInstanceWrapper {
        while (true) {
            val instance = desktopInstanceWrappers.take()
            synchronized(lock) {
                val server = liveWrappers[instance]
                if (server != null && server.process.isAlive) {
                    activeThreads[instance] = Thread.currentThread()
                    return instance
                }
            }
            discard(instance)
        }
    }

    fun giveBack(instance: DesktopInstanceWrapper) {
        val returned = synchronized(lock) {
            activeThreads.remove(instance)
            (instance in liveWrappers).also {
                if (it) {
                    desktopInstanceWrappers.put(instance)
                }
            }
        }
        if (!returned) {
            Thread.interrupted()
        }
    }

    @PreDestroy
    fun shutdown() {
        closed = true
        replenisher.shutdownNow()
        val toDestroy = synchronized(lock) {
            val current = servers.toList()
            servers.clear()
            liveWrappers.clear()
            desktopInstanceWrappers.clear()
            activeThreads.clear()
            current
        }
        toDestroy.forEach { runCatching { it.destroy() } }
    }

    private fun replenish(serverId: Int) {
        var delayMs = 500L
        while (!closed) {
            try {
                construct(serverId)
                return
            } catch (e: Exception) {
                logger.error("[LibreOffice/$serverId] Failed to start replacement instance, retrying in ${delayMs}ms.", e)
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                delayMs = (delayMs * 2).coerceAtMost(10_000L)
            }
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
