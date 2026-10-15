package dev.finalish.ui.login

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.container
import dev.finalish.core.auth.AuthManager
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.auth.ImsConfig
import dev.finalish.core.auth.LoopbackReceiver
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.SecondaryButton
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class LoginUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val clientId: String = "",
    val redirectUri: String = "",
    /** The previous session ended by itself (the sign-in note explains why). */
    val sessionExpired: Boolean = false,
    /** The app has a usable client id and redirect. */
    val hasConfig: Boolean = false,
)

sealed interface LoginEvent {
    data class SaveConfig(val clientId: String, val redirectUri: String) : LoginEvent
    /** Opens the Adobe sign-in page; [context] launches the browser tab. */
    data class StartSignIn(val context: Context) : LoginEvent
    data class Complete(val redirect: Uri) : LoginEvent
    data object StartDemo : LoginEvent
}

class LoginViewModel(app: Application, private val auth: AuthManager) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(
        LoginUiState(clientId = auth.config.clientId, redirectUri = auth.config.redirectUri),
    )
    val state: StateFlow<LoginUiState> = combine(_state, auth.sessionExpired) { s, expired ->
        s.copy(sessionExpired = expired, hasConfig = auth.config.isValid)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        _state.value.copy(sessionExpired = auth.sessionExpired.value, hasConfig = auth.config.isValid),
    )
    private var loopbackJob: Job? = null

    fun onEvent(e: LoginEvent) {
        when (e) {
            is LoginEvent.SaveConfig -> saveConfig(e.clientId, e.redirectUri)
            is LoginEvent.StartSignIn -> startSignIn(e.context)
            is LoginEvent.Complete -> complete(e.redirect)
            LoginEvent.StartDemo -> auth.signInDemo()
        }
    }

    private fun saveConfig(clientId: String, redirect: String) {
        auth.config = ImsConfig(clientId, redirect)
        _state.value = _state.value.copy(clientId = clientId, redirectUri = redirect, error = null)
    }

    private fun startSignIn(context: Context) {
        val cfg = auth.config
        if (!cfg.isValid) {
            _state.value = _state.value.copy(error = getApplication<Application>().getString(R.string.login_error_missing_config))
            return
        }
        if (!cfg.schemeSupported) {
            _state.value = _state.value.copy(
                error = getApplication<Application>().getString(R.string.login_error_unsupported_redirect, cfg.redirectUri.substringBefore("://")),
            )
            return
        }
        _state.value = _state.value.copy(error = null)
        if (cfg.isLoopback) {
            val previous = loopbackJob
            loopbackJob = viewModelScope.launch {
                // A second "Sign in": the old listener must let go of the port before this one binds it.
                previous?.cancelAndJoin()
                runCatching { LoopbackReceiver.awaitRedirect(cfg.redirectUri) }
                    .onSuccess { complete(it) }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        _state.value = _state.value.copy(busy = false, error = it.message)
                    }
            }
        }
        val tab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF0A0A0E.toInt())
                    .setNavigationBarColor(0xFF0A0A0E.toInt())
                    .build(),
            )
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setShowTitle(true)
            .build()
        tab.launchUrl(context, auth.buildAuthorizeUri())
    }

    private fun complete(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            auth.completeRedirect(uri)
                .onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
                .onSuccess { _state.value = _state.value.copy(busy = false) }
        }
    }


    companion object {
        val Factory = viewModelFactory { initializer { LoginViewModel(application, container.auth) } }
    }
}

@Composable
fun LoginScreen(pendingRedirect: Uri?, onRedirectConsumed: () -> Unit, vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val expired = state.sessionExpired
    val context = LocalContext.current
    var advanced by rememberSaveable { mutableStateOf(!state.hasConfig) }
    var clientId by rememberSaveable { mutableStateOf(state.clientId) }
    var redirect by rememberSaveable { mutableStateOf(state.redirectUri) }
    var appeared by remember { mutableStateOf(false) }
    var linkHelp by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingRedirect) {
        if (pendingRedirect != null) {
            vm.onEvent(LoginEvent.Complete(pendingRedirect))
            onRedirectConsumed()
        }
    }
    LaunchedEffect(Unit) { appeared = true }

    // First run: three intro slides before sign-in.
    val container = remember { dev.finalish.FinalishApp.container(context) }
    val prefs by container.settings.settings.collectAsStateWithLifecycle()
    if (!prefs.introSeen && pendingRedirect == null) {
        dev.finalish.ui.onboarding.IntroPager(
            onDone = { container.settings.update { it.copy(introSeen = true) } },
            onDemo = { container.settings.update { it.copy(introSeen = true) }; vm.onEvent(LoginEvent.StartDemo) },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        AuroraBackground(Modifier.fillMaxSize())

        Column(
            Modifier
                // A readable column on tablets and in landscape, like the other screens.
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            AnimatedVisibility(appeared, enter = fadeIn(tween(600)) + slideInVertically(tween(700)) { it / 3 }) {
                Column {
                    LogoMark(Modifier.size(64.dp))
                    Spacer(Modifier.height(28.dp))
                    Text(
                        stringResource(R.string.login_title),
                        style = MaterialTheme.typography.displaySmall,
                        color = Palette.Text,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.login_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.TextSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SignInStep(1, stringResource(R.string.login_step1_title), stringResource(R.string.login_step1_detail))
                        SignInStep(2, stringResource(R.string.login_step2_title), stringResource(R.string.login_step2_detail))
                        SignInStep(3, stringResource(R.string.login_step3_title), stringResource(R.string.login_step3_detail))
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            if (expired) {
                SessionExpiredCard()
                Spacer(Modifier.height(14.dp))
            }
            PrimaryButton(
                label = if (state.busy) stringResource(R.string.login_signing_in) else stringResource(R.string.login_continue_with_adobe),
                onClick = { vm.onEvent(LoginEvent.StartSignIn(context)) },
                enabled = !state.busy,
                icon = Icons.Rounded.Lock,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(
                    Modifier.align(Alignment.CenterHorizontally).size(24.dp),
                    color = Palette.AccentBright, strokeWidth = 2.dp,
                )
            }
            state.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    color = Palette.Red,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Red.copy(alpha = 0.1f))
                        .padding(12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            AdobeLinkHelpRow(onClick = { linkHelp = true })

            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                stringResource(R.string.login_explore_demo),
                onClick = { vm.onEvent(LoginEvent.StartDemo) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.login_advanced_sign_in), color = Palette.TextSecondary)
                Icon(
                    Icons.Rounded.KeyboardArrowDown, null, tint = Palette.TextSecondary,
                    modifier = Modifier.rotate(if (advanced) 180f else 0f),
                )
            }

            AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Palette.Surface1.copy(alpha = 0.85f))
                        .border(1.dp, Palette.Hairline, RoundedCornerShape(20.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.login_credential_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.login_credential_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                    )
                    Field(clientId, { clientId = it }, stringResource(R.string.login_client_id))
                    Field(redirect, { redirect = it }, stringResource(R.string.login_redirect_uri), KeyboardType.Uri)
                    SecondaryButton(
                        stringResource(R.string.login_save_credential),
                        onClick = { vm.onEvent(LoginEvent.SaveConfig(clientId.trim(), redirect.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                }
            }

            Spacer(Modifier.height(48.dp))
            Text(
                stringResource(R.string.login_unofficial_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }
    if (linkHelp) {
        AdobeLinkHelpSheet(
            onDismiss = { linkHelp = false },
            onSignInAgain = { linkHelp = false; vm.onEvent(LoginEvent.StartSignIn(context)) },
        )
    }
}

@Composable
private fun SignInStep(n: Int, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface1.copy(alpha = 0.85f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Palette.AccentContainer),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright) }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
    }
}

@Composable
private fun SessionExpiredCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface1)
            .border(1.dp, Palette.Amber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.LockClock, null, tint = Palette.Amber, modifier = Modifier.size(22.dp))
        Column {
            Text(stringResource(R.string.login_session_expired), style = MaterialTheme.typography.titleSmall, color = Palette.Text)
            Text(
                stringResource(R.string.login_session_expired_body),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            focusedContainerColor = Palette.Surface2,
            unfocusedContainerColor = Palette.Surface2,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Slowly drifting colored light behind the hero text. */
@Composable
private fun AuroraBackground(modifier: Modifier) {
    val still = dev.finalish.ui.theme.LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "aurora")
    val drift = t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.blur(90.dp)) {
        // Read while drawing only: the blurred canvas redraws, nothing recomposes.
        val phase = if (still) 0.8f else drift.value
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(Color(0x997C6CFF), w * (0.25f + 0.12f * cos(phase)), h * (0.18f + 0.05f * sin(phase)), w * 0.7f)
        blob(Color(0x664DA3FF), w * (0.85f + 0.1f * sin(phase * 1.3f)), h * (0.08f + 0.06f * cos(phase)), w * 0.55f)
        blob(Color(0x55FF6BC1), w * (0.6f + 0.15f * cos(phase * 0.7f)), h * (0.42f + 0.05f * sin(phase * 1.1f)), w * 0.45f)
    }
    Box(
        modifier.background(
            Brush.verticalGradient(0.0f to Color.Transparent, 0.55f to Palette.Ink.copy(alpha = 0.6f), 1f to Palette.Ink),
        ),
    )
}

@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    // The Final(ish) app icon: the mark on the ink tile.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Ink).border(1.dp, Palette.Surface4, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        dev.finalish.ui.components.BrandMark(maxWidth * 0.78f)
    }
}
