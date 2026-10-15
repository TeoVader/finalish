package dev.finalish.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.R
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.data.Remote
import dev.finalish.core.data.WorkspaceProjects
import dev.finalish.core.model.Project
import dev.finalish.core.network.userMessage
import dev.finalish.ui.application
import dev.finalish.ui.container
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class ProjectsUiState(
    val remote: Remote<List<WorkspaceProjects>> = Remote.loading(),
    /** True only for refreshes the user asked for (pull-to-refresh). */
    val userRefreshing: Boolean = false,
    /** A project is being created. */
    val busy: Boolean = false,
)

sealed interface ProjectsEvent {
    data class Load(val accountId: String, val force: Boolean = false) : ProjectsEvent
    data class Refresh(val user: Boolean, val force: Boolean = false) : ProjectsEvent
    /** [onCreated] opens the new project. */
    data class Create(
        val target: WorkspaceTarget,
        val name: String,
        val restricted: Boolean,
        val onCreated: (Project) -> Unit,
    ) : ProjectsEvent
    data class Rename(val project: Project, val name: String) : ProjectsEvent
    data class SetRestricted(val project: Project, val restricted: Boolean) : ProjectsEvent
    data class SetArchived(val project: Project, val archived: Boolean) : ProjectsEvent
    data class Delete(val project: Project) : ProjectsEvent
    data class RenameWorkspace(val id: String, val name: String) : ProjectsEvent
    data class DeleteWorkspace(val id: String) : ProjectsEvent
}

class ProjectsViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()
    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()
    private var job: Job? = null
    private var account: String? = null

    fun onEvent(e: ProjectsEvent) {
        when (e) {
            is ProjectsEvent.Load -> load(e.accountId, e.force)
            is ProjectsEvent.Refresh -> refresh(e.user, e.force)
            is ProjectsEvent.Create -> createProject(e.target, e.name, e.restricted, e.onCreated)
            is ProjectsEvent.Rename -> rename(e.project, e.name)
            is ProjectsEvent.SetRestricted -> setRestricted(e.project, e.restricted)
            is ProjectsEvent.SetArchived -> setArchived(e.project, e.archived)
            is ProjectsEvent.Delete -> delete(e.project)
            is ProjectsEvent.RenameWorkspace -> renameWorkspace(e.id, e.name)
            is ProjectsEvent.DeleteWorkspace -> deleteWorkspace(e.id)
        }
    }

    private fun setRemote(block: (Remote<List<WorkspaceProjects>>) -> Remote<List<WorkspaceProjects>>) =
        _state.update { it.copy(remote = block(it.remote)) }

    private fun refresh(user: Boolean, force: Boolean = false) {
        val a = account ?: return
        if (user) _state.update { it.copy(userRefreshing = true) } else if (job?.isActive == true && !force) return
        job?.cancel()
        // A background poll over projects already on screen applies only its final result: the
        // cached emission before it would flip the state to loading and back for nothing.
        val quiet = !user && _state.value.remote.data != null
        job = viewModelScope.launch {
            repo.workspacesWithProjects(a).collect { r ->
                if (quiet && r.loading) return@collect
                // Never blank the grid while revalidating: keep what's on screen until fresh data lands.
                setRemote { old -> if (r.data == null && r.error == null) r.copy(data = old.data) else r }
                if (!r.loading) _state.update { it.copy(userRefreshing = false) }
            }
        }
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun updateProjects(block: (Project) -> Project?) =
        setRemote { r -> r.copy(data = r.data?.map { g -> g.copy(projects = g.projects.mapNotNull(block)) }) }

    /** Runs a change, reports it, then reloads so the grid shows the server's view. */
    private fun op(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            _messages.report({ success }, { it.userMessage(getApplication<Application>()) }) { block() }
            refresh(user = false, force = true)
        }
    }

    private fun createProject(target: WorkspaceTarget, name: String, restricted: Boolean, onCreated: (Project) -> Unit) {
        val a = account ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            _messages.report({ p -> str(R.string.home_project_created, p.name) }, { it.userMessage(getApplication<Application>()) }) {
                val wsId = when (target) {
                    is WorkspaceTarget.Existing -> target.id
                    is WorkspaceTarget.New -> repo.createWorkspace(a, target.name).id
                }
                repo.createProject(a, wsId, name, restricted)
            }.onSuccess(onCreated)
            _state.update { it.copy(busy = false) }
            refresh(user = false, force = true)
        }
    }

    private fun rename(p: Project, name: String) {
        updateProjects { if (it.id == p.id) it.copy(name = name) else it }
        op(str(R.string.home_renamed)) { repo.updateProject(account!!, p.id, mapOf("name" to JsonPrimitive(name))) }
    }

    private fun setRestricted(p: Project, restricted: Boolean) {
        updateProjects { if (it.id == p.id) it.copy(restricted = restricted) else it }
        op(if (restricted) str(R.string.home_project_restricted) else str(R.string.home_project_open)) {
            repo.updateProject(account!!, p.id, mapOf("restricted" to JsonPrimitive(restricted)))
        }
    }

    private fun setArchived(p: Project, archived: Boolean) {
        val status = if (archived) "inactive" else "active"
        updateProjects { if (it.id == p.id) it.copy(status = status) else it }
        op(if (archived) str(R.string.home_project_archived) else str(R.string.home_project_restored)) {
            repo.updateProject(account!!, p.id, mapOf("status" to JsonPrimitive(status)))
        }
    }

    private fun renameWorkspace(id: String, name: String) {
        setRemote { r -> r.copy(data = r.data?.map { g -> if (g.workspace.id == id) g.copy(workspace = g.workspace.copy(name = name)) else g }) }
        op(str(R.string.home_workspace_renamed)) { repo.renameWorkspace(account!!, id, name) }
    }

    private fun deleteWorkspace(id: String) {
        setRemote { r -> r.copy(data = r.data?.filterNot { it.workspace.id == id }) }
        op(str(R.string.home_workspace_deleted)) { repo.deleteWorkspace(account!!, id) }
    }

    private fun delete(p: Project) {
        updateProjects { if (it.id == p.id) null else it }
        op(str(R.string.home_project_deleted)) { repo.deleteProject(account!!, p.id) }
    }

    private fun load(accountId: String, force: Boolean = false) {
        if (accountId == account && !force && job?.isActive == true) return
        if (accountId == account && !force && _state.value.remote.data != null) return
        if (accountId != account) setRemote { Remote.loading() }
        account = accountId
        job?.cancel()
        job = viewModelScope.launch { repo.workspacesWithProjects(accountId).collect { r -> setRemote { r } } }
    }

    companion object {
        val Factory = viewModelFactory { initializer { ProjectsViewModel(application, container.repository) } }
    }
}
