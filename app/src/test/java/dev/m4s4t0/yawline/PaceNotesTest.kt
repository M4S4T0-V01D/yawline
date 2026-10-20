package dev.m4s4t0.yawline

import dev.m4s4t0.yawline.notes.Grade
import dev.m4s4t0.yawline.notes.NoteParser
import dev.m4s4t0.yawline.notes.PaceNote
import org.junit.Assert.assertEquals
import org.junit.Test

class PaceNotesTest {
    @Test fun sheet() {
        val s = NoteParser.parseSheet("80\nL4 100 R3- tgt dc\n# comment\n- HP R 50\nfin")
        assertEquals(80, s.startGap)
        assertEquals(listOf("L4", "R3- tgt dc", "HP R", "fin"), s.notes.map { it.text })
        assertEquals(listOf(100, null, 50, null), s.notes.map { it.distAfter })
    }

    @Test fun speak() {
        assertEquals("right three minus tightens don't cut", NoteParser.speak(PaceNote("R3- tgt dc")))
        assertEquals("left four 100", NoteParser.speak(PaceNote("L4", 100)))
        assertEquals("left hairpin caution", NoteParser.speak(PaceNote("LHP !")))
        assertEquals("right six", NoteParser.speak(PaceNote("6R")))
    }

    @Test fun fromSpeech() {
        assertEquals("L4 tgt dc", NoteParser.fromSpeech("left four tightens don't cut"))
        assertEquals("R2+ cr", NoteParser.fromSpeech("right to plus crest"))
        assertEquals("L6 into R5", NoteParser.fromSpeech("Left 6 into right five"))
        assertEquals("keep it to the right", NoteParser.fromSpeech("keep it to the right"))
    }

    @Test fun grades() {
        assertEquals("6", Grade.fromRadius(200.0, 30.0))
        assertEquals("3", Grade.fromRadius(50.0, 60.0))
        assertEquals("HP", Grade.fromRadius(12.0, 170.0))
        assertEquals("SQ", Grade.fromRadius(10.0, 90.0))
    }
}
