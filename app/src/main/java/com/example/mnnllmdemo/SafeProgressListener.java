package com.example.mnnllmdemo;

import androidx.annotation.Nullable;
import com.alibaba.mnnllm.android.llm.GenerateProgressListener;

/** 防崩包装：SDK 偶尔传 null token，这里统一兜底 */
public abstract class SafeProgressListener implements GenerateProgressListener {

    @Override
    public final boolean onProgress(String token) {
        // 允许 token 为 null
        return onProgressNullable(token);
    }

    /** 子类实现可空版本 */
    public abstract boolean onProgressNullable(@Nullable String token);

    @Override
    public void onComplete() {}

    @Override
    public void onError(String message) {}
}
