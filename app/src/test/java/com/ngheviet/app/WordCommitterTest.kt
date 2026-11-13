package com.ngheviet.app
import org.junit.Assert.*
import org.junit.Test
class WordCommitterTest {
    @Test fun commitsWithoutWaitingForSilenceAndNeverRepeats() {
        val c=WordCommitter()
        assertEquals(emptyList<String>(),c.update("please",1000))
        assertEquals(listOf("please"),c.update("please",1500))
        assertEquals(emptyList<String>(),c.update("please",2000))
        c.update("please move",2100)
        assertEquals(listOf("move"),c.update("please move",2600))
    }
    @Test fun groupsCommonPhrasalVerb() {
        val c=WordCommitter(); c.update("pick",1000)
        assertTrue(c.update("pick",1500).isEmpty())
        c.update("pick up",1600)
        assertEquals(listOf("pick up"),c.update("pick up",2100))
    }
    @Test fun flushesTailAtEndpointAndResetsForNextUtterance() {
        val c=WordCommitter()
        assertEquals(listOf("hello"),c.update("hello",1000,true))
        assertEquals(listOf("hello"),c.update("hello",2000,true))
    }
    @Test fun doesNotReplayAlreadySpokenPositionsOnRecognitionRevision() {
        val c=WordCommitter(); c.update("a box",1000)
        assertEquals(listOf("a","box"),c.update("a box",1500))
        c.update("the box",1700)
        assertTrue(c.update("the box",2200).isEmpty())
        c.update("the box here",2300)
        assertEquals(listOf("here"),c.update("the box here",2800))
    }
    @Test fun changingHypothesisDoesNotCommitAnUnstableWord() {
        val c=WordCommitter(); c.update("cat",1000)
        assertTrue(c.update("cap",1100).isEmpty())
        assertTrue(c.update("cab",1200).isEmpty())
        assertEquals(listOf("cab"),c.update("cab",1800))
    }
}
