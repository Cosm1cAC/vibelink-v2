package com.github.kr328.clash.design.model

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.github.kr328.clash.core.model.Provider


class ProviderState(
    val provider: Provider,
    updatedAt: Long,
    updating: Boolean,
) : BaseObservable() {
    var updatedAt: Long = updatedAt
        @Bindable get
        set(value) {
            field = value

            notifyChange()
        }

    var updating: Boolean = updating
        @Bindable get
        set(value) {
            field = value

            notifyChange()
        }
}