package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.GitHistoryHeadGoneException
import com.tamimarafat.ferngeist.core.model.GitHistoryUnsupportedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ChatIntent.LoadGitLog], [ChatIntent.LoadGitCommit], and commit-mode [ChatIntent.LoadGitDiff]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelLoadGitLogTest : ChatViewModelTestBase() {
    @Test
    fun `later pages pin page one's head and append, and stop once there is no more`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { head, skip -> if (head == null) page("h", true, "a") else page("h", false, "b") }
                }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            val history = viewModel.state.value.gitHistory
            assertEquals(listOf("a", "b"), history.commits.map { it.sha })
            assertFalse(history.hasMore)
            assertTrue(history.isLoaded)
            assertEquals(listOf(null to 0, "h" to 1), facade.gitLogRequests)
        }

    @Test
    fun `a vanished head on a later page reloads page one instead of failing`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        if (head == null) page("h2", true, "x") else throw GitHistoryHeadGoneException(Exception())
                    }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            val history = viewModel.state.value.gitHistory
            assertEquals(listOf(null to 0, "h2" to 1, null to 0), facade.gitLogRequests)
            assertNull(history.moreError)
            assertFalse(history.isLoadingMore)
        }

    @Test
    fun `a gateway without history marks the tab unsupported`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitLog = { _, _ -> throw GitHistoryUnsupportedException() } }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            val history = viewModel.state.value.gitHistory
            assertTrue(history.unsupported)
            assertFalse(history.isLoading)
            assertNull(history.error)
        }

    @Test
    fun `a reload drops the late reply of the page it replaced, and a repeat reload is not sent`() =
        runTest {
            val pageTwo = CompletableDeferred<Unit>()
            var reloads = 0
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        if (head == null) {
                            page("h", true, "p1-${reloads++}")
                        } else {
                            pageTwo.await()
                            page("h", false, "stale")
                        }
                    }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            pageTwo.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf("p1-1"),
                viewModel.state.value.gitHistory.commits
                    .map { it.sha },
            )
            assertEquals(listOf(null to 0, "h" to 1, null to 0), facade.gitLogRequests)
        }

    @Test
    fun `asking for page one while it is in flight sends nothing more`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { _, _ ->
                        gate.await()
                        page("h", false, "a")
                    }
                }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, facade.gitLogRequests.size)
            assertEquals(
                listOf("a"),
                viewModel.state.value.gitHistory.commits
                    .map { it.sha },
            )
        }

    @Test
    fun `a failed later page keeps the list and offers a retry`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitLog = { head, _ -> if (head == null) page("h", true, "a") else error("offline") }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            val history = viewModel.state.value.gitHistory
            assertEquals(listOf("a"), history.commits.map { it.sha })
            assertEquals("offline", history.moreError)
            assertNull(history.error)
        }

    @Test
    fun `a commit loads its files, and a file of it diffs against that commit`() =
        runTest {
            val renamed = ChatChangedFile(path = "b.kt", status = "R", oldPath = "a.kt")
            val (viewModel, facade) = gatewayViewModel { gitCommitFiles = { listOf(renamed) } }

            viewModel.dispatch(ChatIntent.LoadGitCommit("abc"))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitDiff("b.kt", commit = "abc", oldPath = "a.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("abc", state.gitHistory.commitSha)
            assertEquals(listOf(renamed), state.gitHistory.commitFiles)
            assertEquals("abc", state.gitFileDiffCommit)
            assertEquals(listOf("abc" to "a.kt"), facade.gitDiffCommitRequests)
        }

    private fun page(
        head: String,
        hasMore: Boolean,
        sha: String,
    ) = ChatGitLogPage(
        head = head,
        commits =
            listOf(
                ChatGitCommit(sha, emptyList(), sha, authorName = "A", authorEmail = "", authorTimeMillis = 0),
            ),
        hasMore = hasMore,
        remoteUrl = null,
    )

    private suspend fun gatewayViewModel(
        configure: FakeChatSessionFacade.() -> Unit,
    ): Pair<ChatViewModel, FakeChatSessionFacade> {
        val facadeFactory = FakeChatSessionFacadeFactory()
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        val facade = facadeFactory.lastFacade.value!!
        facade.emitGatewayWorkspaceConnection(GatewayWorkspaceConnection("rt-1", "http", "10.0.0.2:5788", "token"))
        facade.configure()
        return viewModel to facade
    }
}
