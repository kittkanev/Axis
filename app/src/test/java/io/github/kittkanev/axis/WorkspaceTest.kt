package io.github.kittkanev.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    private val profile = Profile("first", "Тест", "https://example.com/v1", "chat", "encrypted")
    private val input = ProfileInput(profile.name, profile.baseUrl, profile.model, "example-key")

    @Test
    fun onlyHttpsBaseUrlsWithoutCredentialsOrCompletionPathsAreAccepted() {
        val rejected = listOf(
            "http://example.com/v1",
            "https://name:password@example.com/v1",
            "https://example.com/v1?key=secret",
            "https://example.com/v1#fragment",
            "https://example.com/v1/chat/completions/",
        )
        rejected.forEach { url ->
            assertThrows(AxisException::class.java) { validateProfile(input.copy(baseUrl = url), null) }
        }
        assertEquals(profile.baseUrl, validateProfile(input.copy(baseUrl = profile.baseUrl + "/"), null).baseUrl.toString())
    }

    @Test
    fun changingEndpointRequiresNewKeyAndDropsExistingProviderContext() {
        val otherUrl = "https://other.example.com/v1"
        val existingKeyInput = input.copy(key = "")
        assertEquals("", validateProfile(existingKeyInput, profile).key)
        assertThrows(AxisException::class.java) {
            validateProfile(existingKeyInput.copy(baseUrl = otherUrl), profile)
        }
        assertThrows(AxisException::class.java) { validateProfile(existingKeyInput, null) }
        assertThrows(AxisException::class.java) { validateProfile(input.copy(key = "abc\r\ndef"), null) }

        val message = Message(Role.USER, "private context")
        val workspace = Workspace().saveProfile(profile).copy(chats = mapOf(profile.id to listOf(message)))
        assertEquals(listOf(message), workspace.saveProfile(profile.copy(model = "another-model")).messages)
        assertTrue(workspace.saveProfile(profile.copy(baseUrl = otherUrl)).messages.isEmpty())
    }

    @Test
    fun deletionDropsOnlyTheRequestedProfileAndItsHistory() {
        val other = profile.copy(id = "second")
        val message = Message(Role.USER, "different profile")
        val workspace = Workspace().saveProfile(profile).saveProfile(other).copy(
            chats = mapOf(profile.id to emptyList(), other.id to listOf(message)),
        )
        val result = workspace.removeProfile(profile.id)
        assertEquals(listOf(other), result.profiles)
        assertEquals(listOf(message), result.messages)
        assertFalse(result.chats.containsKey(profile.id))
    }

    @Test
    fun contextKeepsRecentCompleteTurnsWithinTheConfiguredBudget() {
        val prompt = Message(Role.USER, "Newest")
        val history = List(30) { index ->
            listOf(Message(Role.USER, "Question $index"), Message(Role.ASSISTANT, "Answer $index"))
        }.flatten()
        val context = chatContext(history, prompt)
        assertTrue(context.size <= MAX_CONTEXT_MESSAGES)
        assertEquals(Role.USER, context.first().role)
        assertSame(prompt, context.last())
        assertEquals(history.last(), context[context.lastIndex - 1])

        val oversized = listOf(Message(Role.USER, "q"), Message(Role.ASSISTANT, "a".repeat(MAX_CONTEXT_LENGTH)))
        assertEquals(listOf(prompt), chatContext(history + oversized, prompt))
    }
}
