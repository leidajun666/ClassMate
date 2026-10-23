package com.classmate.timetable.importer

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 真 Excel(.xlsx) 读取：自己解 zip + 读 sheet XML，不引第三方库。
 * 只认 sheet1（教务导出通常就一张表），够用且体积为零。
 */
object XlsxReader {

    fun readSheet(bytes: ByteArray): List<List<String>> {
        val files = unzip(bytes)
        val sheetName = files.keys.firstOrNull { Regex("^xl/worksheets/sheet\\d+\\.xml$").matches(it) }
            ?: return emptyList()
        val shared = files["xl/sharedStrings.xml"]?.let { parseSharedStrings(decode(it)) } ?: emptyList()
        return parseSheet(decode(files[sheetName]!!), shared)
    }

    private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = runCatching { zis.readBytes() }.getOrDefault(ByteArray(0))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun parseSharedStrings(xml: String): List<String> =
        Regex("<si>([\\s\\S]*?)</si>").findAll(xml).map { m ->
            Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(m.groupValues[1])
                .joinToString("") { xmlUnescape(it.groupValues[1]) }
        }.toList()

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val grid = mutableListOf<List<String>>()
        Regex("<row\\b[^>]*>([\\s\\S]*?)</row>").findAll(xml).forEach { rowMatch ->
            val line = ArrayList<String>()
            val cellRe = Regex("<c\\b([^>]*)>([\\s\\S]*?)</c>|<c\\b([^>]*)/>")
            for (c in cellRe.findAll(rowMatch.groupValues[1])) {
                val attrs = c.groupValues[1].ifEmpty { c.groupValues[3] }
                val inner = c.groupValues[2]
                val type = Regex("t=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                val raw = Regex("<v>([\\s\\S]*?)</v>").find(inner)?.groupValues?.get(1) ?: ""
                val value = when (type) {
                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                    "inlineStr" -> Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(inner)
                        .joinToString("") { xmlUnescape(it.groupValues[1]) }
                    else -> xmlUnescape(raw)
                }
                val col = if (ref != null) colIndex(ref) else line.size
                while (line.size <= col) line.add("")
                line[col] = value.trim()
            }
            if (line.isNotEmpty()) grid.add(line)
        }
        return grid
    }

    private fun colIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    private fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
