package com.qlang.gdxkt.lifecycle

open class MutableLiveData<T> : LiveData<T> {
    constructor() : super()
    constructor(value: T) : super(value)
}