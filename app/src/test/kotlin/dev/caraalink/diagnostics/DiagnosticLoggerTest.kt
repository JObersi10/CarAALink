package dev.caraalink.diagnostics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DiagnosticLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lines from concurrent writers all reach the exported file in per-writer order`() {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(tmp.root)
        DiagnosticLogger.clearAll(context)

        val writers = (0 until 4).map { t ->
            Thread { DiagnosticLogger(context, "t$t").let { log -> repeat(500) { log.log("line $it") } } }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join() }

        val lines = DiagnosticLogger(context, "export").latestLogFile().readLines()
        for (t in 0 until 4) {
            val mine = lines.filter { "[t$t] " in it }.map { it.substringAfter("line ").toInt() }
            assertEquals((0 until 500).toList(), mine)
        }
    }
}
