package com.abk.brodue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun headings_levels() {
        val blocks = ReleaseNotes.parse("# Big\n## Mid\n### Small")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as ReleaseNotes.Block.Heading).level)
        assertEquals(2, (blocks[1] as ReleaseNotes.Block.Heading).level)
        assertEquals(3, (blocks[2] as ReleaseNotes.Block.Heading).level)
        assertEquals("Big", (blocks[0] as ReleaseNotes.Block.Heading).runs.single().text)
    }

    @Test
    fun bullets_numbered_quote_rule() {
        val blocks = ReleaseNotes.parse("- a\n* b\n1. one\n2) two\n> cited\n---")
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is ReleaseNotes.Block.Bullet)
        assertTrue(blocks[1] is ReleaseNotes.Block.Bullet)
        assertTrue(blocks[2] is ReleaseNotes.Block.Numbered)
        assertTrue(blocks[3] is ReleaseNotes.Block.Numbered)
        assertTrue(blocks[4] is ReleaseNotes.Block.Quote)
        assertTrue(blocks[5] is ReleaseNotes.Block.Rule)
        assertEquals("a", (blocks[0] as ReleaseNotes.Block.Bullet).runs.single().text)
    }

    @Test
    fun inline_bold_italic_code_link() {
        val runs = ReleaseNotes.inlineRuns("Fix **crash on open** and *slope* plus `code()` and [notes](https://x)")
        assertEquals(7, runs.size)
        assertEquals("Fix ", runs[0].text)
        assertEquals("crash on open", runs[1].text)
        assertTrue(runs[1].bold)
        assertEquals(" and ", runs[2].text)
        assertEquals("slope", runs[3].text)
        assertTrue(runs[3].italic)
        assertEquals(" plus ", runs[4].text)
        assertEquals("code()", runs[5].text)
        assertTrue(runs[5].code)
        // link text merges into surrounding plain text, URL is dropped
        assertEquals(" and notes", runs[6].text)
        assertTrue(runs.none { it.text.contains("https://") })
    }

    @Test
    fun code_blocks_and_blanks() {
        val blocks = ReleaseNotes.parse("Intro\n\n```\nline1\nline2\n```\n\nOutro")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is ReleaseNotes.Block.Para)
        assertEquals("line1", (blocks[1] as ReleaseNotes.Block.Code).text)
        assertEquals("line2", (blocks[2] as ReleaseNotes.Block.Code).text)
        assertTrue(blocks[3] is ReleaseNotes.Block.Para)
    }

    @Test
    fun typical_release_notes_shape() {
        val md = "## What's Changed\n- Fix **login crash** by @dev\n- Polish *empty state*\n\n**Full Changelog**: https://github.com/x/compare"
        val blocks = ReleaseNotes.parse(md)
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is ReleaseNotes.Block.Heading)
        val bold = (blocks[1] as ReleaseNotes.Block.Bullet).runs
        assertEquals("login crash", bold[1].text)
        assertTrue(bold[1].bold)
    }
}
