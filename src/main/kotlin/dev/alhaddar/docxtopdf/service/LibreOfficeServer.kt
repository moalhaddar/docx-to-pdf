package dev.alhaddar.docxtopdf.service

import dev.alhaddar.docxtopdf.logger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.util.FileSystemUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.createTempDirectory


@Component
class LibreOfficeServer() {
    private final val logger = logger()
    private final val libreOfficeUserProfilePath: Path = createTempDirectory(prefix = "docx-to-pdf-profile-")
    private final val process: Process
    final val host = "127.0.0.1"
    final val port = "2002"

    init {
        logger.info("[LibreOffice] Starting server..");
        logger.info("[LibreOffice] Profile path: $libreOfficeUserProfilePath")
        logger.info("[LibreOffice] Host: $host")
        logger.info("[LibreOffice] Port: $port")

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
                reader.forEachLine { logger.info("[LibreOffice] $it") }
            }
        }

        GlobalScope.launch {
            process.errorStream.bufferedReader().use { reader ->
                reader.forEachLine { logger.error("[LibreOffice] $it") }
            }
        }

        process.onExit().thenAccept {
            logger.info("[LibreOffice] Deleting profile: $libreOfficeUserProfilePath")
            FileSystemUtils.deleteRecursively(libreOfficeUserProfilePath)
            logger.info("[LibreOffice] Process exited with status: ${it.exitValue()}")
        }

        val startTime = System.currentTimeMillis()
        val timeout = 10 * 1000;

        while (true) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port.toInt()), 10 * 1000)
                    logger.info("[LibreOffice] Successfully started server on $host:$port")
                }
                break;
            } catch (e: IOException) {
                // Check if the timeout has been exceeded

                // Check if the timeout has been exceeded
                if (System.currentTimeMillis() - startTime > timeout) {
                    logger.error("[LibreOffice] Connection attempt timed out after $timeout milliseconds.")
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
