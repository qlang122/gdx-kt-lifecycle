package com.qlang.gdxkt.lifecycle

open class MediatorLiveData<T> : MutableLiveData<T>() {
    private val mSources: SafeIterableMap<LiveData<*>, Source<*>> = SafeIterableMap()

    /**
     * Starts to listen the given `source` LiveData, `onChanged` observer will be called
     * when `source` value was changed.
     *
     *
     * `onChanged` callback will be called only when this `MediatorLiveData` is active.
     *
     *  If the given LiveData is already added as a source but with a different Observer,
     * [IllegalArgumentException] will be thrown.
     *
     * @param source    the `LiveData` to listen to
     * @param onChanged The observer that will receive the events
     * @param <S>       The type of data hold by `source` LiveData </S>
     */
    fun <S> addSource(source: LiveData<S>, onChanged: Observer<in S>) {
        val e: Source<S> = Source(source, onChanged)
        val existing: Source<*>? = mSources.putIfAbsent(source, e)
        require(!(existing != null && existing.mObserver !== onChanged)) { "This source was already added with the different observer" }
        if (existing != null) {
            return
        }
        if (hasActiveObservers()) {
            e.plug()
        }
    }

    /**
     * Stops to listen the given `LiveData`.
     *
     * @param toRemote `LiveData` to stop to listen
     * @param <S>      the type of data hold by `source` LiveData </S>
     */
    fun <S> removeSource(toRemote: LiveData<S>) {
        val source: Source<*>? = mSources.remove(toRemote)
        source?.unplug()
    }

    override fun onActive() {
        for ((_, value) in mSources) {
            value.plug()
        }
    }

    override fun onInactive() {
        for ((_, value) in mSources) {
            value.unplug()
        }
    }

    private class Source<V> internal constructor(val mLiveData: LiveData<V>, val mObserver: Observer<in V>) : Observer<V> {
        var mVersion = START_VERSION
        fun plug() {
            mLiveData.observeForever(this)
        }

        fun unplug() {
            mLiveData.removeObserver(this)
        }

        override fun onChanged(v: V?) {
            if (mVersion != mLiveData.getVersion()) {
                mVersion = mLiveData.getVersion()
                mObserver.onChanged(v)
            }
        }
    }
}