package com.qlang.gdxkt.lifecycle

object Transformations {
    fun <X, Y> map(source: LiveData<X>, block: (X?) -> Y): LiveData<Y> {
        val result: MediatorLiveData<Y> = MediatorLiveData()
        result.addSource(source, Observer { result.setValue(block(it)) })
        return result
    }

    fun <X, Y> switchMap(source: LiveData<X>, block: (X?) -> LiveData<Y>): LiveData<Y> {
        val result: MediatorLiveData<Y> = MediatorLiveData()
        result.addSource(source, object : Observer<X> {
            var mSource: LiveData<Y>? = null
            override fun onChanged(x: X?) {
                val newLiveData: LiveData<Y> = block(x)
                if (mSource == newLiveData) {
                    return
                }
                if (mSource != null) {
                    result.removeSource(mSource!!)
                }
                mSource = newLiveData
                if (mSource != null) {
                    result.addSource(mSource!!, Observer { result.setValue(it) })
                }
            }
        })
        return result
    }
}