package com.lexiflow.core.language

/** A sentence with one typical learner error and its corrected version (original LexiFlow content). */
data class GrammarDrill(val wrong: String, val correct: String, val ruleId: String, val focusTr: String)

object GrammarDrills {
    val all: List<GrammarDrill> = listOf(
        GrammarDrill("I made a party for my sister's birthday.", "I had a party for my sister's birthday.", "party_make", "have/throw a party"),
        GrammarDrill("Everybody does mistakes when they learn a language.", "Everybody makes mistakes when they learn a language.", "mistake_do", "make a mistake"),
        GrammarDrill("I have to make my homework tonight.", "I have to do my homework tonight.", "homework_make", "do homework"),
        GrammarDrill("Can you make a photo of us?", "Can you take a photo of us?", "photo_make", "take a photo"),
        GrammarDrill("She said me that she was tired.", "She told me that she was tired.", "say_me", "tell someone"),
        GrammarDrill("We discussed about the budget for hours.", "We discussed the budget for hours.", "discuss_about", "discuss + nesne"),
        GrammarDrill("Could you explain me the rules?", "Could you explain the rules to me?", "explain_me", "explain sth to sb"),
        GrammarDrill("I usually listen music on the bus.", "I usually listen to music on the bus.", "listen_to", "listen to"),
        GrammarDrill("It depends of the weather.", "It depends on the weather.", "depend_of", "depend on"),
        GrammarDrill("He is married with a doctor.", "He is married to a doctor.", "married_with", "married to"),
        GrammarDrill("I'm interested about history.", "I'm interested in history.", "interested_about", "interested in"),
        GrammarDrill("We arrived to the hotel late.", "We arrived at the hotel late.", "arrive_to", "arrive at/in"),
        GrammarDrill("Can you open the light, please?", "Can you turn on the light, please?", "open_light", "turn on/off"),
        GrammarDrill("I am agree with your idea.", "I agree with your idea.", "am_agree", "agree (fiil)"),
        GrammarDrill("This phone is more better than my old one.", "This phone is better than my old one.", "more_comparative", "karşılaştırma"),
        GrammarDrill("You should to call her.", "You should call her.", "modal_to", "modal + yalın fiil"),
        GrammarDrill("People is reluctant to change their habits.", "People are reluctant to change their habits.", "people_is", "people + are"),
        GrammarDrill("I have worked here since three years.", "I have worked here for three years.", "since_years", "for / since"),
        GrammarDrill("My brother has twenty years old.", "My brother is twenty years old.", "have_years_old", "yaş: be + years old"),
        GrammarDrill("I look forward to see you.", "I look forward to seeing you.", "look_forward_to", "look forward to + -ing"),
        GrammarDrill("I lost the bus this morning.", "I missed the bus this morning.", "lose_bus", "miss the bus"),
        GrammarDrill("Despite of the rain, we went out.", "Despite the rain, we went out.", "despite_of", "despite + isim"),
        GrammarDrill("She gave me some useful advices.", "She gave me some useful advice.", "uncountable_plural", "sayılamayan isimler"),
        GrammarDrill("I didn't went to the meeting.", "I didn't go to the meeting.", "past_after_did", "did + yalın fiil"),
        GrammarDrill("She don't like coffee.", "She doesn't like coffee.", "agreement_dont", "he/she/it + doesn't"),
        GrammarDrill("He work in a bank.", "He works in a bank.", "agreement_s", "3. tekil şahıs -s"),
        GrammarDrill("I ate a apple after lunch.", "I ate an apple after lunch.", "article_a_an", "a / an"),
        GrammarDrill("Although it was late, but we kept working.", "Although it was late, we kept working.", "although_but", "although + but olmaz"),
    )
}
