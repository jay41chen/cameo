package io.github.jay41chen.cameo.ui

import java.io.File
import java.util.UUID

/** 相機暫存檔的存取。ViewModel 只透過它操作檔案，所以能在 JVM 測試。 */
interface CaptureFiles {
    /** 建立一個新的暫存檔路徑（檔案本身由相機寫入）。 */
    fun newFile(): String
    fun exists(path: String): Boolean
    fun delete(path: String)

    /** 刪掉暫存目錄中除 [keep] 以外的檔案。 */
    fun deleteOthers(keep: String?)
}

/** 暫存檔放在 [dir]（`cacheDir/captures`）；只會刪除該目錄內的檔案。 */
class CaptureFilesImpl(private val dir: File) : CaptureFiles {
    override fun newFile(): String {
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.jpg").path
    }

    override fun exists(path: String): Boolean = inside(path)?.isFile == true

    override fun delete(path: String) {
        inside(path)?.delete()
    }

    override fun deleteOthers(keep: String?) {
        val keepFile = keep?.let { File(it).absoluteFile }
        dir.listFiles()?.forEach { if (it.absoluteFile != keepFile) it.delete() }
    }

    private fun inside(path: String): File? {
        val f = File(path).absoluteFile
        return if (f.parentFile == dir.absoluteFile) f else null
    }
}
