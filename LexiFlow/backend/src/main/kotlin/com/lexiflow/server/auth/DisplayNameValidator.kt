package com.lexiflow.server.auth

import java.text.Normalizer
import java.util.Locale

/**
 * Validates public display names: 2-24 characters, letters (any script, incl. Turkish), digits, space, `_` and `-`,
 * plus a basic profanity/impersonation block list. This is a first line of defence; reports are the second.
 */
object DisplayNameValidator {
    const val MIN = 2
    const val MAX = 24

    private val allowed = Regex("^[\\p{L}\\p{N} _-]+$")

    /** Blocked when found anywhere in the compacted name (long enough to avoid false positives). */
    private val blockedSubstrings = listOf(
        "fuck", "shit", "bitch", "cunt", "whore", "slut", "asshole", "bastard", "pussy", "nigger", "nigga", "faggot",
        "retard", "hitler", "nazi", "rapist",
        "orospu", "yarrak", "yarak", "pezevenk", "kahpe", "amcık", "amcik", "sikerim", "sikik", "siktir", "amına", "amina",
        "götveren", "gotveren", "ibne", "kaltak", "gavat",
    )

    /** Blocked only as a whole word (short words that appear inside harmless words, e.g. "klasik", "götür"). */
    private val blockedWords = setOf(
        "sik", "amk", "aq", "göt", "piç", "dick", "cock", "rape", "porn", "sex", "oç", "mk",
    )

    /** Names that could be used to impersonate staff. */
    private val reserved = setOf("admin", "administrator", "moderator", "mod", "lexiflow", "system", "support", "staff")

    /** Trims and collapses whitespace; returns the cleaned name. */
    fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()

    /** Returns null when valid, otherwise a short error message (English + Turkish). */
    fun validate(name: String): String? {
        if (name.length < MIN || name.length > MAX) return "displayName must be $MIN-$MAX characters / Kullanıcı adı $MIN-$MAX karakter olmalı"
        if (!allowed.matches(name)) return "displayName may contain only letters, digits, space, _ and - / Sadece harf, rakam, boşluk, _ ve - kullanılabilir"
        if (!name.any { it.isLetter() }) return "displayName must contain a letter / En az bir harf içermeli"
        if (isBlocked(name)) return "displayName is not allowed / Bu kullanıcı adı kullanılamaz"
        return null
    }

    private fun isBlocked(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val words = lower.split(' ', '_', '-').filter { it.isNotEmpty() }
        val deLeet = deLeet(lower)
        val compact = deLeet.filter { it.isLetter() }
        val compactAscii = foldTurkish(compact)
        if (words.any { it in reserved || deLeet(it) in reserved }) return true
        if (words.any { w -> deLeet(w) in blockedWords }) return true
        return blockedSubstrings.any { b -> compact.contains(b) || compactAscii.contains(foldTurkish(b)) }
    }

    private fun deLeet(s: String): String = s.map {
        when (it) {
            '0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '4' -> 'a'; '5' -> 's'; '7' -> 't'; '@' -> 'a'; '$' -> 's'
            else -> it
        }
    }.joinToString("")

    private fun foldTurkish(s: String): String = s
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u').replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
}
