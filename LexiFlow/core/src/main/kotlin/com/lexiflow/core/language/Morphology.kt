package com.lexiflow.core.language

/** Lightweight English tokenisation and inflection matching used by deterministic checks. */
object Morphology {

    private val tokenRegex = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

    fun tokens(text: String): List<String> = tokenRegex.findAll(text).map { it.value.lowercase() }.toList()

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** All surface forms we accept for a lemma (handles multi-word expressions like "run out of"). */
    fun forms(lemma: String): Set<String> {
        val clean = lemma.trim().lowercase()
        if (clean.contains(' ')) return setOf(clean)
        val out = linkedSetOf(clean)
        IRREGULAR[clean]?.let { out += it }
        out += clean + "s"
        out += clean + "'s"
        if (clean.endsWith("y") && clean.length > 2 && clean[clean.length - 2] !in VOWELS) {
            val stem = clean.dropLast(1)
            out += stem + "ies"; out += stem + "ied"; out += stem + "ier"; out += stem + "iest"; out += stem + "ily"
        }
        if (clean.endsWith("s") || clean.endsWith("x") || clean.endsWith("z") || clean.endsWith("ch") || clean.endsWith("sh") || clean.endsWith("o")) {
            out += clean + "es"
        }
        if (clean.endsWith("e")) {
            out += clean + "d"; out += clean + "r"; out += clean + "st"
            out += clean.dropLast(1) + "ing"
            out += clean.dropLast(1) + "y"
        } else {
            out += clean + "ed"; out += clean + "ing"; out += clean + "er"; out += clean + "est"
        }
        if (isCvc(clean)) {
            val last = clean.last()
            out += clean + last + "ed"; out += clean + last + "ing"; out += clean + last + "er"
        }
        if (clean.endsWith("le")) out += clean.dropLast(1) + "y"
        out += clean + "ly"
        if (clean.endsWith("ic")) out += clean + "ally"
        return out
    }

    /** Index of the first token span that matches [lemma] (any inflection), or -1. */
    fun findUsage(text: String, lemma: String): Int {
        val toks = tokens(text)
        val parts = lemma.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return -1
        val headForms = forms(parts.first())
        for (i in toks.indices) {
            if (toks[i] !in headForms) continue
            if (parts.size == 1) return i
            // Phrasal verbs: allow up to two intervening tokens ("run a company", "look it up").
            var j = i + 1
            var matched = 1
            var gaps = 0
            while (j < toks.size && matched < parts.size && gaps <= 2) {
                if (toks[j] == parts[matched]) matched++ else gaps++
                j++
            }
            if (matched == parts.size) return i
        }
        return -1
    }

    fun usesWord(text: String, lemma: String): Boolean = findUsage(text, lemma) >= 0

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = identical, 0.0 = completely different. */
    fun similarity(a: String, b: String): Double {
        val x = a.lowercase().trim(); val y = b.lowercase().trim()
        val len = maxOf(x.length, y.length)
        if (len == 0) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / len
    }

    private fun isCvc(w: String): Boolean {
        if (w.length < 3 || w.length > 6) return false
        val c1 = w[w.length - 3]; val v = w[w.length - 2]; val c2 = w.last()
        return c1 !in VOWELS && v in VOWELS && c2 !in VOWELS && c2 !in "wxy"
    }

    private const val VOWELS = "aeiou"

    val IRREGULAR: Map<String, List<String>> = mapOf(
        "be" to listOf("am", "is", "are", "was", "were", "been", "being"),
        "have" to listOf("has", "had", "having"),
        "do" to listOf("does", "did", "done", "doing"),
        "go" to listOf("goes", "went", "gone", "going"),
        "run" to listOf("ran", "running", "runs"),
        "make" to listOf("made", "making"),
        "take" to listOf("took", "taken", "taking"),
        "give" to listOf("gave", "given", "giving"),
        "get" to listOf("got", "gotten", "getting"),
        "come" to listOf("came", "coming"),
        "see" to listOf("saw", "seen", "seeing"),
        "know" to listOf("knew", "known"),
        "think" to listOf("thought"),
        "bring" to listOf("brought"),
        "buy" to listOf("bought"),
        "catch" to listOf("caught"),
        "teach" to listOf("taught"),
        "seek" to listOf("sought"),
        "fight" to listOf("fought"),
        "find" to listOf("found"),
        "feel" to listOf("felt"),
        "keep" to listOf("kept"),
        "leave" to listOf("left", "leaving"),
        "lose" to listOf("lost", "losing"),
        "meet" to listOf("met", "meeting"),
        "pay" to listOf("paid"),
        "say" to listOf("said"),
        "sell" to listOf("sold"),
        "send" to listOf("sent"),
        "spend" to listOf("spent"),
        "stand" to listOf("stood"),
        "tell" to listOf("told"),
        "understand" to listOf("understood"),
        "win" to listOf("won", "winning"),
        "write" to listOf("wrote", "written", "writing"),
        "speak" to listOf("spoke", "spoken"),
        "break" to listOf("broke", "broken"),
        "choose" to listOf("chose", "chosen", "choosing"),
        "drive" to listOf("drove", "driven", "driving"),
        "eat" to listOf("ate", "eaten"),
        "fall" to listOf("fell", "fallen"),
        "forget" to listOf("forgot", "forgotten", "forgetting"),
        "forgive" to listOf("forgave", "forgiven"),
        "grow" to listOf("grew", "grown"),
        "hide" to listOf("hid", "hidden", "hiding"),
        "hold" to listOf("held"),
        "lead" to listOf("led"),
        "mislead" to listOf("misled"),
        "overcome" to listOf("overcame", "overcoming"),
        "overlook" to listOf("overlooked", "overlooking"),
        "undertake" to listOf("undertook", "undertaken"),
        "withdraw" to listOf("withdrew", "withdrawn"),
        "rise" to listOf("rose", "risen", "rising"),
        "arise" to listOf("arose", "arisen", "arising"),
        "shake" to listOf("shook", "shaken"),
        "throw" to listOf("threw", "thrown"),
        "wear" to listOf("wore", "worn"),
        "begin" to listOf("began", "begun", "beginning"),
        "drink" to listOf("drank", "drunk"),
        "swim" to listOf("swam", "swum", "swimming"),
        "sing" to listOf("sang", "sung"),
        "set" to listOf("setting"),
        "put" to listOf("putting"),
        "let" to listOf("letting"),
        "cut" to listOf("cutting"),
        "hit" to listOf("hitting"),
        "quit" to listOf("quitting"),
        "build" to listOf("built"),
        "lend" to listOf("lent"),
        "bend" to listOf("bent"),
        "sleep" to listOf("slept"),
        "sweep" to listOf("swept"),
        "deal" to listOf("dealt"),
        "mean" to listOf("meant"),
        "hear" to listOf("heard"),
        "read" to listOf("reading"),
        "sit" to listOf("sat", "sitting"),
        "light" to listOf("lit"),
        "strive" to listOf("strove", "striven", "striving"),
        "bear" to listOf("bore", "borne"),
        "good" to listOf("better", "best"),
        "bad" to listOf("worse", "worst"),
        "child" to listOf("children"),
        "person" to listOf("people"),
        "man" to listOf("men"),
        "woman" to listOf("women"),
        "analysis" to listOf("analyses"),
        "crisis" to listOf("crises"),
        "phenomenon" to listOf("phenomena"),
        "criterion" to listOf("criteria"),
    )

    /** Base forms of irregular past tense / participles, used by grammar rules ("didn't went"). */
    val PAST_TO_BASE: Map<String, String> = buildMap {
        IRREGULAR.forEach { (base, forms) ->
            forms.filter { !it.endsWith("ing") && !it.endsWith("s") }.forEach { put(it, base) }
        }
        remove("better"); remove("best"); remove("worse"); remove("worst")
        remove("children"); remove("people"); remove("men"); remove("women")
        remove("am"); remove("are"); remove("been"); remove("being")
        remove("analyses"); remove("crises"); remove("phenomena"); remove("criteria")
        remove("read"); remove("set"); remove("put"); remove("let"); remove("cut"); remove("hit"); remove("quit")
    }
}
