package net.torosaba.marumasign.companion

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 3D 表示のデータ (marumasign/<地図>.json) の設定の項目 */
internal class WebExporterTest {

    private fun export(dir: Path, settings: Settings) =
        dir.resolve("overworld.json").also { WebExporter.write(it, emptyList(), settings) }
            .let { JsonParser.parseString(Files.readString(it)).asJsonObject }

    @Test
    fun boardsAreHiddenAndLabelsAreOffByDefault(@TempDir dir: Path) {
        val json = export(dir, Settings())
        assertTrue(json.get("hideBoards").asBoolean)
        assertFalse(json.get("labels").asBoolean)
        assertEquals(0, json.getAsJsonArray("signs").size())
    }

    @Test
    fun settingsAreWritten(@TempDir dir: Path) {
        val json = export(dir, Settings().apply { web3dHideBoards = false; web3dLabels = true })
        assertFalse(json.get("hideBoards").asBoolean)
        assertTrue(json.get("labels").asBoolean)
    }
}
