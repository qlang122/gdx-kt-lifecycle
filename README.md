# a gdx lifecycle

lifecycle be use in libgdx game engine, and simpler and faster data management. the framework comes from android lifecycle and livedata, using MVVM architecture like android.

code make use of kotlin.

## USE
```
dependencies {
	implementation 'com.github.qlang122:gdx-kt-lifecycle:1.0.0'
}
```

1.should be make lifecycle call from game screen lifecycle.
```kotlin
open class LifecycleScreenAdapter : ScreenAdapter(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)

    override fun show() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_SHOW)
    }

    override fun hide() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_HIDE)
    }

    override fun resume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun pause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun dispose() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DISPOSE)
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }
}
```

2.in viewModel, make some ```LiveData``` type variable.
```kotlin
val someDataParams = MutableLiveData<String>()
val someData: LiveData<Any> = Transformations.switchMap(recordsParams) {
    liveData {
        //it is the someDataParams value.
        //delay(2000)
        //something code
        emit("value") //call result
    }
}
```

3.in the game screen, observe variable.
```kotlin
if (!viewModel.someData.hasObservers()) {
    viewModel.someData.observe(this, Observer {
        //use result
    })
}
```

4.how to make LiveData run?
such that:
```kotlin
someDataParams.value = "value"
```
