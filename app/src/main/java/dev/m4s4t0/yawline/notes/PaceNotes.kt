package dev.m4s4t0.yawline.notes

/**
 * Corner grades in the 1–6 system most crews use (6 fastest, 1 slowest, then hairpin and square).
 * The radius cut-offs are a common starting point; every crew calibrates their own.
 */
object Grade {
    fun fromRadius(radiusM: Double, turnDeg: Double): String = when {
        turnDeg > 140 && radiusM < 25 -> "HP"
        turnDeg in 70.0..110.0 && radiusM < 15 -> "SQ"
        radiusM > 150 -> "6"
        radiusM > 100 -> "5"
        radiusM > 65 -> "4"
        radiusM > 40 -> "3"
        radiusM > 22 -> "2"
        else -> "1"
    }
}

/**
 * One call, as written ("L4 tightens 3 dc"). [distAfter] is the distance to the next call in metres
 * (the "100" in "L4 100"). [lat]/[lon] pin it to the road when it was made on a recce or generated from
 * a recorded run; such notes are triggered by position rather than by counting distance.
 */
data class PaceNote(
    val text: String,
    val distAfter: Int? = null,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
) {
    val pinned get() = !lat.isNaN() && !lon.isNaN()
    val linked get() = text.trim().endsWith(" into") || text.trim().endsWith(" and")
}

/**
 * Reads typed or pasted notes (from a notebook, a roadbook, or text copied out of a photo with Google
 * Lens) and turns calls into what the co-driver should say. Accepts the usual shorthand: L4, R3-, 6+,
 * HP, SQ, tgt/tightens, opn/opens, dc, ki, ko, cr, ! / !!, and distances as bare numbers.
 */
object NoteParser {
    private val WORDS = mapOf(
        "l" to "left", "left" to "left", "r" to "right", "right" to "right",
        "hp" to "hairpin", "hairpin" to "hairpin", "sq" to "square", "square" to "square",
        "fl" to "flat", "flat" to "flat", "kk" to "kink", "kink" to "kink",
        "tgt" to "tightens", "tg" to "tightens", "tightens" to "tightens", "tighten" to "tightens",
        "opn" to "opens", "opns" to "opens", "op" to "opens", "opens" to "opens", "open" to "opens",
        "lg" to "long", "lng" to "long", "long" to "long", "vlg" to "very long", "vlong" to "very long",
        "sh" to "short", "short" to "short",
        "dc" to "don't cut", "nc" to "don't cut", "c" to "cut", "cut" to "cut",
        "ki" to "keep in", "ko" to "keep out", "km" to "keep middle",
        "cr" to "crest", "crest" to "crest", "ocr" to "over crest", "ovcr" to "over crest", "over" to "over",
        "jmp" to "jump", "jump" to "jump", "bmp" to "bump", "bump" to "bump", "dip" to "dip",
        "!" to "caution", "!!" to "double caution", "!!!" to "triple caution", "cau" to "caution", "caution" to "caution",
        "into" to "into", ">" to "into", "and" to "and", "&" to "and", "+" to "and",
        "brk" to "brake", "br" to "brake", "brake" to "brake",
        "jct" to "junction", "junc" to "junction", "jn" to "junction", "brdg" to "bridge", "bridge" to "bridge",
        "nar" to "narrows", "narrows" to "narrows", "wd" to "widens", "widens" to "widens",
        "ruts" to "ruts", "rk" to "rock", "rock" to "rock", "sl" to "slippy", "slippy" to "slippy",
        "wtr" to "water", "water" to "water", "grv" to "gravel", "tmc" to "tarmac", "mud" to "mud",
        "chic" to "chicane", "chicane" to "chicane", "fin" to "finish", "finish" to "finish", "stop" to "stop",
        "ds" to "don't", "dont" to "don't", "maybe" to "maybe", "mb" to "maybe", "plus" to "plus", "minus" to "minus",
    )
    private val NUM = listOf("zero", "one", "two", "three", "four", "five", "six")
    private val CALL = Regex("^([LRlr])(\\d|HP|hp|SQ|sq)([+\\-]?)$")
    private val CALL_REV = Regex("^(\\d)([+\\-]?)([LRlr])$")
    private val GRADE = Regex("^(\\d)([+\\-]?)$")

    /** Splits [text] on spaces, commas and slashes, keeping "!" runs as their own tokens. */
    private fun tokens(text: String): List<String> =
        text.replace(",", " ").replace("/", " ").replace(Regex("(!+)"), " $1 ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun isDistance(t: String) = t.toIntOrNull()?.let { it >= 10 } ?: false

    /**
     * A whole sheet: one or more calls per line; a bare number of 10 or more is the distance to the next
     * call, and splits the line there ("L4 100 R3 dc" is two calls). A number at the very start is the
     * run-in from the start line, returned as [Sheet.startGap].
     */
    fun parseSheet(text: String): Sheet {
        val out = ArrayList<PaceNote>()
        var startGap = 0
        val cur = ArrayList<String>()
        fun close(dist: Int?) {
            if (cur.isEmpty()) {
                if (dist != null) {
                    if (out.isEmpty()) startGap += dist
                    else out[out.size - 1] = out.last().copy(distAfter = (out.last().distAfter ?: 0) + dist)
                }
                return
            }
            out.add(PaceNote(cur.joinToString(" "), dist))
            cur.clear()
        }
        for (line in text.lines()) {
            val clean = line.trim().removePrefix("-").removePrefix("•").trim()
            if (clean.isEmpty() || clean.startsWith("#")) continue
            for (t in tokens(clean)) {
                if (isDistance(t)) close(t.toInt()) else cur.add(t)
            }
            close(null)
        }
        return Sheet(out, startGap)
    }

    data class Sheet(val notes: List<PaceNote>, val startGap: Int)

    /** What the co-driver says for one call, e.g. "L4- tgt 3 dc" -> "left four minus tightens three don't cut". */
    fun speak(n: PaceNote): String {
        val words = ArrayList<String>()
        for (t in tokens(n.text)) {
            val call = CALL.matchEntire(t)
            val rev = CALL_REV.matchEntire(t)
            val grade = GRADE.matchEntire(t)
            when {
                call != null -> {
                    words += if (call.groupValues[1].lowercase() == "l") "left" else "right"
                    words += grade(call.groupValues[2])
                    sign(call.groupValues[3])?.let { words += it }
                }
                rev != null -> {
                    words += if (rev.groupValues[3].lowercase() == "l") "left" else "right"
                    words += grade(rev.groupValues[1])
                    sign(rev.groupValues[2])?.let { words += it }
                }
                grade != null -> {
                    words += grade(grade.groupValues[1])
                    sign(grade.groupValues[2])?.let { words += it }
                }
                else -> words += WORDS[t.lowercase()] ?: t
            }
        }
        n.distAfter?.let { if (it >= 10) words += it.toString() }
        return words.joinToString(" ")
    }

    private fun grade(g: String) = when (g.uppercase()) {
        "HP" -> "hairpin"; "SQ" -> "square"
        else -> g.toIntOrNull()?.let { NUM.getOrNull(it) } ?: g
    }

    private fun sign(s: String) = when (s) { "+" -> "plus"; "-" -> "minus"; else -> null }

    /**
     * Turns something said on a recce ("left four tightens three don't cut") back into shorthand
     * ("L4 tgt 3 dc"), so voice-made notes look like typed ones.
     */
    fun fromSpeech(said: String): String {
        val nums = mapOf("one" to "1", "two" to "2", "to" to "2", "too" to "2", "three" to "3", "four" to "4", "for" to "4",
            "five" to "5", "six" to "6", "hairpin" to "HP", "square" to "SQ")
        val ws = said.lowercase().replace("don't cut", "dc").replace("do not cut", "dc").replace("keep in", "ki")
            .replace("keep out", "ko").replace("over crest", "ocr").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < ws.size) {
            val w = ws[i]
            val next = ws.getOrNull(i + 1)?.let { nums[it] ?: it.takeIf { n -> n.length == 1 && n[0].isDigit() } }
            if ((w == "left" || w == "right") && next != null) {
                var call = (if (w == "left") "L" else "R") + next
                i += 2
                when (ws.getOrNull(i)) { "plus" -> { call += "+"; i++ }; "minus" -> { call += "-"; i++ } }
                out += call
                continue
            }
            out += when (w) {
                "tightens" -> "tgt"; "opens" -> "opn"; "caution" -> "!"; "crest" -> "cr"; "jump" -> "jmp"
                // "to"/"for" only mean 2/4 straight after left or right.
                "to", "too", "for" -> w
                else -> nums[w] ?: w
            }
            i++
        }
        return out.joinToString(" ")
    }
}
