package dev.alhaddar.docxtopdf.service

import dev.alhaddar.docxtopdf.logger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import kotlin.io.path.createTempDirectory

@Component
class LibreOfficeServer {
    val logger = logger()

    val process: Process = startProcess()

    val host = "127.0.0.1"
    val port = "2002"

    fun startProcess(): Process {
        val libreoffceUserProfilePath = createTempDirectory(prefix = "docx-to-pdf")

        logger.info("[LibreOffice] Starting server")

        val process = ProcessBuilder(
                "libreoffice",
                "--headless",
                "--invisible",
                "--nocrashreport",
                "--nodefault",
                "--nologo",
                "--nofirststartwizard",
                "--norestore",
                "-env:UserInstallation=file://${libreoffceUserProfilePath}",
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
            logger.info("[LibreOffice] Process exited with status: ${it.exitValue()}")
        }

        return process;
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
