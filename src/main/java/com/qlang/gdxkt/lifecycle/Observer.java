package com.qlang.gdxkt.lifecycle;

public interface Observer<T> {
    void onChanged(T t);
}