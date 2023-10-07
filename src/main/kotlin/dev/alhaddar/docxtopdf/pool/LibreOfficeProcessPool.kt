package dev.alhaddar.docxtopdf.pool

import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.server.LibreOfficeServerProcess
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
class LibreOfficeProcessPool(
    @Value("\${pool.size:1}")
    val size: Int
) {
    private val logger = logger()
    val processDescriptors: ArrayList<ProcessDescriptor> = ArrayList(size)


    init {
        fillWithDeadProcesses()
        attachProcessLifeCycleListeners()
        reviveFromTheDead()
    }

    fun reviveFromTheDead() {
        val deferredList = mutableListOf<Deferred<Any>>()
        runBlocking {
            processDescriptors
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
        val alive = processDescriptors.filter { it.status != ProcessStatus.DEAD }
        logger.info("[LibreOfficeProcessPool] Current pool size is ${alive.size}/$size")
        reviveFromTheDead()
    }

    fun attachProcessLifeCycleListeners() {
        processDescriptors.forEach { pd ->
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

    fun fillWithDeadProcesses() {
        val deferredList = mutableListOf<Deferred<Any>>()

        repeat(size) {
            val process = LibreOfficeServerProcess(
                "127.0.0.1",
                2000 + it,
            )
            processDescriptors.add(
                ProcessDescriptor(
                    process,
                    ProcessStatus.DEAD
                )
            )
        }
    }
}


enum class ProcessStatus {
    DEAD, AVAILABLE
}

data class ProcessDescriptor(
    val process: LibreOfficeServerProcess,
    var status: ProcessStatus
)