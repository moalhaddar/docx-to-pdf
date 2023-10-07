package dev.alhaddar.docxtopdf.proxy

import com.sun.star.lang.XComponent
import dev.alhaddar.docxtopdf.pool.DesktopDescriptor
import dev.alhaddar.docxtopdf.pool.DesktopInstancePool
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.stereotype.Component
import org.springframework.web.context.WebApplicationContext


@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
class RequestScopedDesktopInstance(
    private val desktopInstancePool: DesktopInstancePool

) {
    private val desktopDescriptor: DesktopDescriptor = desktopInstancePool.borrow()
    val desktopInstance: XComponent = desktopDescriptor.desktopInstance

    @PreDestroy
    fun onDestroy() {
        desktopInstancePool.giveBack(desktopDescriptor)
    }
}