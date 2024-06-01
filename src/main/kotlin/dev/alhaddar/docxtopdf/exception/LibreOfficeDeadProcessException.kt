package dev.alhaddar.docxtopdf.exception

import java.lang.Exception

class LibreOfficeDeadProcessException:
    Exception("LibreOffice process is dead.") {
}