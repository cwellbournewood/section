package io.section.edge.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boundary correctness for [Chunker]. The interesting cases are not
 * "split at exactly 256kB" (a one-liner) but the boundary preferences
 * (paragraph > line > sentence > word > hard) and offset accuracy when
 * reconstructing the original text by concatenation.
 */
class ChunkerTest {

    @Test
    fun `empty input produces no chunks`() {
        assertThat(Chunker.split("")).isEmpty()
    }

    @Test
    fun `single chunk for small input`() {
        val text = "hello world"
        val chunks = Chunker.split(text)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].text).isEqualTo(text)
        assertThat(chunks[0].start).isZero
        assertThat(chunks[0].end).isEqualTo(text.length)
    }

    @Test
    fun `chunks reconstruct the original text exactly`() {
        val text = buildString {
            repeat(50) {
                append("Paragraph $it line 1.\nLine 2 with more content here.\n\n")
            }
        }
        val chunks = Chunker.split(text, maxBytes = 80)
        // Reassembly must equal the original; offsets must be contiguous.
        val reassembled = chunks.joinToString("") { it.text }
        assertThat(reassembled).isEqualTo(text)
        var cursor = 0
        for (c in chunks) {
            assertThat(c.start).isEqualTo(cursor)
            cursor = c.end
        }
        assertThat(cursor).isEqualTo(text.length)
    }

    @Test
    fun `prefers paragraph break over line break`() {
        val text = "first line\nsecond line\n\nthird para\nmore"
        val chunks = Chunker.split(text, maxBytes = 30)
        // The break should land at the \n\n (offset 23), not on the
        // line-break at offset 11.
        assertThat(chunks[0].text).isEqualTo("first line\nsecond line\n\n")
    }

    @Test
    fun `prefers line break over sentence break`() {
        val text = "one. two.\nthree. four."
        val chunks = Chunker.split(text, maxBytes = 12)
        assertThat(chunks[0].text).endsWith("\n")
    }

    @Test
    fun `prefers sentence break over arbitrary whitespace`() {
        val text = "alpha beta. gamma delta epsilon."
        val chunks = Chunker.split(text, maxBytes = 16)
        // The sentence terminator `. ` lands at offset 11; the next
        // whitespace before that is offset 5 (`alpha `).
        assertThat(chunks[0].text).isEqualTo("alpha beta. ")
    }

    @Test
    fun `falls back to whitespace break when no sentence break`() {
        val text = "alpha beta gamma delta epsilon"
        val chunks = Chunker.split(text, maxBytes = 14)
        // Some whitespace break must be honoured.
        // AssertJ's character assert has no isWhitespace(), so assert on the
        // Kotlin stdlib predicate instead.
        assertThat(chunks[0].text.last().isWhitespace()).isTrue()
    }

    @Test
    fun `hard-cuts when no whitespace at all fits in budget`() {
        val text = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val chunks = Chunker.split(text, maxBytes = 5)
        // Must produce at least one chunk and make forward progress.
        assertThat(chunks).isNotEmpty
        assertThat(chunks[0].text.length).isLessThanOrEqualTo(5)
        // Concatenated chunks rebuild the input.
        assertThat(chunks.joinToString("") { it.text }).isEqualTo(text)
    }

    @Test
    fun `handles multi-byte UTF-8 without exceeding byte budget`() {
        // Each non-ASCII codepoint takes 3 UTF-8 bytes. With a 6-byte
        // budget we expect 2 codepoints per chunk maximum.
        val text = "ééééééé"
        val chunks = Chunker.split(text, maxBytes = 6)
        for (c in chunks) {
            assertThat(c.text.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(6)
        }
        assertThat(chunks.joinToString("") { it.text }).isEqualTo(text)
    }

    @Test
    fun `handles surrogate pair without splitting it`() {
        // U+1F600 GRINNING FACE — 4 bytes in UTF-8, 2 chars in Kotlin.
        val face = "😀"
        val text = "$face$face$face"
        val chunks = Chunker.split(text, maxBytes = 5)
        // We must never end up with a chunk holding only a high
        // surrogate.
        for (c in chunks) {
            val s = c.text
            if (s.isNotEmpty()) {
                val last = s.last()
                assertThat(Character.isHighSurrogate(last)).isFalse()
            }
        }
        assertThat(chunks.joinToString("") { it.text }).isEqualTo(text)
    }

    @Test
    fun `large input splits into many chunks under default budget`() {
        // ~1MB of plain ASCII split into 256kB-ish pieces.
        val text = "x".repeat(1_000_000)
        val chunks = Chunker.split(text)
        assertThat(chunks).hasSizeGreaterThan(1)
        for (c in chunks) {
            assertThat(c.text.toByteArray(Charsets.UTF_8).size)
                .isLessThanOrEqualTo(Chunker.DEFAULT_MAX_BYTES)
        }
    }
}
