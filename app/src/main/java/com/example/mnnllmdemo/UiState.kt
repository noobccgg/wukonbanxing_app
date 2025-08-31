package com.example.mnnllmdemo.llm

import androidx.lifecycle.MutableLiveData

// 封装UI状态
data class WarningState(
    @JvmField val obstacleName: String, // 障碍物名称
    @JvmField val distance: Float       // 距离
)

// “数据中心”，存放全局的UI状态 LiveData
object UiState {
    // 创建一个 MutableLiveData，它可以被后台更新
    // 它将持有一个可空的 WarningState 对象 (在无障碍时为 null)
    @JvmStatic
    public val currentWarning = MutableLiveData<WarningState?>()
}
