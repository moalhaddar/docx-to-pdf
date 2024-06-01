package dev.alhaddar.docxtopdf.exception

import java.lang.Exception

class LibreOfficeDeadProcessException:
    Exception("LibreOffice process is dead/disposed. Try restarting the service and try again.") {
}