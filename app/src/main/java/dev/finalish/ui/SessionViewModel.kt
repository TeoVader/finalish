package dev.finalish.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.AppContainer
import dev.finalish.core.auth.AuthState
import dev.finalish.core.model.Account
import dev.finalish.core.model.User
import dev.finalish.core.network.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionState(
    val me: User? = null,
    val accounts: List<Account> = emptyList(),
    val accountId: String? = null,
    val error: String? = null,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == accountId }
}

/** Activity-scoped session: current user, accounts and selected account. */
class SessionViewModel(app: Application, val container: AppContainer) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SessionState(accountId = container.settings.settings.value.accountId))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Files shared into the app, waiting for a destination folder. */
    private val _incomingUploads = MutableStateFlow<List<Uri>>(emptyList())
    val incomingUploads: StateFlow<List<Uri>> = _incomingUploads.asStateFlow()

    /** Frame.io web link to open once signed in (project id to asset id). */
    private val _deepLink = MutableStateFlow<String?>(null)
    val deepLink: StateFlow<String?> = _deepLink.asStateFlow()

    /** A Home tab to switch to on arrival (e.g. Transfers, from a folder's upload strip). */
    private val _homeTab = MutableStateFlow<Int?>(null)
    val homeTab: StateFlow<Int?> = _homeTab.asStateFlow()
    fun showHomeTab(index: Int) { _homeTab.value = index }
    fun consumeHomeTab() { _homeTab.value = null }

    init {
        viewModelScope.launch {
            container.auth.state.collectLatest { s ->
                if (s is AuthState.SignedIn) load() else {
                    // A load still running would fill the account back in after sign-out.
                    loadJob?.cancel()
                    _state.value = SessionState()
                }
            }
        }
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() {
        _state.update { it.copy(error = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching {
                val me = container.repository.me()
                _state.update { it.copy(me = me) }
                val accounts = container.repository.accounts()
                val saved = container.settings.settings.value.accountId
                val selected = accounts.firstOrNull { it.id == saved }?.id
                    ?: accounts.maxByOrNull { if ("owner" in it.roles || "admin" in it.roles) 1 else 0 }?.id
                _state.update { it.copy(me = me, accounts = accounts, accountId = selected, error = null) }
                if (selected != saved) container.settings.update { it.copy(accountId = selected) }
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) return@launch
                // userMessage() consumes the error body, so read it once and log the result.
                val message = err.userMessage(getApplication<Application>())
                dev.finalish.core.util.AppLog.w("Session load failed: $message", err)
                _state.update { it.copy(error = message) }
            }
        }
    }

    fun selectAccount(id: String) {
        _state.update { it.copy(accountId = id) }
        container.settings.update { it.copy(accountId = id) }
    }

    fun signOut() {
        container.auth.signOut()
        container.transferManager.signOut()
        // Disk work: off the main thread, and on the app scope so it finishes after this screen goes.
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { container.clearCaches() }
        // Reports, the log ring and the last crash belong to this user too.
        container.feedback.clearAll()
        dev.finalish.core.feedback.Breadcrumbs.clear()
        dev.finalish.core.util.AppLog.clear()
        container.settings.update { it.copy(accountId = null) }
    }

    /** The app files were shared from ("Gallery"), when Android tells us. */
    var incomingSource: String? = null
        private set

    fun offerUploads(shared: List<Uri>, source: String? = null) {
        // Another app could "share" our own private files (file:// paths or our FileProvider):
        // only accept content it can actually give us.
        val ownFiles = "${getApplication<Application>().packageName}.files"
        val uris = shared.filterNot { it.scheme == "file" || it.authority == ownFiles }
        if (uris.isEmpty()) return
        incomingSource = source
        _incomingUploads.value = uris
    }

    fun consumeUploads() {
        _incomingUploads.value = emptyList()
    }

    fun openLink(url: String) {
        _deepLink.value = url
    }

    fun consumeLink() {
        _deepLink.value = null
    }

    companion object {
        val Factory = viewModelFactory { initializer { SessionViewModel(application, container) } }
    }
}
