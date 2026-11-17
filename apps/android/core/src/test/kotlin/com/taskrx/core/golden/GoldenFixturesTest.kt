package com.taskrx.core.golden

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The gate on the golden fixtures (ADR 0017). Every [GoldenSet] is regenerated in memory and
 * compared with its checked-in file. `./gradlew :core:test -PupdateGoldens` writes them
 * instead; review the diff, because it is what the Rust port must now reproduce.
 */
class GoldenFixturesTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    private val dir = File(System.getProperty("taskrx.goldenDir") ?: error("taskrx.goldenDir is not set"))
    private val update = System.getProperty("taskrx.updateGoldens") == "true"

    private fun render(set: GoldenSet): String {
        val cases = set.cases()
        val names = cases.map { it.name }
        assertEquals(names.size, names.toSet().size, "${set.name}: case names must be unique")
        assertTrue(cases.isNotEmpty(), "${set.name}: no cases")
        val document =
            buildJsonObject {
                put("rule", set.name)
                put("about", set.about)
                put("generatedBy", "apps/android/core/src/test/kotlin/com/taskrx/core/golden (./gradlew :core:test -PupdateGoldens)")
                put(
                    "cases",
                    JsonArray(
                        cases.map { case ->
                            buildJsonObject {
                                put("name", case.name)
                                put("input", case.input)
                                put("output", case.output)
                            }
                        },
                    ),
                )
            }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), document) + "\n"
    }

    @Test
    fun `every golden file is what core answers today`() {
        val names = goldenSets.map { it.name }
        assertEquals(names.size, names.toSet().size, "two golden sets share a name")
        dir.mkdirs()

        val stale = mutableListOf<String>()
        for (set in goldenSets) {
            val file = File(dir, "${set.name}.json")
            val fresh = render(set)
            if (update) {
                if (!file.exists() || file.readText() != fresh) file.writeText(fresh)
            } else if (!file.exists()) {
                stale += "${file.name} is missing"
            } else if (file.readText() != fresh) {
                stale += "${file.name} differs from what :core answers"
            }
        }

        val orphans = dir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.filter { it !in names }
        if (update) orphans.forEach { File(dir, "$it.json").delete() } else orphans.forEach { stale += "$it.json has no golden set" }

        if (stale.isNotEmpty()) {
            fail(
                "Golden fixtures are out of date:\n  " + stale.joinToString("\n  ") +
                    "\nRegenerate with `./gradlew :core:test -PupdateGoldens`, review the diff, and update the Rust port to match.",
            )
        }
    }
}
