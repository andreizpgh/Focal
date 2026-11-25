package app.focal.ui.state

/** Resolution of a scrubber position: the highlighted word (if any) and the playback target (null to park near end). */
data class SeekSnapResult(val location: WordLocation?, val targetMs: Long?)

/**
 * Pure, zero-allocation O(log P + log W) scrubber word-seek resolution: shares one locate rule between the live
 * scrub lens and release/accessibility seek so "what is lit is what plays, from its start".
 */
object SeekSnap {
    const val ONSET_WINDOW_MS = 600L
    const val LEAD_INTO_PREV_MS = 40L
    const val MIN_LEAD_MS = 80L
    const val BRIDGE_PREV_LEAD_MS = 80L
    const val MAX_REWIND_MS = 1500L
    const val END_PARK_MS = 300L

    fun locateScrub(paragraphs: List<ParagraphUi>, gaps: List<GapUi>, scrubMs: Long): WordLocation? =
        resolve(paragraphs, gaps, scrubMs, durationMs = 0L).location

    fun target(paragraphs: List<ParagraphUi>, gaps: List<GapUi>, scrubMs: Long, durationMs: Long): Long? =
        resolve(paragraphs, gaps, scrubMs, durationMs).targetMs

    fun resolve(
        paragraphs: List<ParagraphUi>,
        gaps: List<GapUi>,
        scrubMs: Long,
        durationMs: Long,
    ): SeekSnapResult {
        val clampedScrub = if (durationMs > 0L) scrubMs.coerceIn(0L, durationMs) else scrubMs.coerceAtLeast(0L)

        // 1. Untranscribed gap first: raw audio has no word boundaries and lights no word.
        for (i in gaps.indices) {
            val gap = gaps[i]
            if (clampedScrub >= gap.startMs && clampedScrub < gap.endMs) {
                val target = maxOf(gap.startMs, clampedScrub - PlaybackViewModel.SCRUB_LEAD_MS).coerceAtLeast(0L)
                val clampedTarget = clampTarget(target, durationMs)
                return SeekSnapResult(
                    location = null,
                    targetMs = applyEndPark(clampedTarget, releaseMs = clampedScrub, durationMs = durationMs, litWord = false),
                )
            }
        }

        if (paragraphs.isEmpty()) {
            val clampedTarget = clampTarget(clampedScrub, durationMs)
            return SeekSnapResult(
                location = null,
                targetMs = applyEndPark(clampedTarget, releaseMs = clampedScrub, durationMs = durationMs, litWord = false),
            )
        }

        val paragraphIndex = findParagraphIndex(paragraphs, clampedScrub)

        // 5. Legacy paragraphs without word times: subtract scrub lead directly within the paragraph's own span,
        // leaving silence after it free to snap into an adjacent timed paragraph.
        if (paragraphIndex >= 0 &&
            !hasAnyTimedWord(paragraphs[paragraphIndex].words) &&
            clampedScrub <= paragraphs[paragraphIndex].endMs
        ) {
            val raw = (clampedScrub - PlaybackViewModel.SCRUB_LEAD_MS).coerceAtLeast(0L)
            val clampedTarget = clampTarget(raw, durationMs)
            return SeekSnapResult(
                location = WordLocation(paragraphIndex, null),
                targetMs = applyEndPark(clampedTarget, releaseMs = clampedScrub, durationMs = durationMs, litWord = false),
            )
        }

        // Handle edited single-word untimed bridge inside the active paragraph when closer than adjacent timed onset.
        if (paragraphIndex >= 0) {
            editedBridgeResolution(paragraphs, paragraphIndex, clampedScrub, durationMs)?.let { return it }
        }

        // 2. General O(log P + log W) nearest-onset lookup across paragraphs (zero heap allocations).
        var prevP = -1
        var prevW = -1
        var nextP = -1
        var nextW = -1

        if (paragraphIndex >= 0) {
            val pWords = paragraphs[paragraphIndex].words
            val prevInP = findLastTimedBeforeOrAt(pWords, paragraphs[paragraphIndex].exactTimes, clampedScrub)
            if (prevInP >= 0) {
                val prevWord = pWords[prevInP]
                val end = prevWord.endMs
                if (end != null && clampedScrub <= end) {
                    return resolveTimedWord(paragraphs, paragraphIndex, prevInP, clampedScrub, durationMs)
                }
                prevP = paragraphIndex
                prevW = prevInP
            }
            val nextInP = findFirstTimedAfter(pWords, paragraphs[paragraphIndex].exactTimes, clampedScrub, prevInP)
            if (nextInP >= 0) {
                nextP = paragraphIndex
                nextW = nextInP
            }
        }

        if (prevW < 0) {
            for (pi in (paragraphIndex - 1) downTo 0) {
                val wIdx = findLastTimedInParagraph(paragraphs[pi].words)
                if (wIdx >= 0) {
                    prevP = pi
                    prevW = wIdx
                    break
                }
            }
        }
        if (nextW < 0) {
            val startNi = if (paragraphIndex >= 0) paragraphIndex + 1 else 0
            for (ni in startNi until paragraphs.size) {
                val wIdx = findFirstTimedInParagraph(paragraphs[ni].words)
                if (wIdx >= 0) {
                    nextP = ni
                    nextW = wIdx
                    break
                }
            }
        }

        if (prevW < 0 && nextW < 0) {
            val location = if (paragraphIndex >= 0) WordLocation(paragraphIndex, null) else null
            val clampedTarget = clampTarget(clampedScrub, durationMs)
            return SeekSnapResult(
                location = location,
                targetMs = applyEndPark(clampedTarget, releaseMs = clampedScrub, durationMs = durationMs, litWord = false),
            )
        }

        val prevDistance = if (prevP >= 0) {
            val prevWord = paragraphs[prevP].words[prevW]
            val boundary = prevWord.endMs ?: prevWord.startMs!!
            (clampedScrub - boundary).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val nextDistance = if (nextP >= 0) {
            (paragraphs[nextP].words[nextW].startMs!! - clampedScrub).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }

        if (prevDistance > ONSET_WINDOW_MS && nextDistance > ONSET_WINDOW_MS) {
            val location = if (paragraphIndex >= 0) WordLocation(paragraphIndex, null) else null
            val clampedTarget = clampTarget(clampedScrub, durationMs)
            return SeekSnapResult(
                location = location,
                targetMs = applyEndPark(clampedTarget, releaseMs = clampedScrub, durationMs = durationMs, litWord = false),
            )
        }

        val useNext = nextP >= 0 && nextDistance < prevDistance
        val chosenP = if (useNext) nextP else prevP
        val chosenW = if (useNext) nextW else prevW
        return resolveTimedWord(paragraphs, chosenP, chosenW, clampedScrub, durationMs)
    }

    private fun editedBridgeResolution(
        paragraphs: List<ParagraphUi>,
        paragraphIndex: Int,
        clampedScrub: Long,
        durationMs: Long,
    ): SeekSnapResult? {
        val paragraph = paragraphs[paragraphIndex]
        if (paragraph.exactTimes) return null
        val words = paragraph.words
        if (words.isEmpty()) return null

        var lastTimed = -1
        var nextTimed = -1
        for (i in words.indices) {
            val start = words[i].startMs ?: continue
            if (start <= clampedScrub) lastTimed = i else {
                nextTimed = i
                break
            }
        }

        // Leading single untimed word at paragraph start
        if (lastTimed < 0 && nextTimed == 1 && clampedScrub >= paragraph.startMs) {
            val nextStart = words[1].startMs!!
            val dPara = (clampedScrub - paragraph.startMs).coerceAtLeast(0L)
            val dNext = (nextStart - clampedScrub).coerceAtLeast(0L)
            if (minOf(dPara, dNext) > ONSET_WINDOW_MS) return null
            if (dPara <= dNext) {
                val target = capAndClamp((paragraph.startMs - PlaybackViewModel.SCRUB_LEAD_MS).coerceAtLeast(0L), clampedScrub, durationMs)
                return SeekSnapResult(
                    location = WordLocation(paragraphIndex, 0),
                    targetMs = applyEndPark(target, releaseMs = clampedScrub, durationMs = durationMs, litWord = true),
                )
            }
            return null
        }

        if (lastTimed < 0) return null
        val prevWord = words[lastTimed]
        val boundary = prevWord.endMs ?: prevWord.startMs ?: return null
        if (clampedScrub < boundary) return null
        val gapCount = (if (nextTimed >= 0) nextTimed else words.size) - lastTimed - 1
        if (gapCount != 1) return null

        val dPrev = (clampedScrub - boundary).coerceAtLeast(0L)
        val dNext = if (nextTimed >= 0) (words[nextTimed].startMs!! - clampedScrub).coerceAtLeast(0L) else Long.MAX_VALUE
        if (dPrev > ONSET_WINDOW_MS && dNext > ONSET_WINDOW_MS) return null
        if (nextTimed >= 0 && dNext < dPrev) return null

        val rawTarget = prevWord.endMs?.let { it - BRIDGE_PREV_LEAD_MS }
            ?: (prevWord.startMs!! - leadForTimedIndex(paragraphs, paragraphIndex, lastTimed))
        val target = capAndClamp(rawTarget, clampedScrub, durationMs)
        return SeekSnapResult(
            location = WordLocation(paragraphIndex, lastTimed + 1),
            targetMs = applyEndPark(target, releaseMs = clampedScrub, durationMs = durationMs, litWord = true),
        )
    }

    private fun resolveTimedWord(
        paragraphs: List<ParagraphUi>,
        paragraphIndex: Int,
        wordIndex: Int,
        clampedScrub: Long,
        durationMs: Long,
    ): SeekSnapResult {
        val wordStart = paragraphs[paragraphIndex].words[wordIndex].startMs!!
        val lead = leadForTimedIndex(paragraphs, paragraphIndex, wordIndex)
        val target = capAndClamp(wordStart - lead, clampedScrub, durationMs)
        return SeekSnapResult(
            location = WordLocation(paragraphIndex, wordIndex),
            targetMs = applyEndPark(target, releaseMs = clampedScrub, durationMs = durationMs, litWord = true),
        )
    }

    private fun leadForTimedIndex(paragraphs: List<ParagraphUi>, paragraphIndex: Int, wordIndex: Int): Long {
        val words = paragraphs[paragraphIndex].words
        val wordStart = words[wordIndex].startMs ?: return PlaybackViewModel.SCRUB_LEAD_MS
        var prevEnd: Long? = null
        var prevFound = false
        for (i in wordIndex - 1 downTo 0) {
            if (words[i].startMs != null) {
                prevEnd = words[i].endMs
                prevFound = true
                break
            }
        }
        if (!prevFound) {
            for (pi in paragraphIndex - 1 downTo 0) {
                val prevW = findLastTimedInParagraph(paragraphs[pi].words)
                if (prevW >= 0) {
                    prevEnd = paragraphs[pi].words[prevW].endMs
                    break
                }
            }
        }
        return if (prevEnd != null) {
            (wordStart - prevEnd + LEAD_INTO_PREV_MS).coerceIn(MIN_LEAD_MS, PlaybackViewModel.SCRUB_LEAD_MS)
        } else {
            PlaybackViewModel.SCRUB_LEAD_MS
        }
    }

    private fun capAndClamp(candidateMs: Long, clampedScrub: Long, durationMs: Long): Long {
        val capped = if (clampedScrub - candidateMs > MAX_REWIND_MS) {
            (clampedScrub - PlaybackViewModel.SCRUB_LEAD_MS).coerceAtLeast(0L)
        } else {
            candidateMs
        }
        return clampTarget(capped, durationMs)
    }

    private fun clampTarget(ms: Long, durationMs: Long): Long =
        if (durationMs > 0L) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)

    private fun applyEndPark(target: Long, releaseMs: Long, durationMs: Long, litWord: Boolean): Long? =
        if (durationMs > 0L && !litWord && durationMs - releaseMs <= END_PARK_MS) null else target

    private fun findParagraphIndex(paragraphs: List<ParagraphUi>, positionMs: Long): Int {
        var low = 0
        var high = paragraphs.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (paragraphs[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    private fun hasAnyTimedWord(words: List<WordUi>): Boolean {
        for (i in words.indices) {
            if (words[i].startMs != null) return true
        }
        return false
    }

    private fun findLastTimedBeforeOrAt(words: List<WordUi>, exactTimes: Boolean, positionMs: Long): Int {
        if (words.isEmpty()) return -1
        if (exactTimes) {
            var low = 0
            var high = words.lastIndex
            var found = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (words[mid].startMs!! <= positionMs) {
                    found = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return found
        }
        var found = -1
        for (i in words.indices) {
            val start = words[i].startMs ?: continue
            if (start <= positionMs) found = i else break
        }
        return found
    }

    private fun findFirstTimedAfter(words: List<WordUi>, exactTimes: Boolean, positionMs: Long, prevInP: Int): Int {
        if (words.isEmpty()) return -1
        if (exactTimes) {
            val candidate = prevInP + 1
            return if (candidate < words.size) candidate else -1
        }
        val startIdx = (prevInP + 1).coerceAtLeast(0)
        for (i in startIdx until words.size) {
            val start = words[i].startMs ?: continue
            if (start > positionMs) return i
        }
        return -1
    }

    private fun findFirstTimedInParagraph(words: List<WordUi>): Int {
        for (i in words.indices) {
            if (words[i].startMs != null) return i
        }
        return -1
    }

    private fun findLastTimedInParagraph(words: List<WordUi>): Int {
        for (i in words.lastIndex downTo 0) {
            if (words[i].startMs != null) return i
        }
        return -1
    }
}
