package com.geochanger.app

import kotlinx.coroutines.flow.MutableStateFlow

object MockState {
    val isRunning = MutableStateFlow(false)
}
