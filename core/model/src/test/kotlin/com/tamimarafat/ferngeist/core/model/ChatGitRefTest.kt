package com.tamimarafat.ferngeist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGitRefTest {
    @Test
    fun `a ref is qualified by its kind, so a tag and a branch of one name stay apart`() {
        assertEquals("refs/heads/main", ChatGitRef("main", ChatGitRef.Kind.BRANCH).qualified)
        assertEquals("refs/remotes/origin/main", ChatGitRef("origin/main", ChatGitRef.Kind.REMOTE).qualified)
        assertEquals("refs/tags/main", ChatGitRef("main", ChatGitRef.Kind.TAG).qualified)
    }

    @Test
    fun `a detached head is not a compare target`() {
        assertNull(ChatGitRef("abc1234", ChatGitRef.Kind.HEAD).qualified)
    }
}
