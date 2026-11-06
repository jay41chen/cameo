package com.kimhit.cameo.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureFilesImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "captures").apply { mkdirs() } }
    private val files by lazy { CaptureFilesImpl(dir) }

    private fun put(name: String) = File(dir, name).apply { writeText("x") }

    @Test
    fun deleteOthers_keepsKeepAndDeletesOthers() {
        val keep = put("keep.jpg")
        val a = put("a.jpg")
        val b = put("b.jpg")
        files.deleteOthers(keep.path)
        assertTrue(keep.exists())
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test
    fun deleteOthers_nullKeepDeletesAll() {
        put("a.jpg")
        put("b.jpg")
        files.deleteOthers(null)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun outsidePath_isNotDeleted_andDoesNotExist() {
        val outside = File(tmp.newFolder("other"), "o.jpg").apply { writeText("x") }
        files.delete(outside.path)
        assertTrue(outside.exists())
        assertFalse(files.exists(outside.path))
    }

    @Test
    fun insidePath_existsAndDeletes() {
        val f = put("in.jpg")
        assertTrue(files.exists(f.path))
        files.delete(f.path)
        assertFalse(f.exists())
    }
}
