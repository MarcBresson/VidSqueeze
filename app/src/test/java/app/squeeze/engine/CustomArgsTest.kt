package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomArgsTest {
    @Test
    fun tokenizesQuotesAndEscapes() {
        assertEquals(listOf("-a", "b c", "d e", "f\"g", "h"), CustomArgs.tokenize("""-a 'b c' "d e" "f\"g" h"""))
        assertEquals(listOf("x y", "z"), CustomArgs.tokenize("""x\ y   z"""))
        assertEquals(listOf("", "a"), CustomArgs.tokenize("'' a"))
        assertTrue(CustomArgs.tokenize("   ").isEmpty())
    }

    @Test(expected = ArgSyntaxException::class)
    fun unterminatedQuoteThrows() {
        CustomArgs.tokenize("-vf \"scale=1:1")
    }

    @Test
    fun validateFlagsForbiddenTokens() {
        assertTrue(CustomArgs.validate(listOf("-crf", "20", "-vf", "scale=1:1")).isEmpty())
        assertTrue(CustomArgs.validate(listOf("-y")).hasErrors)
        assertTrue(CustomArgs.validate(listOf("-n")).hasErrors)
        assertTrue(CustomArgs.validate(listOf("result.mkv")).hasErrors)
    }

    @Test
    fun formatterQuotesOnlyWhenNeeded() {
        assertEquals("ffmpeg -i a.mp4 -vf 'a b' 'it'\\''s'", CommandFormatter.format(listOf("-i", "a.mp4", "-vf", "a b", "it's")))
    }
}
