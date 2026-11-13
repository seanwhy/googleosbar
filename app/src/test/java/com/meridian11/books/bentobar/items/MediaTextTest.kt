package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.MediaText.Controls
import com.meridian11.books.bentobar.items.MediaText.Glyph
import com.meridian11.books.bentobar.items.MediaText.Phase
import com.meridian11.books.bentobar.items.MediaText.Show
import com.meridian11.books.bentobar.items.MediaText.Track
import com.meridian11.books.bentobar.items.MediaText.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * What Now playing says, in the bar and in its menu: every example of the product and design
 * tables, to the character, and the promises a title from another app must not break (one line,
 * never more characters than asked for, never half an emoji).
 *
 * Characters that can't be seen in an editor, or that sit on the letter before them (a joiner, a
 * selector, an accent, a mark that turns the text around), are written here by their numbers ([u]),
 * never as themselves: the file then shows what it holds. The last test checks that.
 */
class MediaTextTest {
    /** The app's own text files, so the rules are run with the words the app shows, not a copy of them. */
    private val texts = StringFiles.read(listOf("strings.xml", "strings_media.xml")).strings

    /** The two words that are older than this item and live in strings.xml. */
    private val shared = mapOf(Word.PAUSED to ("common_paused" to "Paused"), Word.STARTING to ("status_starting" to "Starting…"))

    private val words = MediaText.Words { word -> texts.getValue(shared[word]?.first ?: ("media_" + word.name.lowercase())) }

    /** A text of the characters with these numbers. */
    private fun u(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    /** The first [n] characters of [text], as "Longest title" counts them. */
    private fun first(text: String, n: Int) = String(text.codePoints().toArray(), 0, n)

    private val nl = u(0x0A)
    private val tab = u(0x09)
    private val note = u(0x1F3B5)
    private val zwj = u(0x200D)
    private val zwnj = u(0x200C)
    /** "Draw the character before me as a picture." */
    private val selector = u(0xFE0F)
    /** An accent that goes on the letter before it. */
    private val acute = u(0x0301)
    private val family = u(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466)
    private val thumb = u(0x1F44D, 0x1F3FD)
    private val germany = u(0x1F1E9, 0x1F1EA)
    private val france = u(0x1F1EB, 0x1F1F7)
    private val keycap = u('1'.code, 0xFE0F, 0x20E3)
    private val england = u(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F)
    private val kiss = u(0x1F469, 0x1F3FD, 0x200D, 0x2764, 0xFE0F, 0x200D, 0x1F48B, 0x200D, 0x1F468, 0x1F3FB)
    /** Arabic "marhaban bik", written with its vowel marks: nine and two characters, four of them marks. */
    private val marhaban = u(0x0645, 0x064E, 0x0631, 0x0652, 0x062D, 0x064E, 0x0628, 0x064B, 0x0627) + " " + u(0x0628, 0x0643)
    /** Hindi "namaste duniya": the s and the t of "namaste" are joined into one syllable by the sign between them (the fourth character). */
    private val namaste = u(0x0928, 0x092E, 0x0938, 0x094D, 0x0924, 0x0947) + " " + u(0x0926, 0x0941, 0x0928, 0x093F, 0x092F, 0x093E)
    /** The Korean syllable "han" and the syllable "geul", each written as its three sounds. */
    private val han = u(0x1112, 0x1161, 0x11AB)
    private val geul = u(0x1100, 0x1173, 0x11AF)
    /** "Beyonce" with the accent as a character of its own. */
    private val beyonce = "Beyonce$acute"

    private val blue = Track("Blue in Green", "Miles Davis", "Spotify", "Kind of Blue")
    private val sinfonia = Track("Sinfonia Concertante in E-flat major", "Mozart", "Spotify")
    private val clocks = Track("Clocks", "Coldplay", "Spotify")
    private val bohemian = Track("Bohemian Rhapsody", "Queen", "Spotify")
    private val soWhat = Track("So What", "Miles Davis", "Spotify")
    private val video = Track("", "", "Chrome")

    private fun text(track: Track, show: Show = Show.TITLE, max: Int = 20) = MediaText.text(track, show, max, words)

    // ---- the cut

    @Test fun aTitleThatFitsStaysAsItIs() {
        assertEquals("Blue in Green", MediaText.cut("Blue in Green", 20))
        assertEquals(13, MediaText.count("Blue in Green"))
        // Exactly as long as allowed is not too long.
        assertEquals("Sinfonia Concertante", MediaText.cut("Sinfonia Concertante", 20))
        assertEquals("", MediaText.cut("", 20))
    }

    @Test fun aLongTitleLosesItsEndToAnEllipsis() {
        val title = "Sinfonia Concertante in E-flat major"
        assertEquals("Sinfonia Concertant…", MediaText.cut(title, 20))
        assertEquals(20, MediaText.count(MediaText.cut(title, 20)))
        assertEquals("Sinfoni…", MediaText.cut(title, 8))
        assertEquals(8, MediaText.count(MediaText.cut(title, 8)))
        // One too many is enough to be cut.
        assertEquals("Sinfonia Concertant…", MediaText.cut("Sinfonia Concertante!", 20))
    }

    @Test fun noSpaceIsLeftBeforeTheEllipsis() {
        assertEquals("Kind of…", MediaText.cut("Kind of Blue, Deluxe", 9))
        assertEquals("Sinfonia…", MediaText.cut("Sinfonia Concertante", 10))
    }

    @Test fun aLengthOfNothingLeavesTheEllipsis() {
        assertEquals("…", MediaText.cut("abcdef", 1))
        assertEquals("…", MediaText.cut("abcdef", 0))
        assertEquals("…", MediaText.cut("abcdef", -5))
        assertEquals("…", MediaText.cut("abcdef", Int.MIN_VALUE))
        assertEquals("abcdef", MediaText.cut("abcdef", Int.MAX_VALUE))
    }

    @Test fun anEmojiCountsAsOneCharacterAndIsNeverCutInHalf() {
        assertEquals(1, MediaText.count(note))
        assertEquals("abcdefg…", MediaText.cut("abcdefg${note}hij", 8))
        val kept = MediaText.cut("abcdef${note}hij", 8)
        assertEquals("abcdef$note…", kept)
        assertEquals(8, MediaText.count(kept))
        // In a string it is two halves, which is why a cut by the string's own length could part them.
        assertEquals(9, kept.length)
    }

    @Test fun whatReadsAsOneCharacterStaysInOnePiece() {
        // A skin tone belongs to the hand before it.
        assertEquals("abcdef…", MediaText.cut("abcdef${thumb}xyz", 8))
        assertEquals("abcde$thumb…", MediaText.cut("abcde${thumb}xyz", 8))
        // People joined into a family are one picture: all of them or none.
        assertEquals("abc…", MediaText.cut("abc${family}xyz", 8))
        assertEquals("$family…", MediaText.cut("${family}abcdef", 8))
        // A keycap is a digit, a selector and the key around them.
        assertEquals("abcdef…", MediaText.cut("abcdef${keycap}xyz", 8))
        // A heart and the selector that makes it red.
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0x2764) + selector + "xyz", 8))
        // A flag spelled with tag characters (England) has seven parts.
        assertEquals("ab…", MediaText.cut("ab${england}cde", 8))
        assertEquals("$england…", MediaText.cut("${england}cdef", 8))
    }

    @Test fun flagsAreKeptInPairs() {
        assertEquals("abcde$germany…", MediaText.cut("abcde$germany${france}x", 8))
        assertEquals("abcdef…", MediaText.cut("abcdef$germany$france", 8))
        assertEquals("abc$germany$france…", MediaText.cut("abc$germany$france${germany}x", 8))
    }

    @Test fun anAccentStaysOnItsLetter() {
        // The e goes with its accent, or stays with it.
        assertEquals("Beyonc…", MediaText.cut("$beyonce Live at Home", 8))
        assertEquals("$beyonce…", MediaText.cut("$beyonce Live at Home", 9))
        // Arabic with its vowel marks: the seventh character has a mark on it, so six stay.
        assertEquals(first(marhaban, 6) + "…", MediaText.cut(marhaban, 8))
        // Hindi: a vowel sign stays on its consonant.
        assertEquals(first(namaste, 6) + "…", MediaText.cut(namaste, 9))
        assertEquals(first(namaste, 9) + "…", MediaText.cut(namaste, 11))
        // A letter and the sign that says "these two are apart" (Persian) stay together.
        assertEquals("abcdef…", MediaText.cut("abcdefg${zwnj}hij", 8))
        // Thai and Lao write the vowel "am" as a letter of its own, which belongs to the consonant before it.
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0x0E17, 0x0E33) + "x", 8))
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0x0E81, 0x0EB3) + "x", 8))
        // Half-width Japanese writes "ba" as "ha" and a voicing mark.
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0xFF8A, 0xFF9E) + "x", 8))
        // A sign that takes the digits after it into itself (the end of a verse) is not left empty.
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0x06DD, 0x0661, 0x0662) + " x", 8))
    }

    @Test fun aKoreanSyllableWrittenInItsPartsIsNotShownWithoutItsLastSound() {
        assertEquals("abcde…", MediaText.cut("abcde$han$han", 8))
        assertEquals("abcd$han…", MediaText.cut("abcd$han$han", 8))
        // A first sound followed by a whole syllable, in both ranges such sounds have; a last sound after a whole syllable.
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0x1112, 0xAC00) + "x", 8))
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0xA960, 0xAC00) + "x", 8))
        assertEquals("abcdef…", MediaText.cut("abcdef" + u(0xAC00, 0xD7CB) + "x", 8))
    }

    @Test fun twoConsonantsAnIndianScriptHasJoinedAreOneSyllable() {
        // "namaste": na, ma, and "ste" as one. A cut after the joining sign would leave an s that belongs to what was cut off.
        assertEquals(first(namaste, 2) + "…", MediaText.cut(namaste, 5))
        assertEquals(first(namaste, 2) + "…", MediaText.cut(namaste, 6))
        assertEquals(first(namaste, 6) + "…", MediaText.cut(namaste, 7))
        // Bengali "prem": the p and the r are one.
        assertEquals("abcde…", MediaText.cut("abcde" + u(0x09AA, 0x09CD, 0x09B0, 0x09C7, 0x09AE), 8))
        // A non-joiner after the sign says the two consonants are meant to stand apart: there the text may be cut.
        val apart = u(0x0915, 0x094D, 0x200C)
        assertEquals("ab$apart…", MediaText.cut("ab$apart" + u(0x0937) + "cdefgh", 6))
        // Tamil shows the sign as a dot and keeps its consonants apart: the same sign there joins nothing.
        val dotted = u(0x0BA4, 0x0BCD)
        assertEquals("abcde$dotted…", MediaText.cut("abcde$dotted" + u(0x0BA4) + "xyz", 8))
    }

    @Test fun aFirstCharacterThatIsTooLongLeavesOnlyTheEllipsis() {
        assertEquals(10, MediaText.count(kiss))
        assertEquals("…", MediaText.cut("$kiss Our Song", 8))
        assertEquals("$kiss…", MediaText.cut("$kiss Our Song", 11))
    }

    @Test fun rightToLeftTitlesAreCutByTheSameRule() {
        val arabic = "مرحبا بالعالم"
        assertEquals(13, MediaText.count(arabic))
        assertEquals(arabic, MediaText.cut(arabic, 20))
        assertEquals(first(arabic, 7) + "…", MediaText.cut(arabic, 8))
        val hebrew = "שלום עולם"
        assertEquals("$hebrew · Queen", text(Track(hebrew, "Queen", "Spotify"), Show.BOTH))
    }

    /** Titles a player might hand over: long, wide, in pieces, with pictures made of several parts, with marks on their letters. */
    private val titles = listOf("Blue in Green", "Sinfonia Concertante in E-flat major", "x".repeat(200), "W".repeat(41), "a b c d e f g h i j k l m n o p q r s t u",
        "abc${family}xyz$family", "$kiss Our Song", "$germany$france$germany$france$germany$france", "$beyonce Live at Home, the long version",
        "一二三四五六七八九十".repeat(5), "$note ".repeat(30), "line${nl}one${nl}two${tab}three four five six seven",
        "$marhaban ".repeat(4), ("ab" + u(0xD83C) + "cd").repeat(20), keycap.repeat(15), england.repeat(5), "$thumb$zwj".repeat(12),
        "$han$geul".repeat(8), "ab$thumb".repeat(15), "$namaste ".repeat(4), "a${germany}b$germany$france".repeat(6), "x$note$selector$zwj${note}y".repeat(9),
        // Thai and Lao with the vowel "am", half-width Japanese, Bengali and Tamil, a verse sign with its digits, Persian with a non-joiner,
        // Hebrew with points, a letter under a pile of accents, Burmese, and a syllable followed by a loose last sound.
        (u(0x0E17, 0x0E33, 0x0E19, 0x0E49, 0x0E33) + " ").repeat(6), (u(0x0E81, 0x0EB3) + "x").repeat(12), u(0xFF8A, 0xFF9E, 0xFF8A, 0xFF9F, 0xFF76).repeat(9),
        (u(0x09AA, 0x09CD, 0x09B0, 0x09C7, 0x09AE) + " ").repeat(8), u(0x0BA4, 0x0BCD, 0x0BA4, 0x0BAE, 0x0BBF, 0x0BB4, 0x0BCD).repeat(6),
        ("abc " + u(0x06DD, 0x0661, 0x0662) + " ").repeat(6), (u(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645) + " ").repeat(5),
        (u(0x05E9, 0x05C1, 0x05B8, 0x05DC, 0x05D5, 0x05B9, 0x05DD) + " ").repeat(6), "Z" + acute.repeat(50) + "algo",
        u(0x1000, 0x102C, 0x1000, 0x1031, 0x1019, 0x103C).repeat(7), (u(0xAC00, 0xD7CB, 0x1112, 0xAC00) + "x").repeat(8),
        // Three hundred characters and more, with line breaks, pictures and right-to-left words in them.
        ("A very long title $note$selector that goes on$nl" + "שלום $family$tab").repeat(8),
    ) + listOf("long", "wide", "emoji", "rtl").map { MediaSamples.of(listOf(it))!!.players.single().title }

    @Test fun aTitleOfThreeHundredCharactersWithLineBreaksAndPicturesIsOneShortLine() {
        val title = titles.single { it.startsWith("A very long title") }
        assertTrue(title.length > 300)
        val track = Track(title, "Somebody", "Player")
        // At 20 there is room for the note but not for the selector that belongs to it, so both go.
        assertEquals("A very long title…", text(track))
        assertEquals("A very long title $note$selector…", text(track, max = 21))
        // At 40 the family of seven parts would be cut in two.
        assertEquals("A very long title $note$selector that goes on שלום…", text(track, max = 40))
        assertEquals("A very…", text(track, max = 8))
        // What is said aloud and the tooltip hold the title as the source hands it on: one line of at most 200.
        val whole = bar(Phase.PLAYING, track).tooltip!!
        assertTrue(whole.length <= 200 + " · Somebody".length)
        assertFalse(whole.any { it.isISOControl() })
    }

    @Test fun javasOwnIdeaOfACharacterAgreesWithEveryCut() {
        // A second opinion that is not this app's: how Java itself groups a text into characters (the pattern \X), which knows
        // emoji from Java 20 on. Wherever a title is cut, Java must see the end of a character there too.
        assumeTrue((System.getProperty("java.specification.version").orEmpty().toIntOrNull() ?: 0) >= 20)
        val character = Regex("\\X")
        for (title in titles) {
            val full = Track(title, "", "").title
            val ends = character.findAll(full).map { it.range.last + 1 }.toSet()
            val firstCharacter = character.find(full)?.value.orEmpty()
            for (max in 1..60) {
                val shown = MediaText.cut(full, max)
                assertTrue("more than asked for", MediaText.count(shown) <= max)
                if (shown == full) continue
                assertTrue("no ellipsis", shown.endsWith("…"))
                val kept = shown.dropLast(1)
                assertTrue("not the title's own beginning", full.startsWith(kept))
                assertTrue("cut inside a character at $max", kept.isEmpty() || kept.length in ends)
                // And nothing is thrown away for no reason: within the slider's range, a first character that has room is kept.
                if (max in MediaText.CHARS && MediaText.count(firstCharacter) < max) assertTrue("nothing kept at $max", kept.isNotEmpty())
            }
        }
    }

    @Test fun aLongerLimitNeverShowsLess() {
        for (title in titles) {
            val full = Track(title, "", "").title
            var before = 0
            for (max in 1..60) {
                val now = MediaText.count(MediaText.cut(full, max))
                assertTrue("less at $max than at ${max - 1}", now >= before)
                before = now
            }
        }
    }

    // ---- a title made fit to show

    @Test fun aTitleIsOneLine() {
        assertEquals("Blue in Green", Track("  Blue in${nl}Green$tab ", "", "").title)
        assertEquals("a b c", Track("a" + u(0x0D, 0x0A, 0x0D, 0x0A) + "b   c", "", "").title)
        // Every kind of line break and blank a text can hold: a line separator, a paragraph separator, a no-break space,
        // a form feed, and three control characters.
        assertEquals("a b c d e f g h", Track("a" + u(0x2028) + "b" + u(0x2029) + "c" + u(0xA0) + "d" + u(0x0C) + "e" + u(0x7F) + "f" + u(0x9F) + "g" + u(0x85) + "h", "", "").title)
        assertEquals("Live at the Apollo", Track("Live" + u(0x00) + " at" + u(0x0B) + "the" + u(0x85) + "Apollo", "", "").title)
        assertEquals("", Track(" $nl ", "", "").title)
        val shown = text(Track("Line one${nl}Line two${nl}Line three", "", "Player"))
        assertEquals("Line one Line two L…", shown)
        assertFalse(shown!!.any { it.isISOControl() })
    }

    @Test fun theSpacesThatKeepALineTogetherAreSpacesAllTheSame() {
        // French sets a narrow no-break space before "?"; a figure space is as wide as a digit.
        assertEquals("Pourquoi ?", Track("Pourquoi" + u(0x202F) + "?", "", "").title)
        assertEquals("1 000 fois", Track("1" + u(0x2007) + "000 fois", "", "").title)
        assertEquals("abc", Track(u(0x202F) + "abc" + u(0x2007), "", "").title)
        // So none of them is left hanging before the ellipsis.
        assertEquals("abcdefg…", text(Track("abcdefg" + u(0x202F) + "hijkl", "", ""), max = 9))
        assertEquals("abcdefg…", text(Track("abcdefg" + u(0xA0) + "hijkl", "", ""), max = 9))
    }

    @Test fun halfAnEmojiFromAPlayerIsDropped() {
        // A player that cut its own title in the middle of an emoji hands over half of one.
        val firstHalf = u(0xD83C)
        val secondHalf = u(0xDFB5)
        assertEquals("abcd", Track("ab${firstHalf}cd", "", "").title)
        assertEquals("ab", Track("ab$firstHalf", "", "").title)
        assertEquals("ab", Track("${secondHalf}ab", "", "").title)
        assertEquals("ab$note", Track("ab$firstHalf$secondHalf", "", "").title)
    }

    @Test fun aTitleCannotTurnTheTextBesideItAround() {
        // The nine marks that override the direction of what follows are left out; the letters keep their own direction.
        assertEquals("evil", Track(u(0x202E) + "evil" + u(0x202C), "", "").title)
        assertEquals("abc", Track(u(0x202A) + "a" + u(0x202B) + "b" + u(0x202D) + "c", "", "").title)
        assertEquals("abc", Track(u(0x2066) + "a" + u(0x2067) + "b" + u(0x2068) + "c" + u(0x2069), "", "").title)
        // A mark that only says "left to right" or "right to left" for itself does no harm and stays.
        val mark = u(0x200F)
        assertEquals("a${mark}b", Track("a${mark}b", "", "").title)
    }

    @Test fun aTitleWithNothingToSeeIsNoTitle() {
        // Some players say "no title" with a character of no width.
        for (nothing in listOf(u(0x200B), u(0x200B, 0x2060, 0xFEFF), u(0xAD), u(0x200D), "$acute$acute", u(0x3164), u(0x115F, 0x1160), u(0x2800), u(0xFFA0),
            " " + u(0x200B) + " ", u(0x202E), u(0xD83C))) {
            val track = Track(nothing, nothing, "Chrome", nothing)
            assertEquals("", track.title)
            assertEquals("", track.artist)
            assertEquals("", track.album)
            assertTrue(track.bare)
            assertEquals("Chrome", text(track))
            assertEquals("Chrome is playing", bar(Phase.PLAYING, track).desc)
        }
        // With something to see beside it, the title is left as it came.
        assertEquals(u(0x200B) + "Song", Track(u(0x200B) + "Song", "", "").title)
        assertEquals("e$acute", Track("e$acute", "", "").title)
        assertEquals(note, Track(note, "", "").title)
        assertEquals("…", Track("…", "", "").title)
    }

    @Test fun nothingAtAllIsNoTrack() {
        val track = Track(null, null, null)
        assertEquals("", track.title)
        assertEquals("", track.artist)
        assertEquals("", track.album)
        assertEquals("", track.app)
        assertTrue(track.bare)
        assertNull(text(track))
        assertEquals("Something is playing", bar(Phase.PLAYING, track).desc)
    }

    @Test fun aTitleIsAtMostTwoHundredLong() {
        // The source holds a title to 200, between whole characters: the note would be the 200th and 201st, so it goes.
        assertEquals("x".repeat(199), Track("x".repeat(199) + note + "y", "", "").title)
        assertEquals(200, Track("x".repeat(500), "", "").title.length)
    }

    @Test fun aTitleIsOnlyEverPutIntoASentenceNeverReadAsOne() {
        // Percent signs and dollars in a title are text, whatever they would mean to the code that fills in a sentence.
        val odd = Track("100% sure \$1 %s %1\$s %n", "AC/DC %d", "Spotify")
        assertEquals("100% sure \$1 %s %1\$s %n", text(odd, Show.TITLE, 40))
        assertEquals("100% sure \$1 %s %1\$s %n · AC/DC %d", text(odd, Show.BOTH, 40))
        assertEquals("Playing: 100% sure \$1 %s %1\$s %n by AC/DC %d", bar(Phase.PLAYING, odd).desc)
        assertEquals("100% sure \$1 %s %1\$s %n · AC/DC %d", bar(Phase.PLAYING, odd).tooltip)
    }

    @Test fun aSentenceThatAsksForMoreThanItIsGivenDoesNotThrow() {
        // A string gone wrong (a translation with one argument too many) must not take the bar down while it is drawn.
        val broken = MediaText.Words { "%1\$s and %2\$s and %3\$s" }
        assertEquals("only one", broken.fill(Word.OPEN_PLAYER, "only one"))
        assertEquals("one two", MediaText.Words { "%q" }.fill(Word.BAR_BOTH, "one", "two"))
        assertEquals("Open Spotify", words.fill(Word.OPEN_PLAYER, "Spotify"))
    }

    // ---- the bar's text

    @Test fun everyRowOfTheTableInTheBar() {
        assertEquals("Blue in Green", text(blue, Show.TITLE, 20))
        assertEquals("Sinfonia Concertant…", text(sinfonia, Show.TITLE, 20))
        assertEquals("Sinfoni…", text(sinfonia, Show.TITLE, 8))
        assertEquals("Clocks · Coldplay", text(clocks, Show.BOTH, 20))
        assertEquals("Bohemian Rhapsody", text(bohemian, Show.BOTH, 20))
        assertEquals("Bohemian Rhapsody · Queen", text(bohemian, Show.BOTH, 30))
        assertEquals("Miles Davis", text(soWhat, Show.ARTIST, 20))
        for (show in Show.entries) assertEquals("Chrome", text(video, show, 20))
    }

    @Test fun theExamplesHaveTheLengthsTheTablesGive() {
        assertEquals(13, MediaText.count(text(blue)!!))
        assertEquals(20, MediaText.count(text(sinfonia)!!))
        assertEquals(8, MediaText.count(text(sinfonia, max = 8)!!))
        assertEquals(17, MediaText.count(text(clocks, Show.BOTH)!!))
        assertEquals(17, MediaText.count(text(bohemian, Show.BOTH)!!))
        assertEquals(25, MediaText.count(text(bohemian, Show.BOTH, 30)!!))
        assertEquals(11, MediaText.count(text(soWhat, Show.ARTIST)!!))
        assertEquals(6, MediaText.count(text(video)!!))
    }

    @Test fun titleAndArtistDropTheArtistWholeWhenTheyDoNotFit() {
        // 25 characters with the artist: at 24 the artist goes, at 25 it stays. It is never cut ("Bohemian Rhapsody · Qu…").
        assertEquals("Bohemian Rhapsody", text(bohemian, Show.BOTH, 24))
        assertEquals("Bohemian Rhapsody · Queen", text(bohemian, Show.BOTH, 25))
        // The title alone is then cut like any title.
        assertEquals("Sinfonia Concertant…", text(sinfonia, Show.BOTH, 20))
        // No artist: the title. No title: the player.
        assertEquals("Clocks", text(Track("Clocks", "", "Spotify"), Show.BOTH))
        assertEquals("Spotify", text(Track("", "Coldplay", "Spotify"), Show.BOTH))
    }

    @Test fun showArtistFallsBackToTheTitleThenToThePlayer() {
        assertEquals("So What", text(Track("So What", "", "Spotify"), Show.ARTIST))
        assertEquals("Chrome", text(video, Show.ARTIST))
        assertEquals("Wolfgang Amadeus Mo…", text(Track("Sinfonia", "Wolfgang Amadeus Mozart", "Spotify"), Show.ARTIST))
    }

    @Test fun aPlayersNameIsHeldToTheLengthToo() {
        assertEquals("A Very Long Player…", text(Track("", "", "A Very Long Player Name")))
        assertNull(text(Track("", "", "")))
    }

    @Test fun longestTitleNeverGivesMoreCharactersThanAskedFor() {
        for (title in titles) for (artist in listOf("", "Queen", titles[2])) for (show in Show.entries) for (max in MediaText.CHARS) {
            val shown = text(Track(title, artist, "Spotify"), show, max)!!
            assertTrue("$max: more than asked for", MediaText.count(shown) <= max)
            assertFalse("a line break or another control character", shown.any { it.isISOControl() || it.code == 0x2028 || it.code == 0x2029 })
            assertFalse("a space before the ellipsis", shown.endsWith(" …"))
            // Where the text was cut, it was not cut behind a joiner (which would leave the next picture's first half out).
            if (shown.endsWith("…")) assertFalse("a joiner left hanging", shown.dropLast(1).endsWith(zwj))
            // Every surrogate has its other half beside it.
            shown.forEachIndexed { i, c ->
                if (c.isHighSurrogate()) assertTrue(i + 1 < shown.length && shown[i + 1].isLowSurrogate())
                if (c.isLowSurrogate()) assertTrue(i > 0 && shown[i - 1].isHighSurrogate())
            }
        }
    }

    @Test fun aLengthOutsideTheSlidersRangeIsBroughtIntoIt() {
        // A layout that was pasted in can say anything.
        assertEquals("Sinfoni…", text(sinfonia, max = 0))
        assertEquals("Sinfoni…", text(sinfonia, max = -3))
        assertEquals("Sinfonia Concertante in E-flat major", text(sinfonia, max = 500))
        assertEquals("x".repeat(39) + "…", text(Track("x".repeat(60), "", ""), max = 500))
    }

    @Test fun theShowOptionHasThreeValuesAndTitleIsTheDefault() {
        assertEquals(Show.TITLE, Show.of("title"))
        assertEquals(Show.BOTH, Show.of("both"))
        assertEquals(Show.ARTIST, Show.of("artist"))
        assertEquals(Show.TITLE, Show.of(null))
        assertEquals(Show.TITLE, Show.of("something else"))
        assertEquals(listOf("title", "both", "artist"), Show.entries.map { it.id })
    }

    @Test fun theOptionsAreReadFromTheLayoutByTheirFixedNames() {
        // Saved layouts and the test hooks (`set media show=both`) use these names.
        assertEquals(listOf("show", "maxChars", "click", "lingerMin"),
            listOf(MediaText.OPTION_SHOW, MediaText.OPTION_CHARS, MediaText.OPTION_CLICK, MediaText.OPTION_LINGER))
        val set = mapOf("show" to "both", "maxChars" to "8", "click" to "toggle")
        assertEquals(Show.BOTH, MediaText.show(set))
        assertEquals(8, MediaText.chars(set))
        assertTrue(MediaText.toggles(set))
        // Nothing set: the title, 20 characters, a click opens the menu; the rule waits 2 minutes, 1 to 10.
        assertEquals(Show.TITLE, MediaText.show(emptyMap()))
        assertEquals(20, MediaText.chars(emptyMap()))
        assertFalse(MediaText.toggles(emptyMap()))
        assertEquals(8..40, MediaText.CHARS)
        assertEquals(2, MediaText.DEFAULT_LINGER)
        assertEquals(1..10, MediaText.LINGER)
        // A layout that was pasted in can say anything.
        assertEquals(40, MediaText.chars(mapOf("maxChars" to "500")))
        assertEquals(8, MediaText.chars(mapOf("maxChars" to "-1")))
        assertEquals(20, MediaText.chars(mapOf("maxChars" to "many")))
        assertFalse(MediaText.toggles(mapOf("click" to "menu")))
        assertFalse(MediaText.toggles(mapOf("click" to "TOGGLE")))
    }

    // ---- the bar, state by state

    private fun bar(phase: Phase, track: Track?, show: Show = Show.TITLE, max: Int = 20) = MediaText.bar(phase, track, show, max, words)

    @Test fun everyStateOfTheBar() {
        // Playing, title known.
        bar(Phase.PLAYING, blue).let { assertEquals(Glyph.NOTE, it.glyph); assertEquals("Blue in Green", it.text); assertTrue(it.active) }
        // Playing, no title.
        bar(Phase.PLAYING, video).let { assertEquals(Glyph.NOTE, it.glyph); assertEquals("Chrome", it.text); assertTrue(it.active) }
        // Paused, for the rule's minutes: what it showed while playing, whatever Show says.
        bar(Phase.PAUSED, blue).let { assertEquals(Glyph.PAUSE, it.glyph); assertEquals("Blue in Green", it.text); assertTrue(it.active) }
        assertEquals("Clocks · Coldplay", bar(Phase.PAUSED, clocks, Show.BOTH).text)
        assertEquals("Miles Davis", bar(Phase.PAUSED, soWhat, Show.ARTIST).text)
        // Nothing playing, also with a player that paused long ago.
        bar(Phase.NOTHING, blue).let { assertEquals(Glyph.OFF, it.glyph); assertNull(it.text); assertFalse(it.active) }
        bar(Phase.NOTHING, null).let { assertEquals(Glyph.OFF, it.glyph); assertNull(it.text); assertFalse(it.active) }
        // No access, and access given while the players are not listed yet (both: no player's words): playing, paused, nothing.
        bar(Phase.PLAYING, null).let { assertEquals(Glyph.NOTE, it.glyph); assertNull(it.text); assertTrue(it.active) }
        bar(Phase.PAUSED, null).let { assertEquals(Glyph.PAUSE, it.glyph); assertNull(it.text); assertTrue(it.active) }
    }

    @Test fun whatTheBarSaysAloud() {
        assertEquals("Playing: Blue in Green by Miles Davis", bar(Phase.PLAYING, blue).desc)
        assertEquals("Playing: Blue in Green", bar(Phase.PLAYING, Track("Blue in Green", "", "Spotify")).desc)
        assertEquals("Chrome is playing", bar(Phase.PLAYING, video).desc)
        assertEquals("Paused: Blue in Green", bar(Phase.PAUSED, blue).desc)
        assertEquals("Chrome is paused", bar(Phase.PAUSED, video).desc)
        assertEquals("Something is playing", bar(Phase.PLAYING, null).desc)
        assertEquals("Playback paused", bar(Phase.PAUSED, null).desc)
        assertEquals("Nothing playing", bar(Phase.NOTHING, null).desc)
        assertEquals("Nothing playing", bar(Phase.NOTHING, blue).desc)
        // What is said does not depend on what is shown, and is the whole title.
        assertEquals("Playing: Sinfonia Concertante in E-flat major by Mozart", bar(Phase.PLAYING, sinfonia, Show.ARTIST, 8).desc)
        // A player without a name is not called by one.
        assertEquals("Something is playing", bar(Phase.PLAYING, Track("", "", "")).desc)
        assertEquals("Playback paused", bar(Phase.PAUSED, Track("", "", "")).desc)
    }

    @Test fun theTooltipIsTheWholeTitleAndArtist() {
        assertEquals("Blue in Green · Miles Davis", bar(Phase.PLAYING, blue).tooltip)
        assertEquals("Sinfonia Concertante in E-flat major · Mozart", bar(Phase.PLAYING, sinfonia, Show.TITLE, 8).tooltip)
        assertEquals("Blue in Green · Miles Davis", bar(Phase.PAUSED, blue).tooltip)
        assertEquals("Blue in Green", bar(Phase.PLAYING, Track("Blue in Green", "", "Spotify")).tooltip)
        // Without a title the strip shows the item's own name, as for every item.
        assertNull(bar(Phase.PLAYING, video).tooltip)
        assertNull(bar(Phase.PLAYING, null).tooltip)
        assertNull(bar(Phase.NOTHING, blue).tooltip)
    }

    // ---- the rule: while playing, and for some minutes after

    @Test fun theItemStaysForTheRulesMinutesAfterAPause() {
        val min = 60_000L
        val played = 1_000_000L
        assertEquals(Phase.PLAYING, MediaText.phase(true, played, played, 2))
        assertEquals(Phase.PAUSED, MediaText.phase(false, played, played, 2))
        assertEquals(Phase.PAUSED, MediaText.phase(false, played, played + 2 * min - 1, 2))
        assertEquals(Phase.NOTHING, MediaText.phase(false, played, played + 2 * min, 2))
        assertEquals(Phase.PAUSED, MediaText.phase(false, played, played + 9 * min, 10))
        assertEquals(Phase.NOTHING, MediaText.phase(false, played, played + 1 * min, 1))
        // Nothing has played yet.
        assertEquals(Phase.NOTHING, MediaText.phase(false, 0, played, 2))
        // Playing counts whatever the clock says.
        assertEquals(Phase.PLAYING, MediaText.phase(true, 0, played, 2))
    }

    @Test fun aClockSetBackOrAnOddNumberOfMinutesDoesNotKeepTheItemForever() {
        val played = 1_000_000L
        assertEquals(Phase.NOTHING, MediaText.phase(false, played, played - 1, 2))
        // The slider goes from 1 to 10; a pasted layout can say anything.
        assertEquals(Phase.NOTHING, MediaText.phase(false, played, played + 10 * 60_000L, 5_000))
        assertEquals(Phase.PAUSED, MediaText.phase(false, played, played + 30_000, -4))
        assertEquals(Phase.NOTHING, MediaText.phase(false, played, played + 60_000, -4))
    }

    @Test fun pausedTheBarNamesOnlyTheAppThatWasPlaying() {
        assertTrue(MediaText.follows(Phase.PLAYING, "music", null))
        assertTrue(MediaText.follows(Phase.PLAYING, "music", "browser"))
        assertTrue(MediaText.follows(Phase.PAUSED, "music", "music"))
        // The player that was playing has gone, and one that paused long ago is first now: "paused" is not about that one.
        assertFalse(MediaText.follows(Phase.PAUSED, "browser", "music"))
        assertFalse(MediaText.follows(Phase.PAUSED, "browser", null))
        assertFalse(MediaText.follows(Phase.NOTHING, "music", "music"))
    }

    // ---- the menu, state by state

    private val all = Controls(playing = true, durationMs = 337_000, canPrevious = true, canPlayPause = true, canNext = true)
    private fun menu(access: Boolean = true, starting: Boolean = false, granted: Boolean = access, phase: Phase = Phase.PLAYING, track: Track? = null,
                     controls: Controls? = null) = MediaText.menu(access, starting, granted, phase, track, controls, words)

    @Test fun theMenuWhilePlaying() {
        val m = menu(track = blue, controls = all)
        assertEquals("Spotify", m.subtitle)
        assertTrue(m.track); assertNull(m.line); assertTrue(m.position)
        assertTrue(m.playing); assertTrue(m.canPrevious); assertTrue(m.canPlayPause); assertTrue(m.canNext)
        assertTrue(m.player); assertFalse(m.consent)
    }

    @Test fun theMenuWhilePaused() {
        // Also long after the bar has stopped saying so: the menu shows the player there is.
        val m = menu(phase = Phase.NOTHING, track = blue, controls = Controls(false, 337_000, true, true, true))
        assertEquals("Spotify · Paused", m.subtitle)
        assertTrue(m.track); assertTrue(m.position); assertFalse(m.playing); assertTrue(m.player); assertFalse(m.consent)
    }

    @Test fun theMenuForAPlayerThatSaysNothingAboutWhatItPlays() {
        val playing = menu(track = video, controls = Controls(true, 0, false, true, false))
        assertEquals("Chrome", playing.subtitle)
        assertFalse(playing.track); assertEquals("Chrome is playing", playing.line); assertFalse(playing.position)
        assertFalse(playing.canPrevious); assertTrue(playing.canPlayPause); assertFalse(playing.canNext); assertTrue(playing.player)
        val paused = menu(phase = Phase.PAUSED, track = video, controls = Controls(false, 720_000, false, true, false))
        assertEquals("Chrome", paused.subtitle)
        assertEquals("Chrome is paused", paused.line); assertTrue(paused.position)
        // An artist or an album alone is something to show.
        val some = menu(track = Track("", "", "Radio", album = "Morning Show"), controls = all)
        assertTrue(some.track); assertNull(some.line)
    }

    @Test fun theMenuForAPlayerWithoutAName() {
        assertEquals("Playing", menu(track = Track("Title", "Artist", ""), controls = all).subtitle)
        assertEquals("Paused", menu(track = Track("Title", "Artist", ""), controls = Controls(false, 1, true, true, true)).subtitle)
        assertEquals("Something is playing", menu(track = Track("", "", ""), controls = all).line)
    }

    @Test fun theMenuForALiveStream() {
        val m = menu(track = Track("Morning Show", "Radio One", "Radio"), controls = Controls(true, 0, false, true, false))
        assertEquals("Radio", m.subtitle)
        assertTrue(m.track); assertFalse(m.position); assertFalse(m.canPrevious); assertFalse(m.canNext); assertTrue(m.canPlayPause)
    }

    @Test fun theMenuWithNothingPlaying() {
        val m = menu(phase = Phase.NOTHING)
        assertEquals("Nothing playing", m.subtitle)
        assertFalse(m.track); assertNull(m.line); assertFalse(m.position)
        // Play stays: it sends the media key. There is nothing to skip.
        assertFalse(m.playing); assertTrue(m.canPlayPause); assertFalse(m.canPrevious); assertFalse(m.canNext)
        assertFalse(m.player); assertFalse(m.consent)
        // The words of a player without its controls (or the other way round) are no player.
        assertFalse(menu(track = blue, controls = null).player)
        assertFalse(menu(track = null, controls = all).player)
    }

    @Test fun theMenuWhileThePlayersAreNotListedYet() {
        val m = menu(access = false, starting = true, granted = true, phase = Phase.NOTHING)
        assertEquals("Starting…", m.subtitle)
        assertFalse(m.track); assertFalse(m.position); assertFalse(m.playing); assertTrue(m.canPlayPause); assertFalse(m.canPrevious); assertFalse(m.canNext)
        assertFalse(m.player); assertFalse(m.consent)
        // The same while something is audible: nothing to skip in yet. The middle button then reads Pause, since that is what its key would do.
        val audible = menu(access = false, starting = true, granted = true, phase = Phase.PLAYING)
        assertEquals("Starting…", audible.subtitle)
        assertTrue(audible.playing); assertTrue(audible.canPlayPause); assertFalse(audible.canPrevious); assertFalse(audible.canNext)
        assertFalse(audible.consent)
    }

    @Test fun theMenuWithoutAccess() {
        val playing = menu(access = false, phase = Phase.PLAYING)
        assertEquals("Playing", playing.subtitle)
        assertFalse(playing.track); assertFalse(playing.position); assertFalse(playing.player)
        // The three buttons are media keys, which need no access: all there, pause while something plays.
        assertTrue(playing.playing); assertTrue(playing.canPrevious); assertTrue(playing.canPlayPause); assertTrue(playing.canNext)
        assertTrue(playing.consent)
        val paused = menu(access = false, phase = Phase.PAUSED)
        assertEquals("Paused", paused.subtitle)
        assertFalse(paused.playing); assertTrue(paused.canPrevious); assertTrue(paused.canNext); assertTrue(paused.consent)
        val nothing = menu(access = false, phase = Phase.NOTHING)
        assertEquals("Nothing playing", nothing.subtitle)
        assertFalse(nothing.playing); assertTrue(nothing.canPrevious); assertTrue(nothing.canPlayPause); assertTrue(nothing.canNext); assertTrue(nothing.consent)
    }

    @Test fun accessThatIsOnIsNotAskedForAgain() {
        // Turned on in Android, and the device still lists no players: the keys, and no words asking for what was given.
        val m = menu(access = false, granted = true, phase = Phase.PLAYING)
        assertEquals("Playing", m.subtitle)
        assertTrue(m.canPrevious); assertTrue(m.canPlayPause); assertTrue(m.canNext)
        assertFalse(m.consent)
    }

    @Test fun soundWithoutAPlayerIsPlayingAndNoMore() {
        // The players are listed and none is there, but something is audible: all that is known is that it plays.
        val m = menu(phase = Phase.PLAYING)
        assertEquals("Playing", m.subtitle)
        assertTrue(m.playing); assertTrue(m.canPrevious); assertTrue(m.canNext); assertFalse(m.track); assertFalse(m.player); assertFalse(m.consent)
        val paused = menu(phase = Phase.PAUSED)
        assertEquals("Paused", paused.subtitle)
        assertFalse(paused.playing); assertFalse(paused.canPrevious); assertFalse(paused.canNext); assertTrue(paused.canPlayPause)
    }

    @Test fun whatThePlayerDoesNotOfferIsDimmed() {
        val m = menu(track = blue, controls = Controls(true, 337_000, canPrevious = false, canPlayPause = false, canNext = true))
        assertFalse(m.canPrevious); assertFalse(m.canPlayPause); assertTrue(m.canNext)
    }

    // ---- the menu's other words

    @Test fun aTracksTimesAreMinutesAndSeconds() {
        assertEquals("1:42", MediaText.time(102_000))
        assertEquals("5:37", MediaText.time(337_000))
        assertEquals("1:02:03", MediaText.time(3_723_000))
        assertEquals("0:00", MediaText.time(0))
        assertEquals("0:00", MediaText.time(999))
        assertEquals("0:01", MediaText.time(1_000))
        assertEquals("0:00", MediaText.time(-5_000))
        assertEquals("1:42 of 5:37", MediaText.positionState(102_000, 337_000, words))
    }

    @Test fun thePositionIsAShareOfTheLength() {
        assertEquals(0f, MediaText.fraction(0, 337_000), 0f)
        assertEquals(0.5f, MediaText.fraction(100_000, 200_000), 0.0001f)
        assertEquals(1f, MediaText.fraction(400_000, 337_000), 0f)
        assertEquals(0f, MediaText.fraction(-1, 337_000), 0f)
        assertEquals(0f, MediaText.fraction(5_000, 0), 0f)
    }

    @Test fun thePositionBarFollowsThePointerThenWaitsForThePlayer() {
        val length = 337_000L
        // Dragged, it stands under the pointer, whatever the player says.
        assertEquals(200_000, MediaText.position(real = 102_000, dragged = 200_000, sought = null, durationMs = length))
        assertEquals(200_000, MediaText.position(real = 102_000, dragged = 200_000, sought = 50_000, durationMs = length))
        // Let go at 3:20, it stays there while the player is still where it was,
        assertEquals(200_000, MediaText.position(102_500, null, 200_000, length))
        // also when the player reports again from the old place first (some say where they are every second),
        assertEquals(200_000, MediaText.position(103_500, null, 200_000, length))
        // and is the player's own again once the player is about there, before or behind.
        assertEquals(200_400, MediaText.position(200_400, null, 200_000, length))
        assertEquals(198_600, MediaText.position(198_600, null, 200_000, length))
        assertEquals(200_000, MediaText.position(198_400, null, 200_000, length))
        // Nothing sought: where the player is.
        assertEquals(102_000, MediaText.position(102_000, null, null, length))
        // Never outside the track.
        assertEquals(length, MediaText.position(400_000, null, null, length))
        assertEquals(0, MediaText.position(-5, null, null, length))
        assertEquals(length, MediaText.position(0, 999_999, null, length))
        assertEquals(0, MediaText.position(0, -3, null, length))
        assertEquals(0, MediaText.position(5, null, null, 0))
        assertEquals(0, MediaText.position(5, null, null, -1))
    }

    @Test fun aTrackIsReadAsOneSentence() {
        assertEquals("Blue in Green by Miles Davis, Kind of Blue", MediaText.spoken(blue, words))
        assertEquals("Blue in Green by Miles Davis", MediaText.spoken(Track("Blue in Green", "Miles Davis", "Spotify"), words))
        assertEquals("Blue in Green, Kind of Blue", MediaText.spoken(Track("Blue in Green", "", "Spotify", "Kind of Blue"), words))
        assertEquals("Blue in Green", MediaText.spoken(Track("Blue in Green", "", "Spotify"), words))
        assertEquals("Miles Davis, Kind of Blue", MediaText.spoken(Track("", "Miles Davis", "Spotify", "Kind of Blue"), words))
        assertEquals("Kind of Blue", MediaText.spoken(Track("", "", "Spotify", "Kind of Blue"), words))
        assertEquals("", MediaText.spoken(video, words))
    }

    @Test fun otherPlayersAndTheWayToThePlayer() {
        val lofi = Track("Lo-fi beats to study to", "", "Chrome")
        assertEquals("Lo-fi beats to study to", MediaText.other(lofi))
        assertEquals("Chrome", MediaText.other(video))
        assertEquals("Pause Chrome", MediaText.otherButton(video, playing = true, words))
        assertEquals("Play Chrome", MediaText.otherButton(lofi, playing = false, words))
        assertEquals("Open Spotify", MediaText.open(blue, words))
    }

    // ---- the samples QA stages

    private fun sample(vararg name: String) = MediaSamples.of(name.toList())!!
    private fun first(s: MediaSamples.Sample) = s.players.firstOrNull()?.let { Track(it.title, it.artist, it.app, it.album) }
    private fun phase(s: MediaSamples.Sample) = when {
        s.players.firstOrNull()?.playing ?: s.audible -> Phase.PLAYING
        s.paused -> Phase.PAUSED
        else -> Phase.NOTHING
    }
    private fun bar(s: MediaSamples.Sample, show: Show = Show.TITLE, max: Int = 20) = bar(phase(s), first(s), show, max)

    @Test fun theSamplesHaveTheNamesTheHooksAreCalledBy() {
        for (name in listOf("playing", "paused", "none", "notitle", "two", "live", "noaccess", "starting")) {
            assertTrue("no sample called $name", MediaSamples.of(listOf(name)) != null)
        }
        assertNull(MediaSamples.of(listOf("nonsense")))
        assertNull(MediaSamples.of(emptyList()))
        assertNull(MediaSamples.of(listOf("playing", "nonsense")))
    }

    @Test fun aStagedSampleReadsAsTheTablesSay() {
        bar(sample("playing")).let { assertEquals(Glyph.NOTE, it.glyph); assertEquals("Blue in Green", it.text); assertEquals("Playing: Blue in Green by Miles Davis", it.desc) }
        bar(sample("paused")).let { assertEquals(Glyph.PAUSE, it.glyph); assertEquals("Blue in Green", it.text); assertEquals("Paused: Blue in Green", it.desc) }
        bar(sample("none")).let { assertEquals(Glyph.OFF, it.glyph); assertNull(it.text); assertEquals("Nothing playing", it.desc) }
        bar(sample("notitle")).let { assertEquals(Glyph.NOTE, it.glyph); assertEquals("Chrome", it.text); assertEquals("Chrome is playing", it.desc) }
        bar(sample("two"), Show.BOTH).let { assertEquals("Clocks · Coldplay", it.text) }
        bar(sample("long")).let { assertEquals("Sinfonia Concertant…", it.text) }
        bar(sample("long"), max = 8).let { assertEquals("Sinfoni…", it.text) }
        bar(sample("noaccess")).let { assertEquals(Glyph.NOTE, it.glyph); assertNull(it.text); assertEquals("Something is playing", it.desc) }
        bar(sample("noaccess", "paused")).let { assertEquals(Glyph.PAUSE, it.glyph); assertNull(it.text); assertEquals("Playback paused", it.desc) }
        bar(sample("noaccess", "none")).let { assertEquals(Glyph.OFF, it.glyph); assertNull(it.text) }
        // Access given, the players not listed yet: as without access.
        bar(sample("starting")).let { assertEquals(Glyph.OFF, it.glyph); assertNull(it.text); assertEquals("Nothing playing", it.desc); assertFalse(it.active) }
    }

    @Test fun theSamplesCoverTheMenusStates() {
        assertTrue(sample("playing").let { it.access && it.players.single().let { p -> p.playing && p.canSeek && p.durationMs == 337_000L && p.positionMs == 102_000L } })
        assertFalse(sample("paused").players.single().playing)
        assertTrue(sample("none").let { it.access && it.players.isEmpty() && !it.audible })
        assertTrue(sample("two").players.let { it.size == 2 && it[0].playing && !it[1].playing && it[1].title == "Lo-fi beats to study to" && it[1].app == "Chrome" })
        assertTrue(sample("live").players.single().let { it.durationMs == 0L && !it.canPrevious && !it.canNext && !it.canSeek })
        assertTrue(sample("notitle").players.single().let { it.title.isEmpty() && it.artist.isEmpty() && it.album.isEmpty() && !it.canSeek && it.durationMs > 0 })
        assertTrue(sample("noaccess").let { !it.access && !it.granted && it.players.isEmpty() && it.audible })
        assertTrue(sample("starting").let { !it.access && it.starting && it.granted && it.players.isEmpty() })
    }

    @Test fun theSampleWithPicturesIsCutBetweenThemAtEveryLength() {
        val sun = u(0x1F31E)
        val beach = u(0x1F3D6) + selector
        val sweden = u(0x1F1F8, 0x1F1EA)
        // A line break and a tab in the sample have become spaces.
        assertEquals("Summer $sun Hits 2026 $beach $sweden $family Road Trip", first(sample("emoji"))!!.title)
        fun at(max: Int) = bar(sample("emoji"), max = max).text
        assertEquals("Summer…", at(8))
        assertEquals("Summer $sun…", at(9))
        assertEquals("Summer $sun Hits 2026…", at(20))
        // The beach is a picture and its selector: at 21 there is room for the first of the two only.
        assertEquals("Summer $sun Hits 2026…", at(21))
        assertEquals("Summer $sun Hits 2026 $beach…", at(22))
        // At 24 the cut would fall between the flag's two letters.
        assertEquals("Summer $sun Hits 2026 $beach…", at(24))
        assertEquals("Summer $sun Hits 2026 $beach $sweden…", at(25))
        // From 27 to 32 it would fall inside the family.
        for (max in 26..32) assertEquals("Summer $sun Hits 2026 $beach $sweden…", at(max))
        assertEquals("Summer $sun Hits 2026 $beach $sweden $family…", at(33))
        assertEquals("Summer $sun Hits 2026 $beach $sweden $family Road T…", at(40))
    }

    @Test fun theWideAndTheRightToLeftSampleAreCutByTheSameCount() {
        // Twenty wide letters are far wider than twenty Latin ones: the count is the same, and the bar's width cap does the rest.
        val wide = first(sample("wide"))!!.title
        assertEquals(27, MediaText.count(wide))
        assertEquals("雨の日曜日に聴きたい静かなピアノ曲集…", bar(sample("wide")).text)
        assertEquals("雨の日曜日に聴…", bar(sample("wide"), max = 8).text)
        val rtl = first(sample("rtl"))!!.title
        assertEquals(35, MediaText.count(rtl))
        assertEquals(first(rtl, 19) + "…", bar(sample("rtl")).text)
        assertEquals(first(rtl, 7) + "…", bar(sample("rtl"), max = 8).text)
    }

    // ---- what must never be printed

    @Test fun noTitleCanBePrintedByAccident() {
        // What ends up in a log line by accident is what a value prints as. A player's own value leaves its words out, and
        // the classes of the rules print as nothing but their names.
        val session = NowPlaying.Session(key = "key", pkg = "player.app", app = "Player", title = "SECRET title", artist = "SECRET artist",
            album = "SECRET album", playing = true, durationMs = 1, positionMs = 0, positionAt = 0, speed = 1f, canPlayPause = true, canNext = true,
            canPrevious = true, canSeek = true, art = null, startedAt = 0)
        val track = Track(session.title, session.artist, session.app, session.album)
        val printed = listOf(session.toString(), NowPlaying.Playing(access = true, sessions = listOf(session, session)).toString(), track.toString(),
            bar(Phase.PLAYING, track).toString(), menu(track = track, controls = all).toString(), words.toString())
        for (line in printed) assertFalse(line, "SECRET" in line)
        // And the values do hold them, so the test would see them if they were printed.
        assertEquals("SECRET title", bar(Phase.PLAYING, track).text)
    }

    // ---- the text file, and what the item's own code may not do

    private fun strings(path: String): Map<String, String> = StringFiles.file(path)

    @Test fun noStringInTheMediaFileIsLeftOverByTheCode() {
        assertEquals("a string in strings_media.xml that no code shows", emptyList<String>(), StringFiles.unused("src/main/res/values/strings_media.xml"))
    }

    @Test fun britishEnglishSpellsGreyedWithAnE() {
        val british = strings("src/main/res/values-en-rGB/strings_media.xml")
        assertEquals(mapOf("media_access_restricted" to "Switch greyed out? Tap it, tap OK, then turn on Allow restricted settings in App info."), british)
    }

    private val items = File("src/main/java/com/meridian11/books/bentobar/items")
    /** A file's code without its comments (which may name what the code must not use). */
    private fun code(name: String) = File(items, name).readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines()
        .joinToString("\n") { it.substringBefore("//") }

    /** The files a title, an artist and artwork pass through on their way from the source to the screen. */
    private val own = listOf("MediaItem.kt", "MediaMenu.kt", "MediaText.kt", "MediaSamples.kt")

    @Test fun everyWordHasItsOwnStringInTheItem() {
        // The item hands the rules their words by name: "DESC_PAUSED" is media_desc_paused, and so on.
        val item = code("MediaItem.kt")
        for (word in Word.entries) {
            val resource = shared[word]?.first ?: ("media_" + word.name.lowercase())
            assertTrue("$word should be R.string.$resource", Regex("""Word\.${word.name}\s*->\s*R\.string\.$resource\b""").containsMatchIn(item))
        }
    }

    @Test fun whatTheItemShowsIsMarkedAsTheUsersOwnBusiness() {
        // The mark that makes the ticker log only the kind of a failure, drop what the item showed when it goes idle, and
        // makes the debug hooks print a text's length instead of the text.
        assertTrue(Regex("""override\s+val\s+discreet\s*=\s*true""").containsMatchIn(code("MediaItem.kt")))
    }

    @Test fun nowPlayingsOwnCodeWritesNoLogLinesAndKeepsNothing() {
        // None of these files names a log, a file, a preference, the layout's store, the clipboard or anything that is handed to another app.
        val never = listOf("Log.", "println(", "printStackTrace", "System.out", "System.err", "File(", "openFileOutput", "SharedPreferences", "Kept.",
            "Store.update", "Store.add", "Store.import", "Env.copy", "rememberSaveable", "ClipData", "setPrimaryClip", "putExtra", ".edit()", "writeText",
            "OutputStream", "outputStream", "Json.", "encodeToString", "Notify.", "Notification")
        for (name in own) {
            val text = code(name)
            for (n in never) assertFalse("items/$name has $n", n in text)
        }
        // The one message on screen that is not the menu itself is a fixed sentence.
        val toasts = Regex("""Toast\.makeText\(([^)]*)\)""").findAll(own.joinToString("\n") { code(it) }).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Env.app, Env.str(R.string.toast_nothing_can_open"), toasts)
    }

    @Test fun theSourcesLogLinesAreFixedSentences() {
        // The file that reads the players' titles may log that something went wrong, never what it was reading: every log
        // line there is a sentence as it stands, or one that ends in the kind of an exception.
        val kind = Regex.escape("\${e.javaClass.simpleName}")
        val fixed = Regex("Log\\.[a-z]\\(TAG, \"[^\"\$]*(" + kind + ")?\"\\)")
        val source = code("NowPlaying.kt")
        // However many log lines there are, each is one of those.
        assertEquals("a log line that is not a fixed sentence", Regex("""\bLog\.""").findAll(source).count(), fixed.findAll(source).count())
        assertFalse("Log." in code("MediaAccess.kt"))
        for (name in listOf("NowPlaying.kt", "MediaAccess.kt")) for (n in listOf("println(", "printStackTrace", "System.out", "System.err")) {
            assertFalse("items/$name has $n", n in code(name))
        }
    }

    @Test fun theRulesNeedNoAndroid() {
        for (name in listOf("MediaText.kt", "MediaSamples.kt")) assertFalse("items/$name imports Android", Regex("""import\s+android""").containsMatchIn(code(name)))
    }

    @Test fun theseFilesShowWhatTheyHold() {
        // No character that an editor doesn't show, or shows on top of its neighbor, stands in these files as itself: a title
        // that tries the rules is written by its characters' numbers. (A mark that turns text around, hidden in a source
        // file, can also make code read differently from what it does.)
        val hidden = setOf(Character.FORMAT, Character.CONTROL, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED).map { it.toInt() }
        val files = own.map { File(items, it) } + File("src/test/java/com/meridian11/books/bentobar/items/MediaTextTest.kt") +
            File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().map { File(it, "strings_media.xml") }.filter { it.exists() }
        assertTrue(files.size >= 12)
        for (file in files) file.readLines().forEachIndexed { i, line ->
            var at = 0
            while (at < line.length) {
                val cp = line.codePointAt(at)
                at += Character.charCount(cp)
                assertFalse("${file.name}, line ${i + 1}: the character %04X stands there as itself".format(cp), cp != 0x09 && Character.getType(cp) in hidden)
            }
        }
    }
}
