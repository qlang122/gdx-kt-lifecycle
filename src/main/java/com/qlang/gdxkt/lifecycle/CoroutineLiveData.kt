package com.qlang.gdxkt.lifecycle

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.experimental.ExperimentalTypeInference

internal const val DEFAULT_TIMEOUT = 5000L

/**
 * Interface that allows controlling a [LiveData] from a coroutine block.
 *
 * @see liveData
 */
interface LiveDataScope<T> {
    /**
     * Set's the [LiveData]'s value to the given [value]. If you've called [emitSource] previously,
     * calling [emit] will remove that source.
     *
     * Note that this function suspends until the value is set on the [LiveData].
     *
     * @param value The new value for the [LiveData]
     *
     * @see emitSource
     */
    suspend fun emit(value: T)

    /**
     * Add the given [LiveData] as a source, similar to [MediatorLiveData.addSource]. Calling this
     * method will remove any source that was yielded before via [emitSource].
     *
     * @param source The [LiveData] instance whose values will be dispatched from the current
     * [LiveData].
     *
     * @see emit
     * @see MediatorLiveData.addSource
     * @see MediatorLiveData.removeSource
     */
    suspend fun emitSource(source: LiveData<T>): DisposableHandle

    /**
     * References the current value of the [LiveData].
     *
     * If the block never `emit`ed a value, [latestValue] will be `null`. You can use this
     * value to check what was then latest value `emit`ed by your `block` before it got cancelled.
     *
     * Note that if the block called [emitSource], then `latestValue` will be last value
     * dispatched by the `source` [LiveData].
     */
    val latestValue: T?
}

internal class LiveDataScopeImpl<T>(
        internal var target: CoroutineLiveData<T>,
        context: CoroutineContext
) : LiveDataScope<T> {

    override val latestValue: T?
        get() = target.getValue()

    // use `liveData` provided context + main dispatcher to communicate with the target
    // LiveData. This gives us main thread safety as well as cancellation cooperation
    private val coroutineContext = context + Dispatchers.Main.immediate

    override suspend fun emitSource(source: LiveData<T>): DisposableHandle =
            withContext(coroutineContext) {
                return@withContext target.emitSource(source)
            }

    override suspend fun emit(value: T) = withContext(coroutineContext) {
        target.clearSource()
        target.setValue(value)
    }
}

internal suspend fun <T> MediatorLiveData<T>.addDisposableSource(
        source: LiveData<T>
): EmittedSource = withContext(Dispatchers.Main.immediate) {
    addSource(source, Observer { setValue(it) })
    EmittedSource(source, this@addDisposableSource)
}

internal class EmittedSource(
        private val source: LiveData<*>,
        private val mediator: MediatorLiveData<*>
) : DisposableHandle {
    // @MainThread
    private var disposed = false

    /**
     * Unlike [dispose] which cannot be sync because it not a coroutine (and we do not want to
     * lock), this version is a suspend function and does not return until source is removed.
     */
    suspend fun disposeNow() = withContext(Dispatchers.Main.immediate) {
        removeSource()
    }

    override fun dispose() {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            removeSource()
        }
    }

    private fun removeSource() {
        if (!disposed) {
            mediator.removeSource(source)
            disposed = true
        }
    }
}

internal typealias Block<T> = suspend LiveDataScope<T>.() -> Unit

/**
 * Handles running a block at most once to completion.
 */
internal class BlockRunner<T>(
        private val liveData: CoroutineLiveData<T>,
        private val block: Block<T>,
        private val timeoutInMs: Long,
        private val scope: CoroutineScope,
        private val onDone: () -> Unit
) {
    // currently running block job.
    private var runningJob: Job? = null

    // cancelation job created in cancel.
    private var cancellationJob: Job? = null

    fun maybeRun() {
        cancellationJob?.cancel()
        cancellationJob = null
        if (runningJob != null) {
            return
        }
        runningJob = scope.launch {
            val liveDataScope = LiveDataScopeImpl(liveData, coroutineContext)
            block(liveDataScope)
            onDone()
        }
    }

    fun cancel() {
        if (cancellationJob != null) {
            error("Cancel call cannot happen without a maybeRun")
        }
        cancellationJob = scope.launch(Dispatchers.Main.immediate) {
            delay(timeoutInMs)
            if (!liveData.hasActiveObservers()) {
                // one last check on active observers to avoid any race condition between starting
                // a running coroutine and cancelation
                runningJob?.cancel()
                runningJob = null
            }
        }
    }
}

internal class CoroutineLiveData<T>(
        context: CoroutineContext = EmptyCoroutineContext,
        timeoutInMs: Long = DEFAULT_TIMEOUT,
        block: Block<T>
) : MediatorLiveData<T>() {
    private var blockRunner: BlockRunner<T>?
    private var emittedSource: EmittedSource? = null

    init {
        // use an intermediate supervisor job so that if we cancel individual block runs due to losing
        // observers, it won't cancel the given context as we only cancel w/ the intention of possibly
        // relaunching using the same parent context.
        val supervisorJob = SupervisorJob(context[Job])

        // The scope for this LiveData where we launch every block Job.
        // We default to Main dispatcher but developer can override it.
        // The supervisor job is added last to isolate block runs.
        val scope = CoroutineScope(Dispatchers.Main.immediate + context + supervisorJob)
        blockRunner = BlockRunner(this, block, timeoutInMs, scope) {
            blockRunner = null
        }
    }

    internal suspend fun emitSource(source: LiveData<T>): DisposableHandle {
        clearSource()
        val newSource = addDisposableSource(source)
        emittedSource = newSource
        return newSource
    }

    internal suspend fun clearSource() {
        emittedSource?.disposeNow()
        emittedSource = null
    }

    override fun onActive() {
        super.onActive()
        blockRunner?.maybeRun()
    }

    override fun onInactive() {
        super.onInactive()
        blockRunner?.cancel()
    }
}

@UseExperimental(ExperimentalTypeInference::class)
fun <T> liveData(
        context: CoroutineContext = EmptyCoroutineContext,
        timeoutInMs: Long = DEFAULT_TIMEOUT,
        @BuilderInference block: suspend LiveDataScope<T>.() -> Unit
): LiveData<T> = CoroutineLiveData(context, timeoutInMs, block)