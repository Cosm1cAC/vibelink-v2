package com.github.kr328.clash.design.ui

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable


class ObservableCurrentTime : BaseObservable() {
    var value: Long = System.currentTimeMillis()
        @Bindable get
        private set(value) {
            field = value

            notifyChange()
        }

    fun update() {
        value = System.currentTimeMillis()
    }
}