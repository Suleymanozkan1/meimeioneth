package com.lexiflow.core.language

import kotlinx.serialization.Serializable

@Serializable
enum class IssueCategory(val labelTr: String) {
    GRAMMAR("Gramer"),
    COLLOCATION("Kelime birlikteliği (collocation)"),
    WORD_CHOICE("Kelime seçimi"),
    WORD_USAGE("Hedef kelime kullanımı"),
    MECHANICS("Yazım/noktalama"),
    COHERENCE("Anlam bütünlüğü"),
    REPETITION("Gereksiz tekrar"),
}

@Serializable
data class LanguageIssue(
    val ruleId: String,
    val category: IssueCategory,
    /** Character range in the original text, end exclusive. -1 when the issue is not tied to a span. */
    val start: Int,
    val end: Int,
    val original: String,
    val suggestion: String?,
    val explanationTr: String,
    /** 1 = minor, 2 = noticeable, 3 = changes meaning or clearly wrong. */
    val severity: Int,
    /** True when the rule is certain; false for "worth checking" heuristics. */
    val certain: Boolean = true,
    val examples: List<String> = emptyList(),
)

/** One deterministic rule. [replace] receives the regex match and returns the corrected text. */
internal class RegexRule(
    val id: String,
    val category: IssueCategory,
    pattern: String,
    val severity: Int,
    val explanationTr: String,
    val examples: List<String> = emptyList(),
    val certain: Boolean = true,
    val replace: (MatchResult) -> String?,
) {
    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
}

/**
 * Deterministic grammar, collocation and mechanics rules aimed at common errors of Turkish-speaking
 * learners. Each rule explains *why* in Turkish. Rules are deliberately conservative: when an
 * alternative phrasing is also correct the rule does not fire, so natural answers are never "corrected".
 */
object GrammarRules {

    private fun keepCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercase() } else replacement

    private val makeForms = "(make|makes|made|making)"
    private val doForms = "(do|does|did|doing|done)"

    internal val rules: List<RegexRule> = listOf(
        RegexRule(
            "party_make", IssueCategory.COLLOCATION, "\\b$makeForms an? (big |small |birthday |surprise )?party\\b", 2,
            "İngilizcede parti \"yapılmaz\"; \"have a party\" (parti vermek) veya daha gündelik \"throw a party\" denir. \"Make a party\" Türkçedeki \"parti yapmak\" kalıbının birebir çevirisidir.",
            listOf("We had a party for her birthday.", "They threw a surprise party last night."),
        ) { m ->
            val verb = when (m.groupValues[1].lowercase()) { "made" -> "had"; "making" -> "having"; "makes" -> "has"; else -> "have" }
            keepCase(m.value, m.value.replaceFirst(Regex(m.groupValues[1], RegexOption.IGNORE_CASE), verb))
        },
        RegexRule(
            "mistake_do", IssueCategory.COLLOCATION, "(?<!\\b(?:why|how|when|where|what|which|who) )\\b$doForms (a |an |the |some |many |several |lots of |a lot of )?mistakes?\\b", 2,
            "Hata için \"do\" değil \"make\" kullanılır: \"make a mistake\". \"Do\" genelde iş/görev, \"make\" ise ortaya çıkan bir sonuç için kullanılır.",
            listOf("Everyone makes mistakes.", "I made a mistake in the report."),
        ) { m ->
            val verb = when (m.groupValues[1].lowercase()) { "did" -> "made"; "done" -> "made"; "doing" -> "making"; "does" -> "makes"; else -> "make" }
            keepCase(m.value, m.value.replaceFirst(Regex(m.groupValues[1], RegexOption.IGNORE_CASE), verb))
        },
        RegexRule(
            "homework_make", IssueCategory.COLLOCATION, "\\b$makeForms (my |his |her |our |their |your |the )?(homework|research|exercise|sport|sports|the dishes|housework)\\b(?! (more|less|possible|impossible|easier|harder|fun|interesting|difficult|better|worse|easy|hard|boring|enjoyable|effective))", 2,
            "Ödev, araştırma, egzersiz ve ev işleri için \"do\" kullanılır: \"do homework\", \"do research\", \"do exercise\".",
            listOf("I did my homework after dinner.", "She does research on climate."),
        ) { m ->
            val verb = when (m.groupValues[1].lowercase()) { "made" -> "did"; "making" -> "doing"; "makes" -> "does"; else -> "do" }
            keepCase(m.value, m.value.replaceFirst(Regex(m.groupValues[1], RegexOption.IGNORE_CASE), verb))
        },
        RegexRule(
            "photo_make", IssueCategory.COLLOCATION, "\\b$makeForms (a |some )?(photo|photos|picture|pictures|shower|break|nap)\\b", 2,
            "Fotoğraf çekmek, duş almak, mola vermek için \"take\" kullanılır: \"take a photo\", \"take a shower\", \"take a break\".",
            listOf("Can you take a photo of us?", "Let's take a short break."),
        ) { m ->
            val verb = when (m.groupValues[1].lowercase()) { "made" -> "took"; "making" -> "taking"; "makes" -> "takes"; else -> "take" }
            keepCase(m.value, m.value.replaceFirst(Regex(m.groupValues[1], RegexOption.IGNORE_CASE), verb))
        },
        RegexRule(
            "say_me", IssueCategory.WORD_CHOICE, "\\b(say|says|said) (me|him|us)\\b", 2,
            "\"Say\" doğrudan kişi nesnesi almaz. Kişiye söylemek için \"tell someone\" ya da \"say to someone\" kullanılır.",
            listOf("She told me the truth.", "He said to me that he was tired."),
        ) { m ->
            val verb = when (m.groupValues[1].lowercase()) { "said" -> "told"; "says" -> "tells"; else -> "tell" }
            keepCase(m.value, "$verb ${m.groupValues[2]}")
        },
        RegexRule(
            "discuss_about", IssueCategory.GRAMMAR, "\\b(discuss|discusses|discussed|discussing) about\\b", 2,
            "\"Discuss\" geçişli bir fiildir; \"about\" almaz: \"discuss the plan\". (\"Talk about\" ise doğrudur.)",
            listOf("We discussed the problem.", "Let's talk about the problem."),
        ) { m -> m.groupValues[1] },
        RegexRule(
            "explain_me", IssueCategory.GRAMMAR, "\\b(explain|explains|explained|explaining) (me|him|us)\\b", 2,
            "\"Explain\" kişiyi doğrudan nesne olarak almaz: \"explain something to someone\" veya \"explain to me\".",
            listOf("Can you explain it to me?", "She explained to us how it works."),
        ) { m -> "${m.groupValues[1]} to ${m.groupValues[2]}" },
        RegexRule(
            "listen_to", IssueCategory.GRAMMAR, "\\b(listen|listens|listened|listening) (music|the|my|his|her|our|their|a|this|that|podcasts?|songs?)\\b", 2,
            "\"Listen\" bir şeyi dinlemek anlamında \"to\" ister: \"listen to music\".",
            listOf("I listen to music on the bus."),
        ) { m -> "${m.groupValues[1]} to ${m.groupValues[2]}" },
        RegexRule(
            "depend_of", IssueCategory.GRAMMAR, "\\b(depend|depends|depended|depending|dependent) (of|from)\\b", 2,
            "\"Depend\" edat olarak \"on\" alır: \"it depends on the weather\".",
            listOf("It depends on the price."),
        ) { m -> "${m.groupValues[1]} on" },
        RegexRule(
            "married_with", IssueCategory.GRAMMAR, "\\bmarried with (?=(a|an|the|my|his|her) (man|woman|doctor|teacher|engineer|lawyer|nurse|girl|boy|guy|singer|actor|actress|pilot|footballer)\\b)", 2,
            "Biriyle evli olmak \"married to someone\" ile ifade edilir.",
            listOf("She is married to a doctor."),
        ) { m -> keepCase(m.value, "married to") },
        RegexRule(
            "interested_about", IssueCategory.GRAMMAR, "\\binterested (about|on|for)\\b", 2,
            "\"Interested\" her zaman \"in\" ile kullanılır: \"interested in history\".",
            listOf("I'm interested in photography."),
        ) { m -> keepCase(m.value, "interested in") },
        RegexRule(
            "good_in", IssueCategory.GRAMMAR, "\\b(good|bad|better|terrible|great) in (playing|speaking|cooking|math|maths|english|football|drawing)\\b", 1,
            "Bir beceride iyi/kötü olmak için genellikle \"good at\" kullanılır.",
            listOf("She is good at maths.", "He is bad at cooking."),
        ) { m -> "${m.groupValues[1]} at ${m.groupValues[2]}" },
        RegexRule(
            "arrive_to", IssueCategory.GRAMMAR, "\\b(arrive|arrives|arrived|arriving) to(?= (the|a|an|my|our|his|her|their|this|that|(?-i:[A-Z]))\\b)", 2,
            "\"Arrive\" yön bildiren \"to\" almaz: şehir/ülke için \"arrive in\", bina/nokta için \"arrive at\".",
            listOf("We arrived in Istanbul at noon.", "She arrived at the airport."),
        ) { m -> "${m.groupValues[1]} at" },
        RegexRule(
            "open_light", IssueCategory.WORD_CHOICE, "\\b(open|opened|opens|close|closed|closes) the (light|lights|tv|television|radio|computer)\\b", 2,
            "Elektrikli cihazlar için \"open/close\" değil \"turn on / turn off\" (veya \"switch on/off\") kullanılır. Türkçedeki \"ışığı açmak\" kalıbı birebir çevrilmez.",
            listOf("Could you turn on the light?", "I turned off the TV."),
        ) { m ->
            val v = m.groupValues[1].lowercase()
            val phr = when (v) {
                "open" -> "turn on"; "opened" -> "turned on"; "opens" -> "turns on"
                "close" -> "turn off"; "closed" -> "turned off"; else -> "turns off"
            }
            keepCase(m.value, "$phr the ${m.groupValues[2]}")
        },
        RegexRule(
            "borrow_me", IssueCategory.WORD_CHOICE, "\\b(borrow|borrowed) (me|him|us)\\b", 2,
            "\"Borrow\" ödünç almak, \"lend\" ödünç vermektir. Birine bir şey vermek için \"lend me\" denir.",
            listOf("Can you lend me your pen?", "Can I borrow your pen?"),
        ) { m -> "${if (m.groupValues[1].lowercase() == "borrowed") "lent" else "lend"} ${m.groupValues[2]}" },
        RegexRule(
            "am_agree", IssueCategory.GRAMMAR, "\\b(i am|i'm|we are|we're|they are|they're|he is|she is|he's|she's) agree\\b", 3,
            "\"Agree\" bir fiildir, sıfat değildir; \"be\" fiiliyle kullanılmaz: \"I agree\".",
            listOf("I agree with you.", "She doesn't agree."),
        ) { m ->
            val subject = m.groupValues[1].lowercase().replace("'m", "").replace("'re", "").replace("'s", "")
                .removeSuffix(" am").removeSuffix(" are").removeSuffix(" is")
            val verb = if (subject == "he" || subject == "she") "agrees" else "agree"
            keepCase(m.value, "$subject $verb").replace(Regex("^i "), "I ")
        },
        RegexRule(
            "more_comparative", IssueCategory.GRAMMAR, "\\bmore (better|worse|easier|harder|bigger|smaller|faster|cheaper|happier|older|younger)\\b", 2,
            "Karşılaştırma eki almış sıfatın önüne \"more\" gelmez: \"better\", \"easier\". (\"Much better\" vurgu için doğrudur.)",
            listOf("This way is easier.", "It's much better now."),
        ) { m -> m.groupValues[1] },
        RegexRule(
            "modal_to", IssueCategory.GRAMMAR, "(?<!\\b(?:the|a|from|of|his|her|my|your|their|our|its|free|good|strong|political|own) )\\b(can|could|must|should|will|would|might|(?-i:may)) to (\\w+)", 3,
            "Modal fiillerden (can, must, should…) sonra \"to\" gelmez; fiil yalın hâlde kullanılır. (İstisna: \"ought to\", \"have to\".)",
            listOf("You should try it.", "I can swim."),
        ) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" },
        RegexRule(
            "people_is", IssueCategory.GRAMMAR, "\\bpeople (is|was|has)\\b", 2,
            "\"People\" çoğuldur: \"people are\", \"people were\", \"people have\". (Özne başka bir isimse — \"Meeting new people is fun\" — fiil o isme uyar.)",
            listOf("Many people are reluctant to change."), certain = false,
        ) { m ->
            val v = when (m.groupValues[1].lowercase()) { "is" -> "are"; "was" -> "were"; else -> "have" }
            keepCase(m.value, "people $v")
        },
        RegexRule(
            "since_years", IssueCategory.GRAMMAR, "\\bsince (\\d+|two|three|four|five|six|seven|eight|nine|ten|many|several) (years|months|weeks|days|hours)\\b", 2,
            "Süre (kaç yıl/ay) belirtirken \"for\", başlangıç noktası (2019, Monday) belirtirken \"since\" kullanılır.",
            listOf("I have lived here for five years.", "I have lived here since 2019."),
        ) { m -> "for ${m.groupValues[1]} ${m.groupValues[2]}" },
        RegexRule(
            "have_years_old", IssueCategory.GRAMMAR, "\\b(\\w+) (have|has) (\\d+|[a-z]+(?:-[a-z]+)?) years old\\b", 3,
            "Yaş İngilizcede \"be\" ile söylenir: \"I am 25 years old\". \"Have\" Türkçe/İspanyolca kalıbından gelen bir hatadır.",
            listOf("I am 25 years old.", "She is ten years old."),
        ) { m ->
            val s = m.groupValues[1]
            val be = when {
                s.lowercase() == "i" -> "am"
                m.groupValues[2].lowercase() == "has" -> "is"
                else -> "are"
            }
            "${if (s.lowercase() == "i") "I" else s} $be ${m.groupValues[3]} years old"
        },
        RegexRule(
            "return_back", IssueCategory.REPETITION, "\\b(return|returns|returned|returning) back\\b", 1,
            "\"Return\" zaten \"geri dönmek\" demektir; \"back\" gereksiz tekrar olur. (\"Come back\", \"go back\" doğrudur.)",
            listOf("She returned home late."),
        ) { m -> m.groupValues[1] },
        RegexRule(
            "enter_to", IssueCategory.GRAMMAR, "(?<!\\b(?:is|are|was|were|been|be|being|get|got) )\\b(enter|enters|entered|entering) (to|in) the\\b", 2,
            "Bir yere girmek anlamında \"enter\" edat almaz: \"enter the room\".",
            listOf("He entered the room quietly."),
        ) { m -> "${m.groupValues[1]} the" },
        RegexRule(
            "look_forward_to", IssueCategory.GRAMMAR, "\\blook(s|ing|ed)? forward to (see|meet|hear|work|visit|talk)\\b", 2,
            "\"Look forward to\" içindeki \"to\" bir edattır; ardından \"-ing\" gelir: \"I look forward to seeing you\".",
            listOf("I look forward to hearing from you."),
        ) { m ->
            val v = m.groupValues[2].lowercase()
            val ing = if (v.endsWith("e")) v.dropLast(1) + "ing" else v + "ing"
            "look${m.groupValues[1]} forward to $ing"
        },
        RegexRule(
            "lose_bus", IssueCategory.WORD_CHOICE, "\\b(lose|lost|losing) the (bus|train|plane|flight)\\b", 2,
            "Otobüsü/treni kaçırmak \"miss the bus\" ile söylenir. \"Lose\" bir şeyi kaybetmektir.",
            listOf("I missed the bus this morning."),
        ) { m ->
            val v = when (m.groupValues[1].lowercase()) { "lost" -> "missed"; "losing" -> "missing"; else -> "miss" }
            "$v the ${m.groupValues[2]}"
        },
        RegexRule(
            "despite_of", IssueCategory.GRAMMAR, "\\bdespite of\\b", 2,
            "\"Despite\" doğrudan isim alır; \"of\" kullanılmaz. Alternatif: \"in spite of\".",
            listOf("Despite the rain, we went out."),
        ) { m -> keepCase(m.value, "despite") },
        RegexRule(
            "although_but", IssueCategory.GRAMMAR, "\\b(although|though|even though) ([^.!?]{3,}?), but\\b", 2,
            "\"Although\" ile \"but\" aynı cümlede birlikte kullanılmaz; ikisinden biri yeterlidir.",
            listOf("Although it was late, we kept working.", "It was late, but we kept working."),
        ) { m -> "${m.groupValues[1]} ${m.groupValues[2]}," },
        RegexRule(
            "uncountable_plural", IssueCategory.GRAMMAR, "\\b(informations|advices|furnitures|homeworks|equipments|knowledges|luggages)\\b", 2,
            "Bu isim İngilizcede sayılamaz ve çoğul eki almaz (information, advice, homework…). Miktar için \"some / a piece of\" kullanılır.",
            listOf("She gave me some useful advice.", "I need more information."),
        ) { m -> m.value.dropLast(1) },
        RegexRule(
            "much_people", IssueCategory.GRAMMAR, "\\bmuch (people|friends|cars|books|students|things|problems|questions|words)\\b", 2,
            "Sayılabilen çoğul isimlerle \"much\" değil \"many\" kullanılır. (\"A lot of\" ikisiyle de olur.)",
            listOf("Many people came.", "A lot of people came."),
        ) { m -> keepCase(m.value, "many ${m.groupValues[1]}") },
        RegexRule(
            "many_money", IssueCategory.GRAMMAR, "\\bmany (money|information|water|advice|traffic)\\b", 2,
            "Sayılamayan isimlerle \"many\" değil \"much\" ya da \"a lot of\" kullanılır.",
            listOf("I don't have much time.", "We spent a lot of money."),
        ) { m -> keepCase(m.value, "much ${m.groupValues[1]}") },
        RegexRule(
            "in_the_weekend", IssueCategory.GRAMMAR, "\\bin the weekend\\b", 1,
            "Hafta sonu için İngiliz İngilizcesinde \"at the weekend\", Amerikan İngilizcesinde \"on the weekend\" denir.",
            listOf("What are you doing at the weekend?"),
        ) { m -> keepCase(m.value, "on the weekend") },
        RegexRule(
            "everyday_adverb", IssueCategory.MECHANICS, "(?<=\\w )everyday(?=\\s*[.,!?]|\\s*$)", 1,
            "\"Everyday\" bitişik yazıldığında sıfattır (everyday life). Zarf olarak \"her gün\" anlamında ayrı yazılır: \"every day\".",
            listOf("I practise every day.", "It's part of everyday life."),
        ) { _ -> "every day" },
        RegexRule(
            "according_to_me", IssueCategory.WORD_CHOICE, "\\baccording to me\\b", 1,
            "\"According to\" genelde başka bir kaynak için kullanılır (according to the report). Kendi görüşün için \"in my opinion\" veya \"I think\" daha doğaldır.",
            listOf("In my opinion, it's a good idea."), certain = false,
        ) { m -> keepCase(m.value, "in my opinion") },
        RegexRule(
            "duplicate_word", IssueCategory.MECHANICS, "\\b(\\w+) \\1\\b", 1,
            "Aynı kelime art arda iki kez yazılmış.",
        ) { m -> if (m.groupValues[1].lowercase() in setOf("that", "had", "very", "so", "bye", "no")) null else m.groupValues[1] },
    )

    private val vowelSoundExceptions = setOf("hour", "hours", "honest", "honestly", "honour", "honor", "heir", "honorable", "honourable")
    private val consonantSoundExceptions = setOf(
        "use", "used", "user", "users", "useful", "useless", "usual", "usually", "usage", "utility", "ubiquitous", "unanimous",
        "ukrainian", "ukraine", "uranium", "urinal", "utensil", "utopia", "one", "once", "eulogy", "ewe",
    )

    /** "uni-" is /juː/ (university, union, unique) except the negative prefix "un-" + i (uninvited, unimportant, unidentified). */
    private fun startsWithConsonantSound(lower: String): Boolean =
        lower in consonantSoundExceptions || lower.startsWith("eu") ||
            (lower.startsWith("uni") && lower.length > 3 && lower[3] !in "nmd") || lower.startsWith("uti") || lower.startsWith("usu")
    private val subjectPronouns3rd = setOf("he", "she", "it")
    private val baseVerbs3rd = setOf(
        "go", "have", "want", "like", "need", "live", "work", "know", "think", "make", "say", "get", "play", "love",
        "study", "watch", "speak", "come", "take", "eat", "drink", "run", "try", "hate", "prefer", "seem",
    )
    private val auxBefore = setOf(
        "do", "does", "did", "can", "could", "will", "would", "should", "shall", "may", "might", "must", "to", "let",
        "make", "help", "why", "doesn't", "didn't", "don't", "won't", "can't", "cannot", "wouldn't", "shouldn't",
        "couldn't", "and", "or", "if", "whether",
    )

    /** Runs all rules on [text]. Issues are returned in text order. */
    fun check(text: String): List<LanguageIssue> {
        val issues = mutableListOf<LanguageIssue>()
        for (rule in rules) {
            rule.regex.findAll(text).forEach { m ->
                val suggestion = rule.replace(m) ?: return@forEach
                issues += LanguageIssue(rule.id, rule.category, m.range.first, m.range.last + 1, m.value, suggestion,
                    rule.explanationTr, rule.severity, rule.certain, rule.examples)
            }
        }
        issues += articleIssues(text)
        issues += agreementIssues(text)
        issues += pastAfterDidIssues(text)
        issues += mechanicsIssues(text)
        issues += wordOrderIssues(text)
        return removeOverlaps(issues).sortedBy { it.start }
    }

    const val WORD_ORDER = "word_order_verb_last"

    private val pronounBe = mapOf(
        "i" to setOf("am", "was"), "you" to setOf("are", "were"), "we" to setOf("are", "were"), "they" to setOf("are", "were"),
        "he" to setOf("is", "was"), "she" to setOf("is", "was"), "it" to setOf("is", "was"),
    )

    /** Words that make "… he is" a correct clause ending: "I know who he is", "as tired as she is", "Here it is". */
    private val clauseOpeners = setOf(
        "who", "whom", "whose", "what", "which", "where", "when", "why", "how", "that", "than", "as", "if", "whether",
        "because", "since", "though", "although", "unless", "until", "while", "whatever", "whoever", "wherever",
        "however", "whichever", "here", "there", "matter", "know", "knew", "sure", "wonder", "see", "is", "are", "was",
        // "I think he is.", "Yes, it is.", "Of course she is.", "And so it is."
        "think", "thought", "believe", "hope", "guess", "bet", "say", "said", "suppose", "expect", "feel", "wish", "seems",
        "yes", "no", "yeah", "maybe", "probably", "perhaps", "course", "really", "definitely", "certainly", "indeed",
        "still", "and", "but", "or", "so", "now", "today", "tonight", "already", "not", "hope", "hopefully", "actually",
        "think", "tell", "told", "ask", "asked", "where's", "what's", "who's",
    )

    /**
     * Turkish puts the verb last ("Isteksiz bir adam o"). A sentence that ends in "pronoun + be" with no clause
     * word before it ("Reluctant man he is.") has its subject and verb at the end, which is wrong in English.
     */
    private fun wordOrderIssues(text: String): List<LanguageIssue> {
        val out = mutableListOf<LanguageIssue>()
        Regex("[^.!?]+[.!?]*").findAll(text).forEach { sentence ->
            val words = Regex("[A-Za-z']+").findAll(sentence.value).toList()
            if (words.size < 3) return@forEach
            val pron = words[words.size - 2]
            val verb = words.last()
            val bes = pronounBe[pron.value.lowercase()] ?: return@forEach
            if (verb.value.lowercase() !in bes) return@forEach
            val before = words.dropLast(2)
            if (before.any { it.value.lowercase() in clauseOpeners }) return@forEach
            val phrase = before.joinToString(" ") { it.value }
            val first = before.first().value.lowercase()
            val determiners = setOf("a", "an", "the", "my", "your", "his", "her", "our", "their", "this", "that", "very", "so", "quite", "too", "really", "not", "always", "never")
            val needsArticle = before.size >= 2 && first !in determiners && !before.last().value.lowercase().endsWith("s")
            val complement = phrase.replaceFirstChar { it.lowercase() }.let { c ->
                if (!needsArticle) c else (if (c.first() in "aeiou") "an " else "a ") + c
            }
            val subject = if (pron.value.lowercase() == "i") "I" else pron.value.lowercase().replaceFirstChar { it.uppercase() }
            val start = sentence.range.first + before.first().range.first
            val end = sentence.range.first + verb.range.last + 1
            out += LanguageIssue(
                WORD_ORDER, IssueCategory.GRAMMAR, start, end, text.substring(start, end), "$subject ${verb.value.lowercase()} $complement",
                "İngilizce düz cümlede sıra özne + fiil + tümleçtir. Türkçedeki gibi özneyi ve fiili sona koymak (\"isteksiz bir adam o\") İngilizcede yanlıştır; sayılabilir tekil isimden önce \"a/an\" gerekir.",
                3, examples = listOf("He is a reluctant man.", "She is very happy."),
            )
        }
        return out
    }

    private fun articleIssues(text: String): List<LanguageIssue> {
        val out = mutableListOf<LanguageIssue>()
        Regex("\\b(a|an) ([A-Za-z]+)", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val article = m.groupValues[1]
            val next = m.groupValues[2]
            val lower = next.lowercase()
            if (next.all { it.isUpperCase() }) return@forEach // acronyms and letters: an MBA, a UFO, an X-ray, a U-turn
            // A capital "A" after a word is a label, not the article: "Plan A is better", "vitamin A and C".
            if (article == "A" && text.substring(0, m.range.first).trimEnd().lastOrNull()?.isLetterOrDigit() == true) return@forEach
            val startsVowelSound = when {
                lower in vowelSoundExceptions -> true
                startsWithConsonantSound(lower) -> false
                else -> lower.first() in "aeiou"
            }
            val wrong = (article.equals("a", true) && startsVowelSound) || (article.equals("an", true) && !startsVowelSound)
            if (wrong) {
                val fixed = if (article.equals("a", true)) "an" else "a"
                out += LanguageIssue(
                    "article_a_an", IssueCategory.GRAMMAR, m.range.first, m.range.first + article.length, article,
                    if (article.first().isUpperCase()) fixed.replaceFirstChar { it.uppercase() } else fixed,
                    "\"An\" ünlü sesle (a, e, i, o, u sesi), \"a\" ünsüz sesle başlayan kelimeden önce gelir. Yazı değil ses önemlidir: \"an hour\", \"a university\".",
                    1, examples = listOf("an apple", "an hour", "a university"),
                )
            }
        }
        return out
    }

    private fun agreementIssues(text: String): List<LanguageIssue> {
        val out = mutableListOf<LanguageIssue>()
        val wordRegex = Regex("[A-Za-z']+")
        val words = wordRegex.findAll(text).toList()
        for (i in 0 until words.size - 1) {
            val subj = words[i].value.lowercase()
            val verb = words[i + 1]
            val v = verb.value.lowercase()
            if (subj in subjectPronouns3rd && v == "don't") {
                out += LanguageIssue("agreement_dont", IssueCategory.GRAMMAR, verb.range.first, verb.range.last + 1, verb.value,
                    "doesn't", "He/she/it ile olumsuzda \"doesn't\" kullanılır.", 2, examples = listOf("She doesn't like coffee."))
            }
            // "it" is excluded here: after verbs it is an object ("made it work", "Is it love?").
            if ((subj == "he" || subj == "she") && v in baseVerbs3rd) {
                val prev = if (i > 0) words[i - 1].value.lowercase() else ""
                if (prev in auxBefore) continue
                val fixed = thirdPerson(v)
                out += LanguageIssue("agreement_s", IssueCategory.GRAMMAR, verb.range.first, verb.range.last + 1, verb.value, fixed,
                    "Geniş zamanda he/she/it öznesiyle fiil \"-s\" eki alır: \"she works\", \"he has\".", 2,
                    examples = listOf("She works in a bank.", "He has two brothers."))
            }
        }
        return out
    }

    private fun thirdPerson(v: String): String = when {
        v == "have" -> "has"
        v == "go" -> "goes"
        v == "do" -> "does"
        v.endsWith("y") && v.length > 2 && v[v.length - 2] !in "aeiou" -> v.dropLast(1) + "ies"
        v.endsWith("ch") || v.endsWith("sh") || v.endsWith("s") || v.endsWith("x") -> v + "es"
        else -> v + "s"
    }

    private fun pastAfterDidIssues(text: String): List<LanguageIssue> {
        val out = mutableListOf<LanguageIssue>()
        // Only negative auxiliaries (and "did" + irregular past): "Do married couples…" / "Did Ahmed call?" are correct English.
        Regex("\\b(did|didn't|did not|doesn't|does not|don't|do not) (\\w+)", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val aux = m.groupValues[1].lowercase()
            val verb = m.groupValues[2].lowercase()
            val negative = aux != "did"
            val base = Morphology.PAST_TO_BASE[verb]
                ?: if (negative && verb.endsWith("ed") && verb.length > 4 && verb !in setOf("need", "feed", "seed", "bleed", "speed", "breed", "proceed", "succeed", "exceed", "indeed")) {
                    regularBase(verb)
                } else null
            if (base != null && base != verb) {
                val start = m.range.first + m.value.length - m.groupValues[2].length
                out += LanguageIssue("past_after_did", IssueCategory.GRAMMAR, start, start + m.groupValues[2].length, m.groupValues[2], base,
                    "\"Did / didn't / does / do\" yardımcı fiilinden sonra ana fiil yalın hâlde kullanılır; zaman bilgisini yardımcı fiil taşır.",
                    3, examples = listOf("I didn't go to work.", "Did you see that?"))
            }
        }
        return out
    }

    private fun regularBase(v: String): String {
        val stem = v.removeSuffix("ed")
        return when {
            v.endsWith("ied") -> v.dropLast(3) + "y"
            stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] && stem.last() !in "ls" -> stem.dropLast(1)
            stem.endsWith("v") || stem.endsWith("c") || stem.endsWith("s") && !stem.endsWith("ss") || stem.endsWith("z") || stem.endsWith("at") || stem.endsWith("ut") -> stem + "e"
            else -> stem
        }
    }

    private fun mechanicsIssues(text: String): List<LanguageIssue> {
        val out = mutableListOf<LanguageIssue>()
        Regex("(?<![A-Za-z'.])i(?![A-Za-z'.])|(?<![A-Za-z'.])i(?='(m|ve|ll|d)\\b)").findAll(text).forEach { m ->
            out += LanguageIssue("lowercase_i", IssueCategory.MECHANICS, m.range.first, m.range.last + 1, "i", "I",
                "İngilizcede \"I\" (ben) zamiri her zaman büyük harfle yazılır.", 1)
        }
        val abbreviations = setOf("a.m.", "p.m.", "e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "st.", "approx.", "no.")
        Regex("(^|[.!?]\\s+)([a-z])").findAll(text).forEach { m ->
            val idx = m.range.last
            val before = text.substring(0, m.range.first + 1).trimEnd().substringAfterLast(' ').lowercase()
            if (m.range.first > 0 && before in abbreviations) return@forEach
            out += LanguageIssue("sentence_capital", IssueCategory.MECHANICS, idx, idx + 1, m.groupValues[2], m.groupValues[2].uppercase(),
                "Cümle büyük harfle başlar.", 1)
        }
        return out
    }

    private fun removeOverlaps(issues: List<LanguageIssue>): List<LanguageIssue> {
        val sorted = issues.sortedWith(compareByDescending<LanguageIssue> { it.severity }.thenBy { it.start })
        val kept = mutableListOf<LanguageIssue>()
        for (issue in sorted) {
            if (kept.none { it.start < issue.end && issue.start < it.end }) kept += issue
        }
        return kept
    }

    /** Applies all suggestions (non-overlapping) and returns the corrected text. */
    fun applyCorrections(text: String, issues: List<LanguageIssue>): String {
        val sb = StringBuilder(text)
        issues.filter { it.suggestion != null && it.start >= 0 && it.certain }
            .sortedByDescending { it.start }
            .forEach { sb.replace(it.start, it.end, it.suggestion!!) }
        var result = sb.toString().trim()
        if (result.isNotEmpty() && result.last().isLetterOrDigit()) result += "."
        return result
    }
}
