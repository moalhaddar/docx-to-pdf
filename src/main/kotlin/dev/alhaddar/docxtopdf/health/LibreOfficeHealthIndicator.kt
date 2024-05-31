package dev.alhaddar.docxtopdf.health

import dev.alhaddar.docxtopdf.pool.DesktopInstancePool
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class LibreOfficeHealthIndicator(val desktopInstancePool: DesktopInstancePool): HealthIndicator {

    override fun health(): Health {
        val h = Health.Builder()
        desktopInstancePool.servers.stream().forEach {
            if (it.process.isAlive) {
                h.up().withDetail("message", "${it.logPrefix} server is running")
            } else {
                h.down().withDetail("message", "${it.logPrefix} server is not running")
            }
        }
        return h.build()
    }
}