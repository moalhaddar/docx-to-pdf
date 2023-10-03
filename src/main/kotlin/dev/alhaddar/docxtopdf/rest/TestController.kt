package dev.alhaddar.docxtopdf.rest

import dev.alhaddar.docxtopdf.service.UnoService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class TestController {

    @GetMapping("/")
    fun test(){
        UnoService().convert();
    }
}