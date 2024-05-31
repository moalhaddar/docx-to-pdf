package dev.alhaddar.docxtopdf.health

import dev.alhaddar.docxtopdf.pool.DesktopInstancePool
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class LibreOfficeHealthIndicator(val desktopInstancePool: DesktopInstancePool): HealthIndicator {

    override fun health(): Health {
        val h = Health.Builder()
        var isAnyProcessDead = false
        desktopInstancePool.servers.stream().forEach {
            if (it.process.isAlive) {
                h.withDetail("message", "${it.logPrefix} server is running")
            } else {
                isAnyProcessDead = true
                h.withDetail("message", "${it.logPrefix} server is not running")
            }
        }
        if (isAnyProcessDead) {
            h.down()
        } else {
            h.up()
        }
        return h.build()
    }
}