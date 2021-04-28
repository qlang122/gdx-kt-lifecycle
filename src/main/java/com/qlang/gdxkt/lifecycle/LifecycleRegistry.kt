package com.qlang.gdxkt.lifecycle

import java.lang.ref.WeakReference
import java.util.*

class LifecycleRegistry : Lifecycle {
    private val mObserverMap = FastSafeIterableMap<LifecycleObserver, ObserverWithState>()

    private var mState: State

    private var mLifecycleOwner: WeakReference<LifecycleOwner>? = null

    private var mAddingObserverCounter = 0

    private var mHandlingEvent = false
    private var mNewEventOccurred = false
    private val mParentStates = ArrayList<State>()

    constructor(provider: LifecycleOwner) {
        mLifecycleOwner = WeakReference(provider)
        mState = Lifecycle.State.INITIALIZED
    }

    override fun addObserver(observer: LifecycleObserver) {
        val initialState: State = if (mState == State.DISPOSED) State.DISPOSED else State.INITIALIZED
        val statefulObserver = ObserverWithState(observer, initialState)
        val previous = mObserverMap.putIfAbsent(observer, statefulObserver)
//        System.out.println("------->$initialState $observer $previous ")
        if (previous != null) {
            return
        }
        val lifecycleOwner = mLifecycleOwner?.get()
                ?: return// it is null we should be destroyed. Fallback quickly

        val isReentrance = mAddingObserverCounter != 0 || mHandlingEvent
        var targetState = calculateTargetState(observer)
        mAddingObserverCounter++
        while (statefulObserver.mState < targetState
                && mObserverMap.contains(observer)) {
            pushParentState(statefulObserver.mState)
            statefulObserver.dispatchEvent(lifecycleOwner, upEvent(statefulObserver.mState))
            popParentState()
            // mState / subling may have been changed recalculate
            targetState = calculateTargetState(observer)
        }

        if (!isReentrance) {
            // we do sync only on the top level.
            sync()
        }
        mAddingObserverCounter--
    }

    override fun removeObserver(observer: LifecycleObserver) {
        mObserverMap.remove(observer)
    }

    override fun getCurrentState(): State {
        return mState
    }

    fun getObserverCount(): Int {
        return mObserverMap.size()
    }

    /**
     * Moves the Lifecycle to the given state and dispatches necessary events to the observers.
     *
     * @param state new state
     */
    fun setCurrentState(next: State) {
        moveToState(next)
    }

    /**
     * Sets the current state and notifies the observers.
     * <p>
     * Note that if the {@code currentState} is the same state as the last call to this method,
     * calling this method has no effect.
     *
     * @param event The event that was received
     */
    fun handleLifecycleEvent(event: Event) {
        val next = getStateAfter(event)
        moveToState(next)
    }

    private fun moveToState(next: State) {
        if (mState == next) {
            return
        }
        mState = next
        if (mHandlingEvent || mAddingObserverCounter != 0) {
            mNewEventOccurred = true
            // we will figure out what to do on upper level.
            return
        }
        mHandlingEvent = true
        sync()
        mHandlingEvent = false
    }

    private fun isSynced(): Boolean {
        if (mObserverMap.size() == 0) {
            return true
        }
        val eldestObserverState: State = mObserverMap.eldest().value.mState
        val newestObserverState: State = mObserverMap.newest().value.mState
        return eldestObserverState == newestObserverState && mState == newestObserverState
    }

    private fun calculateTargetState(observer: LifecycleObserver): State {
        val previous = mObserverMap.ceil(observer)
        val siblingState = previous?.value?.mState
        val parentState = if (mParentStates.isNotEmpty()) mParentStates[mParentStates.size - 1] else null
        return min(min(mState, siblingState), parentState)
    }

    private fun popParentState() {
        mParentStates.removeAt(mParentStates.size - 1)
    }

    private fun pushParentState(state: State) {
        mParentStates.add(state)
    }

    private fun forwardPass(lifecycleOwner: LifecycleOwner) {
        val ascendingIterator: Iterator<Map.Entry<LifecycleObserver, ObserverWithState>> = mObserverMap.iteratorWithAdditions()
        while (ascendingIterator.hasNext() && !mNewEventOccurred) {
            val entry = ascendingIterator.next()
            val observer = entry.value
            while (observer.mState < mState && !mNewEventOccurred
                    && mObserverMap.contains(entry.key)) {
                pushParentState(observer.mState)
                observer.dispatchEvent(lifecycleOwner, upEvent(observer.mState))
                popParentState()
            }
        }
    }

    private fun backwardPass(lifecycleOwner: LifecycleOwner) {
        val descendingIterator = mObserverMap.descendingIterator()
        while (descendingIterator.hasNext() && !mNewEventOccurred) {
            val entry = descendingIterator.next()
            val observer = entry.value
            while (observer.mState > mState && !mNewEventOccurred
                    && mObserverMap.contains(entry.key)) {
                val event = downEvent(observer.mState)
                pushParentState(getStateAfter(event))
                observer.dispatchEvent(lifecycleOwner, event)
                popParentState()
            }
        }
    }

    // happens only on the top of stack (never in reentrance),
    // so it doesn't have to take in account parents
    private fun sync() {
        val lifecycleOwner = mLifecycleOwner?.get()
                ?: throw IllegalStateException("LifecycleOwner of this LifecycleRegistry is already"
                        + "garbage collected. It is too late to change lifecycle state.")
        while (!isSynced()) {
            mNewEventOccurred = false
            // no need to check eldest for nullability, because isSynced does it for us.
            if (mState < mObserverMap.eldest().value.mState) {
                backwardPass(lifecycleOwner)
            }
            val newest = mObserverMap.newest()
            if (!mNewEventOccurred && newest != null && mState > newest.value.mState) {
                forwardPass(lifecycleOwner)
            }
        }
        mNewEventOccurred = false
    }

    companion object {
        class ObserverWithState {
            var mState: State
            var observer: LifecycleObserver

            constructor(observer: LifecycleObserver, state: State) {
                this.observer = observer
                this.mState = state
            }

            fun dispatchEvent(owner: LifecycleOwner, event: Event) {
                val newState = getStateAfter(event)
                mState = min(mState, newState)
                (observer as? LifecycleEventObserver)?.onStateChanged(owner, event)
                mState = newState
            }
        }

        private fun getStateAfter(event: Event): State {
            return when (event) {
                Event.ON_SHOW, Event.ON_HIDE -> State.INITIALIZED
                Event.ON_PAUSE -> State.CREATED
                Event.ON_RESUME -> State.RESUMED
                Event.ON_DISPOSE -> State.DISPOSED
            }
        }

        private fun min(state1: State, state2: State?): State {
            return if (state2 != null && state2 < state1) state2 else state1
        }

        private fun downEvent(state: State?): Event {
            when (state) {
                State.INITIALIZED -> throw IllegalArgumentException()
                State.CREATED -> return Event.ON_DISPOSE
                State.RESUMED -> return Event.ON_PAUSE
                State.DISPOSED -> throw IllegalArgumentException()
            }
            throw IllegalArgumentException("Unexpected state value $state")
        }

        private fun upEvent(state: State?): Event {
            when (state) {
                State.INITIALIZED, State.DISPOSED -> return Event.ON_SHOW
                State.CREATED -> return Event.ON_RESUME
                State.RESUMED -> throw IllegalArgumentException()
            }
            throw IllegalArgumentException("Unexpected state value $state")
        }
    }
}