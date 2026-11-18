// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.feature.home.domain.api.models.InitFile
import com.srctool.graft.feature.home.domain.api.models.InitOutcome
import com.srctool.graft.feature.home.domain.api.models.InitStep
import com.srctool.graft.feature.home.domain.implementation.usecases.BundledInitTemplates
import com.srctool.graft.feature.home.presentation.clone.CloneEffect
import com.srctool.graft.feature.home.presentation.clone.CloneEvent
import com.srctool.graft.feature.home.presentation.clone.CloneIssue
import com.srctool.graft.feature.home.presentation.clone.CloneViewModel
import com.srctool.graft.feature.home.presentation.clone.FolderStatus
import com.srctool.graft.feature.home.presentation.clone.folderNameFor
import com.srctool.graft.feature.home.presentation.clone.suggestedFolder
import com.srctool.graft.feature.home.presentation.initrepo.FirstCommitIssue
import com.srctool.graft.feature.home.presentation.initrepo.InitEffect
import com.srctool.graft.feature.home.presentation.initrepo.InitEvent
import com.srctool.graft.feature.home.presentation.initrepo.InitIssue
import com.srctool.graft.feature.home.presentation.initrepo.InitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Clone and Init dialogs' view models, against real temp folders and fake use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class FormViewModelTest {
    @TempDir
    lateinit var temp: Path

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clone = FakeClone()
    private val init = FakeInit()
    private val identity = FakeIdentity()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.cloneVm(folder: Path? = null): CloneViewModel {
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                initializer { CloneViewModel(folder, temp, clone, dispatcher) }
            },
        )[CloneViewModel::class]
        vm.onEvent(CloneEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.initVm(folder: Path? = null): InitViewModel {
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory {
                    initializer {
                        InitViewModel(folder, temp, init, identity, BundledInitTemplates, year = 2026, io = dispatcher)
                    }
                },
            )[InitViewModel::class]
        vm.onEvent(InitEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.send(vm: CloneViewModel, vararg events: CloneEvent) {
        events.forEach(vm::onEvent)
        advanceUntilIdle()
    }

    private fun TestScope.send(vm: InitViewModel, vararg events: InitEvent) {
        events.forEach(vm::onEvent)
        advanceUntilIdle()
    }

    @Test
    fun `a clone's folder is named from its URL, whatever form the URL takes`() {
        assertEquals("app", folderNameFor("https://github.com/acme/app.git"))
        assertEquals("app", folderNameFor("git@github.com:acme/app.git"))
        assertEquals("app", folderNameFor("  https://example.com/acme/app/  "))
        assertEquals("app", folderNameFor("/srv/git/app.git"))
        assertEquals("repo", folderNameFor("C:\\repos\\repo.git"))
        assertEquals("app", folderNameFor("host:app"))
        assertEquals("", folderNameFor(""))
        assertEquals("ab", folderNameFor("https://x/a<b>"))
        assertEquals(temp.resolve("app").toString(), suggestedFolder(temp, "https://x/app.git"))
    }

    @Test
    fun `the folder follows the URL until edited by hand, and a picked parent keeps the name`() = runTest(dispatcher) {
        val vm = cloneVm()
        send(vm, CloneEvent.UrlChanged("https://github.com/acme/app.git"))
        assertEquals(temp.resolve("app").toString(), vm.state.value.folder)
        send(vm, CloneEvent.UrlChanged("https://github.com/acme/api.git"))
        assertEquals(temp.resolve("api").toString(), vm.state.value.folder)
        send(vm, CloneEvent.ParentPicked(temp.resolve("clones")))
        assertEquals(temp.resolve("clones/api").toString(), vm.state.value.folder)
        send(vm, CloneEvent.UrlChanged("https://github.com/acme/web.git"))
        assertEquals(temp.resolve("clones/api").toString(), vm.state.value.folder, "edited by hand: it stays")
    }

    @Test
    fun `Clone is off, with a reason, for a bad URL, a folder that's taken, or a bad depth`() = runTest(dispatcher) {
        val vm = cloneVm()
        assertEquals(CloneIssue.NoUrl, vm.state.value.issue)
        send(vm, CloneEvent.UrlChanged("--upload-pack=x"))
        assertEquals(CloneIssue.UrlLooksLikeOption, vm.state.value.issue)

        val taken = Files.createDirectories(temp.resolve("taken"))
        Files.writeString(taken.resolve("file.txt"), "x")
        send(vm, CloneEvent.UrlChanged("https://x/app.git"), CloneEvent.FolderChanged(taken.toString()))
        assertEquals(CloneIssue.FolderNotEmpty, vm.state.value.issue)
        assertEquals(FolderStatus.NotEmpty, vm.state.value.folderStatus)

        send(
            vm,
            CloneEvent.FolderChanged(temp.resolve("fresh").toString()),
            CloneEvent.ShallowToggled,
            CloneEvent.DepthChanged("many"),
        )
        assertEquals(CloneIssue.BadDepth, vm.state.value.issue)
        send(vm, CloneEvent.DepthChanged("20"))
        assertNull(vm.state.value.issue)
        assertTrue(vm.state.value.canClone)
        // An empty folder the user made is fine to clone into.
        send(vm, CloneEvent.FolderChanged(Files.createDirectories(temp.resolve("empty")).toString()))
        assertNull(vm.state.value.issue)
    }

    @Test
    fun `options reach the request, inapplicable ones are dropped, success opens the repository`() =
        runTest(dispatcher) {
            val root = temp.resolve("app")
            val location = RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))
            clone.steps =
                listOf(CloneProgress.Phase("Receiving objects", 62, 1_203, 1_940), CloneProgress.Finished(location))
            val vm = cloneVm()
            val effects = mutableListOf<CloneEffect>()
            val collector = launch { vm.effects.collect { effects += it } }
            send(
                vm,
                CloneEvent.UrlChanged(" https://x/app.git "),
                CloneEvent.ShallowToggled,
                CloneEvent.DepthChanged("5"),
                CloneEvent.SingleBranchToggled,
                CloneEvent.BranchChanged("release"),
                CloneEvent.SubmodulesToggled,
                CloneEvent.PartialToggled,
                CloneEvent.Submit,
            )
            val request = clone.requests.single()
            assertEquals("https://x/app.git", request.url)
            assertEquals(temp.resolve("app"), request.directory)
            assertEquals(5, request.depth)
            assertEquals("release", request.branch)
            assertTrue(request.singleBranch && request.recurseSubmodules && request.partial)
            assertEquals(listOf<CloneEffect>(CloneEffect.Cloned(root)), effects)

            // Without shallow and single-branch, their values aren't sent.
            send(vm, CloneEvent.ShallowToggled, CloneEvent.SingleBranchToggled, CloneEvent.Submit)
            assertNull(clone.requests.last().depth)
            assertNull(clone.requests.last().branch)
            collector.cancel()
        }

    @Test
    fun `cancelling stops the clone, and a failure shows its code and can be shown in full`() = runTest(dispatcher) {
        clone.steps = listOf(CloneProgress.Phase("Receiving objects", 10, 1, 10))
        clone.hang = true
        val vm = cloneVm()
        send(vm, CloneEvent.UrlChanged("https://x/app.git"), CloneEvent.Submit)
        assertTrue(vm.state.value.running)
        assertEquals("Receiving objects", vm.state.value.phase)
        send(vm, CloneEvent.Cancel)
        assertTrue(clone.cancelled, "cancelling the job cancels the flow, which stops git")
        assertEquals(false, vm.state.value.running)

        clone.hang = false
        clone.failure = FakeFailure("could not read from remote")
        send(vm, CloneEvent.Submit)
        assertIs<FakeFailure>(vm.state.value.error)
        assertEquals(false, vm.state.value.running)
        send(vm, CloneEvent.OutputShown(true))
        assertTrue(vm.state.value.outputShown)
    }

    @Test
    fun `cloning into a folder Home opened uses it as it is`() = runTest(dispatcher) {
        val folder = Files.createDirectories(temp.resolve("opened-folder"))
        val vm = cloneVm(folder)
        assertEquals(folder.toString(), vm.state.value.folder)
        send(vm, CloneEvent.UrlChanged("https://x/other.git"))
        assertEquals(folder.toString(), vm.state.value.folder)
        assertEquals(FolderStatus.EmptyFolder, vm.state.value.folderStatus)
    }

    @Test
    fun `init starts from git's identity, and offers a first commit only with files and one`() = runTest(dispatcher) {
        val vm = initVm()
        assertEquals("Dwiki Riyadi", vm.state.value.holder)
        assertEquals(FirstCommitIssue.NothingToCommit, vm.state.value.firstCommitIssue)
        send(vm, InitEvent.ReadmeToggled, InitEvent.FirstCommitToggled)
        assertTrue(vm.state.value.firstCommit)
        send(vm, InitEvent.ReadmeToggled)
        assertEquals(false, vm.state.value.firstCommit, "nothing left to commit: the box turns off")
        store.clear()

        identity.email = null
        val anon = initVm()
        send(anon, InitEvent.ReadmeToggled)
        assertEquals(FirstCommitIssue.NoIdentity, anon.state.value.firstCommitIssue)
    }

    @Test
    fun `init refuses a repository folder, a taken file, a bad branch, or a holderless license`() =
        runTest(dispatcher) {
            val repo = Files.createDirectories(temp.resolve("repo/.git")).parent
            val vm = initVm()
            send(vm, InitEvent.FolderChanged(repo.toString()))
            assertEquals(InitIssue.AlreadyRepository, vm.state.value.issue)

            val plain = Files.createDirectories(temp.resolve("plain"))
            Files.writeString(plain.resolve("README.md"), "mine")
            send(vm, InitEvent.FolderChanged(plain.toString()), InitEvent.ReadmeToggled)
            assertEquals(InitIssue.FileExists, vm.state.value.issue)
            assertEquals(listOf(InitFile.README), vm.state.value.taken)
            send(vm, InitEvent.ReadmeToggled, InitEvent.BranchChanged("my branch"))
            assertEquals(InitIssue.BadBranch, vm.state.value.issue)
            send(vm, InitEvent.BranchChanged("trunk"), InitEvent.LicenseChosen("MIT"), InitEvent.HolderChanged(""))
            assertEquals(InitIssue.NoHolder, vm.state.value.issue)
            send(vm, InitEvent.LicenseChosen("Apache-2.0"))
            assertNull(vm.state.value.issue, "Apache-2.0 has no copyright line to fill in")
        }

    @Test
    fun `create sends the options, a partial result keeps the repository and can be opened`() = runTest(dispatcher) {
        val vm = initVm()
        val effects = mutableListOf<InitEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        val folder = temp.resolve("made")
        send(
            vm,
            InitEvent.FolderChanged(folder.toString()),
            InitEvent.GitignoreChosen("Kotlin"),
            InitEvent.LicenseChosen("MIT"),
            InitEvent.ReadmeToggled,
            InitEvent.FirstCommitToggled,
            InitEvent.Submit,
        )
        val options = init.options.single()
        assertEquals(folder, options.folder)
        assertEquals("Kotlin", options.gitignore)
        assertEquals("MIT", options.license)
        assertEquals("Dwiki Riyadi", options.holder)
        assertTrue(options.readme && options.firstCommit)
        assertEquals(2026, options.year)
        assertEquals(listOf<InitEffect>(InitEffect.Created(folder)), effects)

        val location = RepositoryLocation(folder, folder.resolve(".git"), folder.resolve(".git"))
        init.outcome = { InitOutcome.Partial(location, InitStep.Writing(InitFile.LICENSE), FakeFailure("disk full")) }
        send(vm, InitEvent.Submit)
        val partial = assertNotNullPartial(vm)
        assertEquals(InitStep.Writing(InitFile.LICENSE), partial.step)
        assertEquals(false, vm.state.value.canCreate, "created already: Create is off")
        send(vm, InitEvent.OpenPartial)
        assertEquals(InitEffect.Created(folder), effects.last())

        init.outcome = { InitOutcome.Failed(FakeFailure("bad branch")) }
        send(vm, InitEvent.FolderChanged(temp.resolve("other").toString()))
        send(vm, InitEvent.Submit)
        assertIs<FakeFailure>(vm.state.value.error)
        collector.cancel()
    }

    private fun assertNotNullPartial(vm: InitViewModel): InitOutcome.Partial = vm.state.value.partial!!
}
