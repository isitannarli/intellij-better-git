package dev.bettergit.ui

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReportFileSaverTest : BasePlatformTestCase() {
    fun testNativeSaveDescriptorKeepsItsTitleDescriptionAndHtmlFilter() {
        val descriptor = ReportFileSaver.create("Export report", "Save repository activity", "html")
        assertEquals("Export report", descriptor.title)
        assertEquals("Save repository activity", descriptor.description)
        assertTrue(descriptor.isFileVisible(LightVirtualFile("report.html", PlainTextFileType.INSTANCE, ""), true))
        assertFalse(descriptor.isFileVisible(LightVirtualFile("report.txt", PlainTextFileType.INSTANCE, ""), true))
    }
}
