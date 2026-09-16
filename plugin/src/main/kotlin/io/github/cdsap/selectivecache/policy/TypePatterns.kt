package io.github.cdsap.selectivecache.policy

/** Matches fully-qualified class names against simple '*' wildcard patterns. */
class TypePatterns(patterns: Set<String>) {

    private val regexes: List<Regex> = patterns.map { pattern ->
        val quoted = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        Regex("^$quoted$")
    }

    val isEmpty: Boolean = regexes.isEmpty()

    fun matches(className: String?): Boolean {
        if (className == null || regexes.isEmpty()) return false
        return regexes.any { it.matches(className) }
    }
}
