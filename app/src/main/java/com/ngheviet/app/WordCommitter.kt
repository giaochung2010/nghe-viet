package com.ngheviet.app

/** Commits source positions, never target prefixes: translations may reorder words.
 * A changed already-spoken source token cannot be unspoken; do not replay it.
 * Deliberately trades contextual accuracy for short, immutable speech chunks.
 */
class WordCommitter {
    private var previous = emptyList<String>()
    private var committed = 0
    private var changedAt = 0L
    private var lastCommitAt = 0L
    private val phraseHeads = setOf("pick", "turn", "put", "look", "get", "take", "give", "go", "come", "check", "find", "set")
    private val particles = setOf("up", "off", "on", "out", "in", "over", "back", "down", "away", "after", "for")

    fun update(text: String, now: Long, endpoint: Boolean = false): List<String> {
        val words = text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var stable = 0
        while (stable < minOf(previous.size, words.size) && previous[stable] == words[stable]) stable++
        if (words != previous) changedAt = now
        var available = if (endpoint) words.size else stable
        // A final unchanging token can be committed after a short hold, even without silence.
        if (!endpoint && words == previous && now - changedAt >= 450) available = words.size
        val result = mutableListOf<String>()
        if (endpoint || now - lastCommitAt >= 450) {
            val start = committed
            while (committed < available) {
                val head = words[committed]
                if (!endpoint && head in phraseHeads && committed + 1 >= available && now - changedAt < 800) break
                val count = if (head in phraseHeads && committed + 1 < available && words[committed + 1] in particles) 2 else 1
                result += words.subList(committed, committed + count).joinToString(" ")
                committed += count
            }
            if (committed > start) lastCommitAt = now
        }
        previous = words
        if (endpoint) reset()
        return result
    }
    fun reset() { previous = emptyList(); committed = 0; changedAt = 0; lastCommitAt = 0 }
}
