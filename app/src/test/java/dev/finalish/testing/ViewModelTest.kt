package dev.finalish.testing

import android.app.Application
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/** Base for ViewModel tests on Robolectric: viewModelScope runs unconfined, the API is a [FakeFrameio]. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected lateinit var frameio: FakeFrameio
    /** For collectors the test starts; cancelled after each test. */
    protected val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        frameio = FakeFrameio(app.cacheDir.resolve("test-api-cache").apply { deleteRecursively() })
    }

    @After fun tearDownMain() {
        testScope.cancel()
        Dispatchers.resetMain()
    }

    /** Everything [flow] emits from now on (snackbar messages), as a flow of the list so far. */
    protected fun <T> record(flow: Flow<T>): MutableStateFlow<List<T>> {
        val seen = MutableStateFlow<List<T>>(emptyList())
        testScope.launch { flow.collect { v -> seen.update { it + v } } }
        return seen
    }
}
