package dev.alhaddar.docxtopdf.server

import dev.alhaddar.docxtopdf.logger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.util.FileSystemUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.createTempDirectory


class LibreOfficeServer(val host: String, val port: Int, instanceNumber: Int) {
    private val logger = logger()
    private val logPrefix = "[LibreOffice/$instanceNumber]"
    private val libreOfficeUserProfilePath: Path = createTempDirectory(prefix = "docx-to-pdf-profile-")
    private val process: Process

    init {
        logger.info("$logPrefix Starting server instance..")
        logger.debug("$logPrefix Profile path: $libreOfficeUserProfilePath")
        logger.debug("$logPrefix Host: $host")
        logger.debug("$logPrefix Port: $port")

        val process = ProcessBuilder(
            "libreoffice",
            "--headless",
            "--invisible",
            "--nocrashreport",
            "--nodefault",
            "--nologo",
            "--nofirststartwizard",
            "--norestore",
            "-env:UserInstallation=file://${libreOfficeUserProfilePath}",
            "--accept=socket,host=${host},port=${port},tcpNoDelay=1;urp;StarOffice.ComponentContext",
        )
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()


        GlobalScope.launch {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { logger.info("$logPrefix $it") }
            }
        }

        GlobalScope.launch {
            process.errorStream.bufferedReader().use { reader ->
                reader.forEachLine { logger.error("$logPrefix $it") }
            }
        }

        process.onExit().thenAccept {
            logger.debug("$logPrefix Deleting profile: $libreOfficeUserProfilePath")
            FileSystemUtils.deleteRecursively(libreOfficeUserProfilePath)
            logger.info("$logPrefix Process exited with status: ${it.exitValue()}")
        }

        val startTime = System.currentTimeMillis()
        val timeout = 10 * 1000

        while (true) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 10 * 1000)
                    logger.debug("$logPrefix Successfully started server on $host:$port")
                }
                break
            } catch (e: IOException) {
                // Check if the timeout has been exceeded
                if (System.currentTimeMillis() - startTime > timeout) {
                    logger.error("$logPrefix Connection attempt timed out after $timeout milliseconds.")
                    break
                }
                // Sleep for a short interval before retrying
                try {
                    Thread.sleep(500)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        this.process = process
    }

    @Bean
    fun libreOfficeServerHealthIndicator(): HealthIndicator {
        return HealthIndicator {
            if (process.isAlive) {
                Health.up().withDetail("message", "LibreOffice server is running").build()
            } else {
                Health.down().withDetail("message", "LibreOffice server is not running").build()
            }
        }
    }
}
