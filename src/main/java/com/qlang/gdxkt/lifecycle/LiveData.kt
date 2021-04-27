package com.qlang.gdxkt.lifecycle

abstract class LiveData<T> {
    val LOCK = Any()

    var mActiveCount = 0

    @Volatile
    private var mData: Any? = null
    private val mObservers: SafeIterableMap<Observer<in T>, ObserverWrapper> = SafeIterableMap()

    @Volatile
    var mPendingData = NOT_SET
    private var mVersion = 0

    private var mDispatchingValue = false
    private var mDispatchInvalidated = false

    companion object {
        val START_VERSION = -1
        val NOT_SET = Any()

    }

    constructor() {
        mData = NOT_SET
        mVersion = START_VERSION
    }

    constructor(value: T) {
        mData = value
        mVersion = START_VERSION + 1
    }

    open fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DISPOSED) {
            return
        }
        val wrapper = LifecycleBoundObserver(owner, observer)
        val existing = mObservers.putIfAbsent(observer, wrapper)
        require(!(existing != null && !existing.isAttachedTo(owner))) {
            ("Cannot add the same observer with different lifecycles")
        }
        if (existing != null) {
            return
        }
        owner.getLifecycle().addObserver(wrapper)
    }

    open fun observeForever(observer: Observer<in T>) {
        val wrapper = AlwaysActiveObserver(observer)
        val existing = mObservers.putIfAbsent(observer, wrapper)
        require(existing !is LifecycleBoundObserver) {
            ("Cannot add the same observer with different lifecycles")
        }
        if (existing != null) {
            return
        }
        wrapper.activeStateChanged(true)
    }

    open fun removeObserver(observer: Observer<in T>) {
        val removed = mObservers.remove(observer) ?: return
        removed.detachObserver()
        removed.activeStateChanged(false)
    }

    open fun removeObservers(owner: LifecycleOwner) {
        for ((key, value) in mObservers) {
            if (value.isAttachedTo(owner)) {
                removeObserver(key)
            }
        }
    }

    var value: T?
        set(value) {
            mVersion++
            mData = value
            dispatchingValue(null)
        }
        get() {
            val data = mData
            return if (data != NOT_SET) {
                data as T?
            } else null
        }

    fun getVersion(): Int {
        return mVersion
    }

    private fun considerNotify(observer: ObserverWrapper) {
        if (!observer.mActive) {
            return
        }
        // Check latest state b4 dispatch. Maybe it changed state but we didn't get the event yet.
        //
        // we still first check observer.active to keep it as the entrance for events. So even if
        // the observer moved to an active state, if we've not received that event, we better not
        // notify for a more predictable notification order.
        if (!observer.shouldBeActive()) {
            observer.activeStateChanged(false)
            return
        }
        if (observer.mLastVersion >= mVersion) {
            return
        }
        observer.mLastVersion = mVersion
        observer.mObserver.onChanged(mData as? T?)
    }

    open fun dispatchingValue(initiator: ObserverWrapper?) {
        var initiator = initiator
        if (mDispatchingValue) {
            mDispatchInvalidated = true
            return
        }
        mDispatchingValue = true
        do {
            mDispatchInvalidated = false
            if (initiator != null) {
                considerNotify(initiator)
                initiator = null
            } else {
                val iterator: Iterator<Map.Entry<Observer<in T>, ObserverWrapper>> = mObservers.iteratorWithAdditions()
                while (iterator.hasNext()) {
                    considerNotify(iterator.next().value)
                    if (mDispatchInvalidated) {
                        break
                    }
                }
            }
        } while (mDispatchInvalidated)
        mDispatchingValue = false
    }

    /**
     * Called when the number of active observers change to 1 from 0.
     *
     *
     * This callback can be used to know that this LiveData is being used thus should be kept
     * up to date.
     */
    protected open fun onActive() {}

    /**
     * Called when the number of active observers change from 1 to 0.
     *
     *
     * This does not mean that there are no observers left, there may still be observers but their
     * lifecycle states aren't [Lifecycle.State.STARTED] or [Lifecycle.State.RESUMED]
     * (like an Activity in the back stack).
     *
     *
     * You can check if there are observers via [.hasObservers].
     */
    protected open fun onInactive() {}

    /**
     * Returns true if this LiveData has observers.
     *
     * @return true if this LiveData has observers
     */
    open fun hasObservers(): Boolean {
        return mObservers.size() > 0
    }

    /**
     * Returns true if this LiveData has active observers.
     *
     * @return true if this LiveData has active observers
     */
    open fun hasActiveObservers(): Boolean {
        return mActiveCount > 0
    }

    internal inner class LifecycleBoundObserver(val mOwner: LifecycleOwner, observer: Observer<in T>) : ObserverWrapper(observer), LifecycleEventObserver {
        override fun shouldBeActive(): Boolean {
            return mOwner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.INITIALIZED)
        }

        override fun onStateChanged(source: LifecycleOwner?, event: Lifecycle.Event?) {
            if (mOwner.getLifecycle().getCurrentState() == Lifecycle.State.DISPOSED) {
                removeObserver(mObserver)
                return
            }
            activeStateChanged(shouldBeActive())
        }

        override fun isAttachedTo(owner: LifecycleOwner): Boolean {
            return mOwner === owner
        }

        override fun detachObserver() {
            mOwner.getLifecycle().removeObserver(this)
        }

    }

    abstract inner class ObserverWrapper internal constructor(observer: Observer<in T>) {
        val mObserver: Observer<in T> = observer
        var mActive = false
        var mLastVersion = START_VERSION

        abstract fun shouldBeActive(): Boolean

        open fun isAttachedTo(owner: LifecycleOwner): Boolean {
            return false
        }

        open fun detachObserver() {}
        fun activeStateChanged(newActive: Boolean) {
            if (newActive == mActive) {
                return
            }
            // immediately set active state, so we'd never dispatch anything to inactive
            // owner
            mActive = newActive
            val wasInactive = this@LiveData.mActiveCount == 0
            this@LiveData.mActiveCount += if (mActive) 1 else -1
            if (wasInactive && mActive) {
                onActive()
            }
            if (this@LiveData.mActiveCount == 0 && !mActive) {
                onInactive()
            }
            if (mActive) {
                dispatchingValue(this)
            }
        }

    }

    inner class AlwaysActiveObserver internal constructor(observer: Observer<in T>) : ObserverWrapper(observer) {
        override fun shouldBeActive(): Boolean {
            return true
        }
    }
}