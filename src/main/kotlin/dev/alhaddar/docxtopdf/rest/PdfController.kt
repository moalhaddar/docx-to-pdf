package dev.alhaddar.docxtopdf.rest

import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.service.UnoService
import dev.alhaddar.docxtopdf.auth.VerifyJWT
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.beans.factory.annotation.Value

@RestController
class PdfController(
    val unoService: UnoService,
    @Value("\${auth.secret}") private val authSecret: String,
    @Value("\${auth.publicKey}") private val publicKey: String,
    @Value("\${auth.algo}") private val algorithm: String,
    @Value("\${auth.enable:true}") private val authEnable: Boolean
) {
    val logger = logger()
    private val verifyJWT = VerifyJWT(
        secret = authSecret,
        algorithm = algorithm,
        publicKey = publicKey
    )

    @CrossOrigin
    @RequestMapping(value = ["/pdf", "/docx-to-pdf"], method = [RequestMethod.POST])
    fun getPdf(
        @RequestParam("document") file: MultipartFile, 
        @RequestHeader(value = "Authorization", required = false) authorizationHeader: String?
    ): ResponseEntity<ByteArray> {
        if (authEnable) {
            if (authorizationHeader == null) {
                logger.warn("JWT verification failed: Authorization header is missing")
                return ResponseEntity.status(401).build()
            }
            
            val token = authorizationHeader.replace("Bearer ", "")
            
            // The verifyToken method now automatically determines the algorithm to use
            val verificationResult = verifyJWT.verifyToken(token)
            
            if (!verificationResult.valid) {
                logger.warn("JWT verification failed: ${verificationResult.message}")
                return ResponseEntity.status(verificationResult.code).build()
            }
        } else {
            logger.info("Auth is disabled, skipping JWT verification")
        }

        logger.info("PDF Request. Docx file size: ${file.inputStream.readBytes().size} bytes.")

        val pdf = unoService.convert(file.inputStream, false)

        logger.info("Successfully generated PDF. File Size: ${pdf.size} bytes.")

        return ResponseEntity
            .status(200)
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf)
    }
}