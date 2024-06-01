package dev.alhaddar.docxtopdf.exception

import java.lang.Exception

class DocumentNotLoadedNullException()
    : Exception("XDocument is null. This indicates that the file was not loaded successfully by LibreOffice.")
