package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitMessagePolicyTest {
    @Test
    fun `accepts conventional subjects with scopes and breaking markers`() {
        listOf(
            "feat: add repository picker",
            "fix(editor): preserve the selected line",
            "refactor!: remove the legacy service",
            "feat(api)!: return paginated reviews",
            "build-deps: update Kotlin",
        ).forEach { subject -> assertNull(CommitMessagePolicy.validate(subject), subject) }
    }

    @Test
    fun `requires a nonblank first line even when the body contains a valid subject`() {
        listOf("", "   ", "\nfeat: add history", "\r\nfix: recover selection").forEach { message ->
            assertNotNull(CommitMessagePolicy.validate(message), message)
        }
    }

    @Test
    fun `rejects malformed conventional syntax`() {
        listOf(
            "Fix: update history",
            "fix update history",
            "fix:update history",
            "fix(): update history",
            "fix(editor: update history",
            "fix: ",
        ).forEach { message -> assertNotNull(CommitMessagePolicy.validate(message), message) }
    }

    @Test
    fun `requires actual description text after the type`() {
        listOf("feat:    ", "fix(editor): \t", "chore!: \t  ").forEach { subject ->
            assertNotNull(CommitMessagePolicy.validate(subject), "Blank descriptions must be rejected: '$subject'")
        }
    }

    @Test
    fun `subject length boundary is inclusive and does not count the body`() {
        val subject = "feat: " + "a".repeat(66)
        assertNull(CommitMessagePolicy.validate(subject))
        assertNotNull(CommitMessagePolicy.validate(subject + "a"))
        assertNull(CommitMessagePolicy.validate("$subject\n\n" + "Detailed explanation. ".repeat(100)))
    }

    @Test
    fun `custom subject limit is used in both validation and the correction message`() {
        val subject = "fix: " + "a".repeat(15)
        assertNull(CommitMessagePolicy.validate(subject, 20))
        val error = CommitMessagePolicy.validate(subject + "a", 20)
        assertNotNull(error)
        assertTrue(error.orEmpty().contains("20"))
        assertTrue(error.orEmpty().contains("21"))
    }

    @Test
    fun `CRLF body and breaking change footer do not alter subject validation`() {
        assertNull(CommitMessagePolicy.validate("feat(api)!: replace endpoint\r\n\r\nBREAKING CHANGE: use v2.\r\n"))
    }
}
