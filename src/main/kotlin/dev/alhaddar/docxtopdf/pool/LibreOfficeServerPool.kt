package dev.alhaddar.docxtopdf.pool

import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.server.LibreOfficeServerProcess
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
@EnableScheduling
class LibreOfficeServerPool(
    @Value("\${pool.size:1}")
    val size: Int
) {
    private val logger = logger()
    val processDescriptorsPool: ArrayList<ProcessDescriptor> = ArrayList(size)

    private val lock = ReentrantLock()
    private val availableCondition = lock.newCondition()

    init {
        fillPoolWithDeadProcesses()
        attachProcessLifeCycleListeners()
        reviveFromTheDead()
    }

    fun reviveFromTheDead() {
        val deferredList = mutableListOf<Deferred<Any>>()
        runBlocking {
            processDescriptorsPool
                .filter {it.status == ProcessStatus.DEAD }
                .forEach {
                    val deferred = async(Dispatchers.IO) {
                        it.process.connect()
                    }
                    deferredList.add(deferred)
                }
            deferredList.awaitAll();
        }
    }

    @Scheduled(fixedDelay = 1000)
    fun healthCheck() {
        val alive = processDescriptorsPool.filter { it.status != ProcessStatus.DEAD }
        logger.info("[LibreOfficeServerPool] Current pool size is ${alive.size}/$size")
        reviveFromTheDead()
    }

    fun attachProcessLifeCycleListeners() {
        processDescriptorsPool.forEach { pd ->
            run {
                pd.process.onStart = {
                    pd.status = ProcessStatus.AVAILABLE
                }
                pd.process.onExit = {
                    pd.status = ProcessStatus.DEAD
                }
            }
        }
    }

    fun fillPoolWithDeadProcesses() {
        val deferredList = mutableListOf<Deferred<Any>>()

        repeat(size) {
            val process = LibreOfficeServerProcess(
                "127.0.0.1",
                2000 + it,
            )
            processDescriptorsPool.add(
                ProcessDescriptor(
                    process,
                    ProcessStatus.DEAD
                )
            )
        }
    }

    fun borrow(): ProcessDescriptor {
        lock.withLock {
            if (processDescriptorsPool.size == 0) {
                logger().error("[Pool] No enough workers available.")
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)
            }

            var pd = processDescriptorsPool.firstOrNull { it.status == ProcessStatus.AVAILABLE }
            while (pd == null) {
                availableCondition.await() // Wait until a pair becomes available
                pd = processDescriptorsPool.firstOrNull { it.status == ProcessStatus.AVAILABLE }
            }
            pd.status = ProcessStatus.BLOCKED
            return pd
        }
    }

    fun giveBack(pd: ProcessDescriptor) {
        lock.withLock {
            val pd = processDescriptorsPool.first() { it == pd }
            pd.status = ProcessStatus.AVAILABLE
            availableCondition.signal()
        }
    }
}


enum class ProcessStatus {
    DEAD, AVAILABLE, BLOCKED
}

data class ProcessDescriptor(
    val process: LibreOfficeServerProcess,
    var status: ProcessStatus
)