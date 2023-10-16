package dev.alhaddar.docxtopdf.wrappers

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageBorder
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder

class Document {
    private var xwpfDocument: XWPFDocument;

    constructor() {
        this.xwpfDocument = XWPFDocument()
        when(xwpfDocument.document.body.isSetSectPr) { // Create an empty section
            true -> xwpfDocument.document.body.sectPr
            false -> xwpfDocument.document.body.addNewSectPr()
        }
    }
    constructor(xwpfDocument: XWPFDocument): this() {
        this.xwpfDocument = xwpfDocument;
    }

    fun getXwpfDocument(): XWPFDocument {
        return this.xwpfDocument;
    }

    fun setMargins(top: Double?, right: Double?, bottom: Double?, left: Double?): Document {
        val margin = when (xwpfDocument.document.body.sectPr.isSetPgMar) {
            true -> xwpfDocument.document.body.sectPr.pgMar
            false -> xwpfDocument.document.body.sectPr.addNewPgMar()
        }

        margin.top = top;
        margin.right = right;
        margin.bottom = bottom;
        margin.left = left;

        return this
    }

    fun setBorders(top: Boolean, right: Boolean, bottom: Boolean, left: Boolean): Document {
        val borders = when(xwpfDocument.document.body.sectPr.isSetPgBorders) {
            true -> xwpfDocument.document.body.sectPr.pgBorders
            false -> xwpfDocument.document.body.sectPr.addNewPgBorders()
        }

        setBorder(top, borders.isSetTop, {borders.top}, {borders.addNewTop()}, {borders.unsetTop()})
        setBorder(right, borders.isSetRight, {borders.right}, {borders.addNewRight()}, {borders.unsetRight()})
        setBorder(bottom, borders.isSetBottom, {borders.bottom}, {borders.addNewBottom()}, {borders.unsetBottom()})
        setBorder(left, borders.isSetLeft, {borders.left}, {borders.addNewLeft()}, {borders.unsetLeft()})


        return this;
    }

    private fun setBorder(set: Boolean, borderCondition: Boolean, getBorder: () -> CTPageBorder, addNewBorder: () -> CTPageBorder, unsetBorder: () -> Unit) {
        val border = when(borderCondition) {
            true -> getBorder()
            false -> addNewBorder()
        }

        when(set) {
            true -> border.`val` = STBorder.SINGLE
            false -> unsetBorder()
        }
    }
}