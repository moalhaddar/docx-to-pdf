package dev.alhaddar.docxtopdf.rest

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.policy.ParagraphRenderPolicy
import com.deepoove.poi.policy.RenderPolicy
import com.deepoove.poi.template.ElementTemplate
import com.deepoove.poi.template.run.RunTemplate
import dev.alhaddar.docxtopdf.logger
import dev.alhaddar.docxtopdf.service.UnoService
import dev.alhaddar.docxtopdf.wrappers.Document
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.TableRowHeightRule
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger


@RestController
class PdfController(val unoService: UnoService) {
    val logger = logger()
    @PostMapping("/pdf")
    fun getPdf(@RequestParam("document") file: MultipartFile): ResponseEntity<ByteArray>{
        logger.info("PDF Request. Docx file size: ${file.inputStream.readBytes().size} bytes.")

//        val config = Configure
//            .builder()
//            .addPlugin('$', ParagraphRenderPolicy())
//            .addPlugin('#', RenderPolicy() {
//                elementTemplate: ElementTemplate?, obj: Any?, xwpfTemplate: XWPFTemplate? ->
//                run {
//                    ((elementTemplate as RunTemplate).run.setText("", 0))
//                    if (xwpfTemplate != null) {
//                        val table = xwpfTemplate.xwpfDocument
//                            .createTable(1, 2)
//
//                        table.removeBorders()
//
////                        table.setCellMargins(0, 0, 0, 0)
//
//                        val firstRow = table.getRow(0);
//                        firstRow.heightRule = TableRowHeightRule.AUTO
//
//                        val cell = firstRow.getCell(0)
//                        cell.verticalAlignment = XWPFTableCell.XWPFVertAlign.TOP
//                        val cellProps = cell.ctTc.addNewTcPr();
//                        val rightBorder = cellProps.addNewTcBorders().addNewRight();
//                        rightBorder.`val` = STBorder.SINGLE;
//                        rightBorder.color = "FF00FF"
//                        val margin = if (cellProps.isSetTcMar) cellProps.tcMar else cellProps.addNewTcMar()
//                        margin.addNewLeft()
//                        margin.left.w = BigInteger.ZERO;
//
//                        val para1 = cell.getParagraphArray(0);
//                        para1.alignment = ParagraphAlignment.LEFT
//                        para1.ctp.addNewPPr().addNewShd().fill = "FF00FF"
//
//
//                        val warba = para1.createRun()
//                        warba.setText("Warba Bank")
//                        warba.setFontSize(20)
//                        warba.setFontFamily("BrownStd")
//                        warba.color = "1C0054"
//                        para1.spacingAfter = 0;
//                        para1.spacingBefore = 0;
//
//
//                        val call1 = firstRow.getCell(1)
//                        val cell1Props = call1.ctTc.addNewTcPr();
//                        val rightBorder1 = cell1Props.addNewTcBorders().addNewTl2Br()
//                        val margin1 = if (cell1Props.isSetTcMar) cell1Props.tcMar else cell1Props.addNewTcMar()
//                        margin1.addNewRight()
//                        margin1.right.w = BigInteger.ZERO;
//                        call1.verticalAlignment = XWPFTableCell.XWPFVertAlign.TOP
//                        rightBorder1.`val` = STBorder.SINGLE;
//                        rightBorder1.color = "FF00FF"
//
//                        val para2 = call1.getParagraphArray(0);
//                        para2.alignment = ParagraphAlignment.RIGHT
//
//
//                        val warba2 = para2.createRun()
//                        warba2.setText("A better solution is here")
//                        warba2.setFontSize(20)
//                        warba2.setFontFamily("BrownStd")
//                        warba2.color = "1C0054"
//
//
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.pgMar.top = BigInteger.valueOf(0)
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.pgMar.bottom = BigInteger.valueOf(100)
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.pgMar.left = BigInteger.valueOf(100)
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.pgMar.right = BigInteger.valueOf(100)
//
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.addNewPgBorders().addNewBottom().`val` = STBorder.SINGLE
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.addNewPgBorders().addNewTop().`val` = STBorder.SINGLE
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.addNewPgBorders().addNewRight().`val` = STBorder.SINGLE
//                        xwpfTemplate.xwpfDocument.document.body.sectPr.addNewPgBorders().addNewLeft().`val` = STBorder.SINGLE
//                    }
//                }
//            })
//            .build();

        val outputStream = ByteArrayOutputStream();


//        XWPFTemplate
//            .compile(file.inputStream, config)
//            .render(mapOf(
//                ("test" to "بنك وربة")
//            ))
//            .write(outputStream);

        Document()
            .setBorders(top = true, right = true, bottom = true , left = true)
            .setMargins(top = 300.0, right = 300.0 , bottom =  300.0, left =  300.0)
            .getXwpfDocument()
            .write(outputStream)

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())

        val pdf = unoService.convert(inputStream)

        logger.info("Successfully generated PDF. File Size: ${pdf.size} bytes.")

        return ResponseEntity
            .status(200)
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf)
    }
}