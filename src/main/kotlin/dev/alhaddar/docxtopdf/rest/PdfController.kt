package dev.alhaddar.docxtopdf.rest

import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.service.UnoService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.outputStream

@RestController
class PdfController(val unoService: UnoService) {
    val logger = logger()
    @PostMapping("/pdf")
    fun getPdf(@RequestParam("document") file: MultipartFile): ResponseEntity<ByteArray>{
        logger.info("PDF Request. Docx file size: ${file.inputStream.readBytes().size} bytes.")

        val pdf = unoService.convert(file.inputStream)

        logger.info("Successfully generated PDF. File Size: ${pdf.size} bytes.")

        return ResponseEntity
            .status(200)
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf)
    }
}