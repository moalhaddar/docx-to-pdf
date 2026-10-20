package dev.alhaddar.docxtopdf.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.RSAKeyProvider
import dev.alhaddar.docxtopdf.logger
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.io.ByteArrayInputStream
import org.json.JSONObject

class VerifyJWT(
    private val secret: String = "",  // Made optional with an empty default value
    private val algorithm: String = "RS256",
    private val publicKey: String = "",
    private val provider: String = "https://auth.praxysante.fr/",
    private val enabled: Boolean = true,
) {
    val logger = logger()
    
    // Check if authentication is possible with the provided parameters
    private val isAuthConfigValid: Boolean by lazy {
        if (!enabled) return@lazy false
        
        (secret.isNotEmpty() || publicKey.isNotEmpty()).also { isValid ->
            if (!isValid) {
                logger.warn("Invalid authentication configuration: available keys=${if (secret.isNotEmpty()) "secret" else ""}${if (publicKey.isNotEmpty()) ", publicKey" else ""}")
            }
        }
    }

    private fun createAlgorithm(alg: String): Algorithm? {
        if (!isAuthConfigValid) return null
        
        try {
            return when (alg) {
                "HS256" -> {
                    if (secret.isEmpty()) {
                        logger.warn("Algorithm HS256 requested but no secret key configured")
                        null
                    } else {
                        Algorithm.HMAC256(secret)
                    }
                }
                "RS256" -> {
                    if (publicKey.isEmpty()) {
                        logger.warn("Algorithm RS256 requested but no public key configured")
                        null
                    } else {
                        val keyProvider = object : RSAKeyProvider {
                            override fun getPublicKeyById(keyId: String?): RSAPublicKey {
                                return parsePublicKey(publicKey)
                            }

                            override fun getPrivateKey(): RSAPrivateKey? {
                                return if (secret.isNotEmpty()) {
                                    try {
                                        val keyFactory = KeyFactory.getInstance("RSA")
                                        val privateKeySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(secret))
                                        keyFactory.generatePrivate(privateKeySpec) as RSAPrivateKey
                                    } catch (e: Exception) {
                                        logger.error("Error processing private key: ${e.message}")
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            override fun getPrivateKeyId(): String {
                                return ""
                            }
                        }
                        
                        Algorithm.RSA256(keyProvider)
                    }
                }
                else -> {
                    logger.error("Unsupported algorithm: $alg")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Error initializing algorithm $alg: ${e.message}")
            return null
        }
    }
    
    // Function to parse and convert the public key string
    private fun parsePublicKey(pubKeyString: String): RSAPublicKey {
        val cleanKey = pubKeyString.replace("\n", "")
        
        return if (cleanKey.contains("BEGIN CERTIFICATE")) {
            logger.debug("Processing a PEM certificate")
            val normalizedPEM = normalizePEM(cleanKey)
            val cf = CertificateFactory.getInstance("X.509")
            val inputStream = ByteArrayInputStream(normalizedPEM.toByteArray())
            val cert = cf.generateCertificate(inputStream)
            cert.publicKey as RSAPublicKey
        } else {
            logger.debug("Processing a public key in Base64 format")
            val keyFactory = KeyFactory.getInstance("RSA")
            val decodedKey = Base64.getDecoder().decode(cleanKey)
            val keySpec = X509EncodedKeySpec(decodedKey)
            keyFactory.generatePublic(keySpec) as RSAPublicKey
        }
    }
    
    // Function to normalize the PEM certificate format
    private fun normalizePEM(pemString: String): String {
        logger.debug("Original certificate format: ${pemString.replace("\n", "\\n")}")
        
        // Clean the PEM certificate and format correctly
        val cleanedPem = pemString
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        
        // Reformat with standard PEM format (64 characters per line)
        return "-----BEGIN CERTIFICATE-----\n" +
               cleanedPem.chunked(64).joinToString("\n") +
               "\n-----END CERTIFICATE-----"
    }
    
    private fun createVerifier(alg: String): JWTVerifier? {
        val jwtAlgorithm = createAlgorithm(alg) ?: return null
        
        try {
            return JWT.require(jwtAlgorithm).withIssuer(provider).build()
        } catch (e: Exception) {
            logger.error("Error creating JWT verifier: ${e.message}")
            return null
        }
    }
    
    // Extract algorithm from JWT token
    private fun extractAlgorithmFromToken(token: String): String {
        try {
            // Get the header (first part of the token)
            val parts = token.split(".")
            if (parts.size < 2) {
                logger.warn("Invalid token format, unable to extract header")
                return algorithm // Fallback to configured algorithm
            }
            
            // Decode the Base64 header
            val header = try {
                val headerJson = String(Base64.getUrlDecoder().decode(parts[0]))
                JSONObject(headerJson)
            } catch (e: Exception) {
                logger.warn("Unable to decode JWT header: ${e.message}")
                return algorithm
            }
            
            // Extract algorithm from header
            val tokenAlg = header.optString("alg", "")
            if (tokenAlg.isEmpty()) {
                logger.warn("Algorithm not specified in JWT header")
                return algorithm
            }
            
            logger.info("Algorithm detected in token: $tokenAlg")
            return when (tokenAlg) {
                "HS256" -> "HS256"
                "RS256" -> "RS256"
                else -> {
                    logger.warn("Algorithm '$tokenAlg' not supported, using default algorithm: $algorithm")
                    algorithm
                }
            }
        } catch (e: Exception) {
            logger.error("Error extracting algorithm from token: ${e.message}")
            return algorithm
        }
    }

    data class TokenVerificationResult(
        val valid: Boolean,
        val code: Int,
        val message: String
    )

    fun verifyToken(token: String): TokenVerificationResult {
        logger.info("_______________________________________________________________________________________________")
        logger.info("Verifying JWT token, ${token.take(15)}...")
        
        if (!enabled || !isAuthConfigValid) {
            logger.warn("Authentication disabled or invalid configuration - token accepted by default")
            return TokenVerificationResult(true, 200, "Authentication disabled or invalid configuration")
        }
        
        // Detect algorithm from token
        val detectedAlgorithm = extractAlgorithmFromToken(token)
        logger.info("Using algorithm: $detectedAlgorithm for verification")
        
        // Create verifier with detected algorithm
        val verifier = createVerifier(detectedAlgorithm)
        if (verifier == null) {
            logger.warn("Unable to create verifier with algorithm $detectedAlgorithm - token accepted by default")
            return TokenVerificationResult(true, 200, "Verifier could not be created")
        }
        
        return try {
            verifier.verify(token)
            TokenVerificationResult(
                valid = true,
                code = 200,
                message = "Token is valid"
            )
        } catch (exception: JWTVerificationException) {
            if (exception.message == "The Token has expired") {
                logger.warn("JWT token expired")
                TokenVerificationResult(
                    valid = false,
                    code = 401,
                    message = "Token expired"
                )
            } else {
                logger.warn("Invalid JWT token: ${exception.message}")
                TokenVerificationResult(
                    valid = false,
                    code = 403,
                    message = "Invalid token: ${exception.message}"
                )
            }
        }
    }
}