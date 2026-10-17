package dev.finalish.core.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real files written by python-docx / openpyxl / python-pptx (src/test/resources/office). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OfficePreviewTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource("office/$name")!!.toURI())

    @Test fun word() {
        val d = OfficePreview.read(res("sample.docx"), "docx") as OfficeDoc.Document
        assertEquals(OfficeDoc.Block("Shooting schedule", OfficeDoc.Block.Style.Title), d.blocks.first())
        assertTrue(d.blocks.any { it.text == "Day 1" && it.style == OfficeDoc.Block.Style.Heading })
        assertTrue(d.blocks.any { it.text == "Camera A: FX6" && it.style == OfficeDoc.Block.Style.Bullet })
        assertEquals(1, d.blocks.count { it.style == OfficeDoc.Block.Style.PageBreak })
    }

    @Test fun excel() {
        val s = OfficePreview.read(res("sample.xlsx"), "xlsx") as OfficeDoc.Sheet
        assertEquals("Budget", s.name)
        assertEquals(1, s.otherSheets)
        assertEquals(listOf("Item", "Days", "Cost"), s.rows[0])
        assertEquals(listOf("Total", "", "2000"), s.rows[3])
    }

    @Test fun powerPoint() {
        val p = OfficePreview.read(res("sample.pptx"), "pptx") as OfficeDoc.Slides
        assertEquals(listOf(listOf("Treatment", "Mood: warm, handheld"), listOf("Locations", "Harbour at dawn")), p.slides)
    }

    @Test fun plainTextParagraphs() {
        val d = OfficePreview.text("﻿Scene 1\nINT. KITCHEN\r\n\r\n  \nScene 2\n")
        assertEquals(listOf("Scene 1\nINT. KITCHEN", "Scene 2"), d.blocks.map { it.text })
    }

    @Test fun csvWithQuotes() {
        val s = OfficePreview.table("Shot,Note\nA001,\"wide, slow\"\nA002,\"says \"\"cut\"\"\nhere\"\n", ',', "CSV")
        assertEquals(listOf("Shot", "Note"), s.rows[0])
        assertEquals(listOf("A001", "wide, slow"), s.rows[1])
        assertEquals(listOf("A002", "says \"cut\"\nhere"), s.rows[2])
        assertEquals(3, s.rows.size)
    }

    @Test fun firstSheetFollowsWorkbookRels() {
        val workbook = """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
            """<sheet name="Budget" sheetId="3" r:id="rId3"/><sheet name="Old" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val sheets = OfficePreview.workbookSheets(workbook)
        assertEquals(listOf("Budget" to "rId3", "Old" to "rId1"), sheets)
        val entries = listOf("xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")
        val rels = { target: String ->
            """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/>""" +
                """<Relationship Id="rId3" Target="$target"/></Relationships>"""
        }
        assertEquals("xl/worksheets/sheet2.xml", OfficePreview.firstSheetPath("rId3", rels("worksheets/sheet2.xml"), entries))
        assertEquals("xl/worksheets/sheet2.xml", OfficePreview.firstSheetPath("rId3", rels("/xl/worksheets/sheet2.xml"), entries))
    }

    @Test fun firstSheetFallsBackToLowestNumber() {
        val entries = listOf("xl/worksheets/sheet10.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/_rels/sheet2.xml.rels")
        assertEquals("xl/worksheets/sheet2.xml", OfficePreview.firstSheetPath(null, null, entries))
        val rels = """<Relationships><Relationship Id="rId1" Target="worksheets/missing.xml"/></Relationships>"""
        assertEquals("xl/worksheets/sheet2.xml", OfficePreview.firstSheetPath("rId1", rels, entries))
    }
}
