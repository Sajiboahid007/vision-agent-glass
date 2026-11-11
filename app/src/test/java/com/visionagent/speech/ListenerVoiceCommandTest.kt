package com.visionagent.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerVoiceCommandTest {

    @Test
    fun testUniversalStopParsing() {
        val stopPhrases = listOf(
            "stop",
            "Stop",
            "stop vision",
            "vision stop",
            "stop vision.",
            "STOP!",
            "থামো",
            "থামো।",
            "বন্ধ করো"
        )
        for (phrase in stopPhrases) {
            val cmd = Listener.parseVoiceCommand(phrase, if (phrase.contains("থামো") || phrase.contains("বন্ধ")) "bn" else "en")
            assertEquals("Expected Stop for '$phrase'", VoiceCommand.Stop, cmd)
        }
    }

    @Test
    fun testDescribeParsing() {
        val phrases = listOf(
            "describe",
            "vision describe",
            "tell me vision",
            "what are you seeing",
            "what do you see?",
            "describe what you see.",
            "বর্ণনা করো",
            "কি দেখছো?",
            "বলো ভিশন"
        )
        for (phrase in phrases) {
            val cmd = Listener.parseVoiceCommand(phrase, if (phrase.contains("বর্ণনা") || phrase.contains("দেখছো") || phrase.contains("ভিশন")) "bn" else "en")
            assertEquals("Expected Describe for '$phrase'", VoiceCommand.Describe, cmd)
        }
    }

    @Test
    fun testLiveReadParsing() {
        val phrases = listOf(
            "live read",
            "live reading",
            "live text",
            "read live",
            "লাইভ পড়ো",
            "লাইভ রিডিং"
        )
        for (phrase in phrases) {
            val cmd = Listener.parseVoiceCommand(phrase, if (phrase.contains("লাইভ")) "bn" else "en")
            assertEquals("Expected LiveRead for '$phrase'", VoiceCommand.LiveRead, cmd)
        }
    }

    @Test
    fun testReadTextParsing() {
        val phrases = listOf(
            "read",
            "read text",
            "vision read",
            "read this",
            "লেখা পড়ো",
            "পড়ে শোনাও"
        )
        for (phrase in phrases) {
            val cmd = Listener.parseVoiceCommand(phrase, if (phrase.contains("পড়ো") || phrase.contains("শোনাও")) "bn" else "en")
            assertEquals("Expected ReadText for '$phrase'", VoiceCommand.ReadText, cmd)
        }
    }

    @Test
    fun testAskParsingWithoutQuestion() {
        val phrases = listOf(
            "vision",
            "hey vision",
            "vision ask",
            "ask",
            "ask vision",
            "ভিশন"
        )
        for (phrase in phrases) {
            val cmd = Listener.parseVoiceCommand(phrase, if (phrase.contains("ভিশন")) "bn" else "en")
            assertNotNull("Expected non-null command for '$phrase'", cmd)
            assertTrue("Expected VoiceCommand.Ask for '$phrase'", cmd is VoiceCommand.Ask)
            assertNull("Direct question should be null for '$phrase'", (cmd as VoiceCommand.Ask).directQuestion)
        }
    }

    @Test
    fun testAskParsingWithDirectQuestion() {
        val cmd1 = Listener.parseVoiceCommand("vision what is in front of me?", "en")
        assertNotNull(cmd1)
        assertTrue(cmd1 is VoiceCommand.Ask)
        assertEquals("what is in front of me", (cmd1 as VoiceCommand.Ask).directQuestion)

        val cmd2 = Listener.parseVoiceCommand("hey vision is there a door ahead?", "en")
        assertNotNull(cmd2)
        assertTrue(cmd2 is VoiceCommand.Ask)
        assertEquals("is there a door ahead", (cmd2 as VoiceCommand.Ask).directQuestion)

        val cmd3 = Listener.parseVoiceCommand("ভিশন সামনে কি কোনো চেয়ার আছে?", "bn")
        assertNotNull(cmd3)
        assertTrue(cmd3 is VoiceCommand.Ask)
        assertEquals("সামনে কি কোনো চেয়ার আছে", (cmd3 as VoiceCommand.Ask).directQuestion)
    }

    @Test
    fun testNonCommandReturnsNull() {
        val result = Listener.parseVoiceCommand("today is a nice day", "en")
        assertNull("General sentences should not trigger commands", result)
    }
}
