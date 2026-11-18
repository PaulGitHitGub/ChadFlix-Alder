package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTypesTest {
    private val episode = Video("kitsu:1:1", "Episode 1", 1, 1, null, null, null)

    @Test fun otherTypesGetAPageEachInAddonOrder() =
        assertEquals(listOf("anime", "channel"), extraPageTypes(listOf("movie", "Anime", "series", "tv", "channel", "anime ", "events", "")))

    @Test fun episodesDecideForOtherTypes() {
        assertTrue(hasEpisodes("series", null))
        assertFalse(hasEpisodes("movie", listOf(episode)))
        assertFalse(hasEpisodes("tv", listOf(episode)))
        assertTrue(hasEpisodes("anime", listOf(episode)))
        assertFalse(hasEpisodes("anime", emptyList()))
        assertFalse(hasEpisodes("anime", null))
    }

    @Test fun includesSearchableCustomTypesAndKeepsSearchOnlyCatalogsOutOfBrowse() {
        val addon = Addon(org.json.JSONObject("""
            {
              "transportUrl": "https://example.com/manifest.json",
              "manifest": {
                "id": "test",
                "catalogs": [
                  {"type": "anime", "id": "find", "extra": [{"name": "search", "isRequired": true}]},
                  {"type": "anime", "id": "browse", "extra": [
                    {"name": "genre", "options": ["Action", "Drama"]},
                    {"name": "language", "options": ["Japanese", "English"], "default": "English", "isRequired": true},
                    {"name": "skip"}
                  ]}
                ]
              }
            }
        """.trimIndent()))
        assertTrue(addon.canSearch)
        val catalog = addon.catalogs.single()
        assertEquals("browse", catalog.id)
        assertEquals(mapOf("genre" to listOf("Action", "Drama"), "language" to listOf("Japanese", "English")), catalog.filters)
        assertEquals(mapOf("language" to "English"), catalog.required)
        assertTrue(catalog.pages)
    }

    @Test fun ranksTitlesInNonLatinScripts() {
        assertEquals(listOf("كثيب", "كثيب الجزء الثاني", "عنوان آخر"),
            rankSearchTitles(listOf("عنوان آخر", "كثيب الجزء الثاني", "كثيب"), "كثيب") { it })
    }
}
