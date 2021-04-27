package com.qlang.gdxkt.lifecycle

abstract class Lifecycle {
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
    abstract fun getCurrentState(): State

    enum class Event {
        ON_SHOW, ON_HIDE, ON_RESUME, ON_PAUSE, ON_DISPOSE
    }

    enum class State {
        INITIALIZED, CREATED, RESUMED, DISPOSED;

        fun isAtLeast(state: State): Boolean {
            return compareTo(state) >= 0
        }
    }
}