package dev.alhaddar.docxtopdf.rest

import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.service.UnoService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class PdfController(val unoService: UnoService) {
    val logger = logger()

    @CrossOrigin
    @RequestMapping(value= ["/pdf", "/docx-to-pdf"], method = [RequestMethod.POST])
    fun getPdf(@RequestParam("document") file: MultipartFile): ResponseEntity<ByteArray>{
        logger.info("PDF Request. Docx file size: ${file.inputStream.readBytes().size} bytes.")

        val pdf = unoService.convert(file.inputStream, false)

        logger.info("Successfully generated PDF. File Size: ${pdf.size} bytes.")

        return ResponseEntity
            .status(200)
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf)
    }
}