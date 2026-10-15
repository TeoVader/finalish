package dev.finalish.ui.search

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.container
import androidx.lifecycle.createSavedStateHandle
import dev.finalish.core.data.FrameioRepository
import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.annotation.StringRes
import dev.finalish.R
import dev.finalish.core.data.SearchResult
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Project
import dev.finalish.core.network.userMessage
import dev.finalish.core.util.Format
import dev.finalish.ui.browser.AssetThumbnail
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.StatusPill
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.home.projectGradient
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val smart: Boolean = false,
    val loading: Boolean = false,
    val results: List<SearchResult>? = null,
    val error: String? = null,
    val recent: List<String> = emptyList(),
    val filter: SearchFilter = SearchFilter.All,
    val next: String? = null,
    val loadingMore: Boolean = false,
)

enum class SearchFilter(@StringRes val labelRes: Int) {
    All(R.string.search_filter_all), Files(R.string.search_filter_files), Folders(R.string.search_filter_folders), Projects(R.string.search_filter_projects),
}

sealed interface SearchEvent {
    /** The selected account; a query restored after process death runs once it is known. */
    data class SetAccount(val accountId: String) : SearchEvent
    data class SetQuery(val query: String) : SearchEvent
    data object ToggleSmart : SearchEvent
    data class SetFilter(val filter: SearchFilter) : SearchEvent
    /** The query is final (keyboard Search, or a result opened): remember it. */
    data object Submit : SearchEvent
    data object LoadMore : SearchEvent
}

class SearchViewModel(
    app: Application,
    private val saved: androidx.lifecycle.SavedStateHandle,
    private val repo: FrameioRepository,
) : AndroidViewModel(app) {

    // Query, Smart search and filter survive the app being killed in the background.
    private val _ui = MutableStateFlow(
        SearchUi(
            query = saved.get<String>("query").orEmpty(),
            smart = saved.get<Boolean>("smart") ?: false,
            filter = saved.get<String>("filter")?.let { n -> SearchFilter.entries.find { it.name == n } } ?: SearchFilter.All,
        ),
    )
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var account: String = ""

    fun onEvent(e: SearchEvent) {
        when (e) {
            is SearchEvent.SetAccount -> setAccount(e.accountId)
            is SearchEvent.SetQuery -> setQuery(e.query)
            SearchEvent.ToggleSmart -> toggleSmart()
            is SearchEvent.SetFilter -> setFilter(e.filter)
            SearchEvent.Submit -> submit()
            SearchEvent.LoadMore -> loadMore()
        }
    }

    private fun setAccount(value: String) {
        val changed = account != value
        account = value
        if (changed && _ui.value.results == null && _ui.value.query.isNotBlank()) schedule(0)
    }

    init {
        viewModelScope.launch {
            _ui.collect { saved["query"] = it.query; saved["smart"] = it.smart; saved["filter"] = it.filter.name }
        }
    }

    private fun setQuery(q: String) {
        _ui.update { it.copy(query = q) }
        schedule(debounce = 380)
    }

    private fun toggleSmart() {
        _ui.update { it.copy(smart = !it.smart) }
        schedule(0)
    }

    private fun setFilter(f: SearchFilter) {
        _ui.update { it.copy(filter = f) }
        schedule(0)
    }

    private fun page(after: String?) = _ui.value.filter.let { f ->
        val q = _ui.value.query.trim()
        suspend { repo.searchPage(account, q, _ui.value.smart, after, f == SearchFilter.All || f == SearchFilter.Files, f == SearchFilter.All || f == SearchFilter.Folders, f == SearchFilter.All || f == SearchFilter.Projects) }
    }

    private fun loadMore() {
        val ui = _ui.value
        val after = ui.next ?: return
        if (ui.loadingMore) return
        _ui.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            runCatching { page(after)() }
                .onSuccess { (r, next) ->
                    // Results already listed are not repeated (the list keys by id).
                    _ui.update { it.copy(results = (it.results.orEmpty() + r).distinctBy(::resultKey), next = next, loadingMore = false) }
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _ui.update { s -> s.copy(loadingMore = false) }
                }
        }
    }

    /** The next page of the current query; a new query cancels it, so old results never join the new ones. */
    private var moreJob: kotlinx.coroutines.Job? = null

    private fun submit() {
        val q = _ui.value.query.trim()
        if (q.isNotBlank()) _ui.update { it.copy(recent = (listOf(q) + it.recent.filterNot { r -> r == q }).take(8)) }
        schedule(0)
    }

    private fun schedule(debounce: Long) {
        job?.cancel()
        moreJob?.cancel()
        _ui.update { it.copy(next = null, loadingMore = false) }
        val q = _ui.value.query.trim()
        if (q.length < 2 || account.isBlank()) {
            _ui.update { it.copy(results = null, loading = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(debounce)
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { page(null)() }
                .onSuccess { (r, next) -> _ui.update { it.copy(results = r, next = next, loading = false) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _ui.update { it.copy(error = e.userMessage(), loading = false) }
                }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { SearchViewModel(application, createSavedStateHandle(), container.repository) } }
    }
}

@Composable
fun SearchTab(
    accountId: String?,
    onOpenAsset: (Asset) -> Unit,
    onOpenProject: (Project) -> Unit,
    contentPadding: PaddingValues,
    vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
) {
    vm.onEvent(SearchEvent.SetAccount(accountId.orEmpty()))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { dev.finalish.FinalishApp.container(context) }
    val opened = remember(accountId, ui.results == null) { accountId?.let { container.activityWatch.recentlyOpened(it) }.orEmpty() }
    // Project names for "Recently opened" and result subtitles (looked up once per project).
    val projectNames = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val wanted = (opened.map { it.projectId } + ui.results.orEmpty().mapNotNull { (it as? SearchResult.AssetHit)?.asset?.projectId })
        .filter { it.isNotBlank() }.distinct()
    LaunchedEffect(wanted, accountId) {
        val acc = accountId ?: return@LaunchedEffect
        wanted.filterNot { it in projectNames }.take(20).forEach { id ->
            runCatching { container.repository.project(acc, id).name }.onSuccess { projectNames[id] = it }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 14.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Surface2)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Palette.TextTertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (ui.query.isEmpty()) {
                        Text(
                            stringResource(if (ui.smart) R.string.search_hint_smart else R.string.search_hint),
                            color = Palette.TextTertiary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    BasicTextField(
                        value = ui.query,
                        onValueChange = { vm.onEvent(SearchEvent.SetQuery(it)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                        cursorBrush = SolidColor(Palette.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.onEvent(SearchEvent.Submit) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.query.isNotEmpty()) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.search_clear), tint = Palette.TextTertiary, modifier = Modifier.size(20.dp).pressable({ vm.onEvent(SearchEvent.SetQuery("")) }))
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (ui.smart) Palette.AccentContainer else Palette.Surface2)
                    .border(1.dp, if (ui.smart) Palette.Accent else Palette.Hairline, RoundedCornerShape(14.dp))
                    .pressable({ vm.onEvent(SearchEvent.ToggleSmart) }, scaleTo = 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.search_smart), tint = if (ui.smart) Palette.AccentBright else Palette.TextSecondary)
            }
        }
        if (ui.smart) {
            Text(
                stringResource(R.string.search_smart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.AccentBright,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(SearchFilter.entries.toList()) { f -> dev.finalish.ui.home.Chip(stringResource(f.labelRes), ui.filter == f) { vm.onEvent(SearchEvent.SetFilter(f)) } }
        }

        AnimatedContent(
            targetState = when {
                ui.loading && ui.results == null -> 0
                ui.results == null -> 1
                else -> 2
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "search",
        ) { mode ->
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when (mode) {
                    0 -> items(6) { ResultSkeleton() }
                    1 -> {
                        if (ui.error != null) item { EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_failed), ui.error) }
                        else if (opened.isNotEmpty() || ui.recent.isNotEmpty()) {
                            if (opened.isNotEmpty()) {
                                item {
                                    Text(stringResource(R.string.search_recently_opened), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(8.dp))
                                }
                                items(opened, key = { "o" + it.fileId }) { w ->
                                    val kind = dev.finalish.core.model.MediaKind.of(null, w.name)
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .pressable({ onOpenAsset(Asset(id = w.fileId, name = w.name, projectId = w.projectId)) }, scaleTo = 0.98f)
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val thumb by androidx.compose.runtime.produceState<Asset?>(null, w.fileId) {
                                            value = container.repository.withThumbnail(w.accountId, Asset(id = w.fileId, name = w.name, projectId = w.projectId))
                                        }
                                        Box(Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2)) {
                                            thumb?.takeIf { it.thumbnailUrl != null }?.let { AssetThumbnail(it, Modifier.fillMaxSize(), scrubbable = false) }
                                                ?: dev.finalish.ui.components.KindPlaceholder(kind, Modifier.fillMaxSize())
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(w.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                listOfNotNull(projectNames[w.projectId], Format.relative(java.time.Instant.ofEpochMilli(w.touchedAt).toString())).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            if (ui.recent.isNotEmpty()) {
                            item { Text(stringResource(R.string.search_recent_searches), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(8.dp)) }
                            items(ui.recent, key = { "recent:$it" }, contentType = { "recent" }) { r ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable({ vm.onEvent(SearchEvent.SetQuery(r)) }, scaleTo = 0.98f).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.History, null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(r, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            }
                        } else item {
                            EmptyState(Icons.Rounded.Search, stringResource(R.string.search_everything), stringResource(R.string.search_everything_message))
                        }
                    }
                    else -> {
                        val results = ui.results.orEmpty()
                        if (results.isEmpty()) item { EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_no_results), stringResource(if (!ui.smart) R.string.search_try_different_smart else R.string.search_try_different)) }
                        items(results, key = ::resultKey) { r ->
                            when (r) {
                                is SearchResult.AssetHit -> AssetResult(r.asset, projectNames[r.asset.projectId], accountId) { vm.onEvent(SearchEvent.Submit); onOpenAsset(r.asset) }
                                is SearchResult.ProjectHit -> ProjectResult(r.project) { vm.onEvent(SearchEvent.Submit); onOpenProject(r.project) }
                            }
                        }
                        if (ui.next != null) item(key = "more") {
                            androidx.compose.runtime.LaunchedEffect(results.size) { vm.onEvent(SearchEvent.LoadMore) }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetResult(hit: Asset, projectName: String?, accountId: String?, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Search hits have no media links; the thumbnail arrives a moment later.
    val asset by androidx.compose.runtime.produceState(hit, hit.id, hit.updatedAt) {
        if (accountId != null) dev.finalish.FinalishApp.container(context).repository.withThumbnail(accountId, hit)?.let { value = it }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick, scaleTo = 0.98f).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(88.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2)) {
            AssetThumbnail(asset, Modifier.fillMaxSize(), scrubbable = false)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(asset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(asset.statusField?.textValue, compact = true)
                Text(
                    listOfNotNull(
                        projectName ?: when { asset.isFolder -> stringResource(R.string.search_folder); asset.isVersionStack -> stringResource(R.string.search_version_stack); else -> stringResource(kindLabel(asset.kind)) },
                        Format.relative(asset.updatedAt).ifBlank { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProjectResult(project: Project, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick, scaleTo = 0.98f).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(88.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(projectGradient(project.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(Format.initials(project.name), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.search_project), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
    }
}

@Composable
private fun ResultSkeleton() {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(88.dp).aspectRatio(16f / 10f).shimmer())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.7f).height(12.dp).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(10.dp).shimmer())
        }
    }
}

/** The file type shown when a hit has no project name (same words as the enum's names). */
@StringRes
private fun kindLabel(kind: dev.finalish.core.model.MediaKind): Int = when (kind) {
    dev.finalish.core.model.MediaKind.Folder -> R.string.search_folder
    dev.finalish.core.model.MediaKind.Video -> R.string.search_kind_video
    dev.finalish.core.model.MediaKind.Image -> R.string.search_kind_image
    dev.finalish.core.model.MediaKind.Audio -> R.string.search_kind_audio
    dev.finalish.core.model.MediaKind.Pdf -> R.string.search_kind_pdf
    dev.finalish.core.model.MediaKind.Other -> R.string.search_kind_other
}

private fun resultKey(r: SearchResult): String = when (r) {
    is SearchResult.AssetHit -> r.asset.id
    is SearchResult.ProjectHit -> "p" + r.project.id
}
