package dev.alhaddar.docxtopdf

import dev.alhaddar.docxtopdf.service.UnoService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DocxToPdfKotlinApplication

fun main(args: Array<String>) {
	runApplication<DocxToPdfKotlinApplication>(*args)
}

inline fun <reified T> T.logger(): Logger {
	return LoggerFactory.getLogger(T::class.java)
}