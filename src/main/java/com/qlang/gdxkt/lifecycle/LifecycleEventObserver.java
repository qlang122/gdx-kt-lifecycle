package com.qlang.gdxkt.lifecycle;

public interface LifecycleEventObserver extends LifecycleObserver {
    /**
     * Called when a state transition event happens.
     *
     * @param source The source of the event
     * @param event  The event
     */
    void onStateChanged(LifecycleOwner source, Lifecycle.Event event);
}
