package com.example.mnnllmdemo;

import com.alibaba.mnnllm.android.llm.LlmGate;
import com.example.mnnllmdemo.llm.UiState;
import java.util.List;
import java.util.ArrayList;

import static com.example.mnnllmdemo.GLRender.FPS;
import static com.example.mnnllmdemo.GLRender.camera_height;
import static com.example.mnnllmdemo.GLRender.camera_width;
import static com.example.mnnllmdemo.GLRender.central_depth;
import static com.example.mnnllmdemo.GLRender.executorService;
import static com.example.mnnllmdemo.GLRender.focus_area;
import static com.example.mnnllmdemo.GLRender.run_depth;
import static com.example.mnnllmdemo.GLRender.run_yolo;

import com.example.mnnllmdemo.llm.LlmSimpleClient;
import com.example.mnnllmdemo.llm.DetResultReporter;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import com.benjaminwan.chinesettsmodule.TtsEngine;

public class MainActivity extends AppCompatActivity {
    private GLSurfaceView mGLSurfaceView;
    private List<String> categories = List.of(
            "长椅", "自行车", "汽车", "椅子", "路缘", "门", "下行楼梯", "垃圾桶", "电梯", "自动扶梯",
            "消火栓", "闸机", "扶手", "左转盲道", "行人", "杆柱", "右转盲道", "路边平台", "路障", "减速带",
            "护栏", "石墩", "提示盲道", "直行盲道", "地铁", "红绿灯", "树", "上坡", "上行楼梯", "斑马线", "笔记本电脑"
    );
    private Context mContext;
    private int clickCount = 0;
    private int settingStep = 0;

    private float[] speedOptions = {0.65f, 0.55f, 0.45f};  // 语速：慢，正常，快
    private int[] intervalOptions = {1000, 700, 550};   // 检测频率：1000ms，700ms，500ms

    @SuppressWarnings("unchecked")

    private int currentSpeedIndex = 1;      // 当前语速索引，默认为正常
    private int currentIntervalIndex = 1;   // 当前检测间隔索引，默认为700ms
    private int currentVibrationIndex = 1;  // 当前震动类别索引


    private GLRender mGLRender;
    private static CameraManager mCameraManager;
    private static CameraDevice mCameraDevice;
    private static CameraCaptureSession mCaptureSession;
    private static CaptureRequest mPreviewRequest;
    private static CaptureRequest.Builder mPreviewRequestBuilder;
    private static final String mCameraId = "0";
    private static Handler mBackgroundHandler;
    private static HandlerThread mBackgroundThread;
    public static final int REQUEST_CAMERA_PERMISSION = 1;
    public static final String file_name_class = "class.txt";
    public static final List<String> labels = new ArrayList<>();
    private TextView FPS_view;
    private TextView class_view;
    private TextView depth_view;


    public static StringBuilder class_result = new StringBuilder();

    // ====== 偏好：语速 + 检测间隔 ======
    private static final String PREFS = "app_settings";
    private static final String KEY_TTS_ALPHA = "tts_speed_alpha";
    private static final float DEFAULT_TTS_ALPHA = 0.70f; // 越小越快
    private static final float MIN_TTS_ALPHA = 0.40f;
    private static final float MAX_TTS_ALPHA = 1.20f;

    private static final String KEY_DETECT_INTERVAL_MS = "detect_interval_ms";
    private static final int DEFAULT_DETECT_MS = 600;  // 与当前代码默认一致
    private static final int MIN_DETECT_MS = 100;
    private static final int MAX_DETECT_MS = 1000;

    private static final String INIT_TTS_TEXT = "请横屏使用，按住屏幕再松开可进入设置模式，设置结束自动恢复导盲。";
    private static final long MIN_LOCK_MS = 2000L; // 初始最短锁定时长，避免太短
    private static final float BASE_ALPHA = 0.70f; // 默认语速系数

    private RelativeLayout mainScreenLayout;
    private RelativeLayout splashScreenLayout;
    private ImageView splashLogoTitle;
    private ImageView splashCenterIcon;
    private ImageView splashSlogan;
    private ImageView scrollingImageView;
    private ObjectAnimator scrollAnimator;
    private FrameLayout scrollingViewport;

    private AnimatorSet animatorSet;

    // 从偏好里读取保存过的语速系数（越小越快），没有就用默认
    private float readSavedAlpha() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        return sp.getFloat(KEY_TTS_ALPHA, DEFAULT_TTS_ALPHA);
    }

    /**
     * 估算中文播报时长（毫秒）：按字数/语速粗略估算，再加冗余
     */
    private long estimateTtsDurationMs(String text, float alpha) {
        double cps = 11.0 * (BASE_ALPHA / Math.max(0.35, Math.min(1.20, alpha))); // 粗略字/秒
        long core = (long) Math.ceil(text.length() / Math.max(6.0, cps) * 1000.0);
        return Math.max(MIN_LOCK_MS, core + 3500L);
    }

    static {
        System.loadLibrary("mnnllmdemo");
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ① 初始化阶段先上闸门：禁止空旷/LLM插队 & 忽略交互
        LlmGate.setBusy(true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final WindowInsetsController insetsController = getWindow().getInsetsController();
        if (insetsController != null) {
            insetsController.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        mContext = this;
        //尝试先加载上层幕布
        splashScreenLayout = findViewById(R.id.splash_screen_layout);
        splashLogoTitle = findViewById(R.id.splash_logo_title);
        splashCenterIcon = findViewById(R.id.splash_center_icon);
        splashSlogan = findViewById(R.id.splash_slogan);
        mainScreenLayout = findViewById(R.id.main_screen_layout);
        scrollingImageView = findViewById(R.id.scrolling_text_image);
        scrollingViewport = findViewById(R.id.scrolling_text_viewport);
        startLoadingAnimation();

        AssetManager mgr = getAssets();
        Read_Assets(file_name_class, mgr);
        FPS_view = findViewById(R.id.fps);
        class_view = findViewById(R.id.class_list);
        depth_view = findViewById(R.id.depth);

        // 初始化模型
        if (run_yolo) {
            if (!Load_Models_A(mgr, false)) {
                FPS_view.setText("YOLO failed.");
            }
        }
        if (run_depth) {
            if (!Load_Models_B(mgr, false)) {
                depth_view.setText("Depth failed.");
            }
        }

        setWindowFlag();
        initView();

        // 初始化 LLM 客户端和报告器
        LlmSimpleClient.INSTANCE.init(getApplicationContext());
        DetResultReporter.INSTANCE.init(getApplicationContext());
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int interval = sp.getInt(KEY_DETECT_INTERVAL_MS, DEFAULT_DETECT_MS);
        GLRender.YOLO_COOLDOWN_MS = interval;
        GLRender.DEPTH_COOLDOWN_MS = interval;
        com.example.mnnllmdemo.llm.DetResultReporter.INSTANCE.setBatchSizeFromInterval(interval);


        // ===== TTS 初始化 =====
        TtsEngine.init(getApplicationContext());


        // ② 初始化期间先装“空”的点击/长按监听，防止误触
        mainScreenLayout.setOnClickListener(v -> { /* ignore during init */ });
        mainScreenLayout.setOnLongClickListener(v -> true); // 初始化锁内，长按也忽略

        // ③ 播报“初始化完成…”，估算播报时长后再解锁并安装真正监听
        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            try {
                //动画稍慢于初始化
                TtsEngine.speak(INIT_TTS_TEXT, true);
            } catch (Throwable ignore) {
            } finally {
                float alpha = readSavedAlpha();
                long waitMs = estimateTtsDurationMs(INIT_TTS_TEXT, alpha);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {

                    // —— 设置模式下用“横屏左中右(=x/宽)”来选择 —— //
                    mainScreenLayout.setOnTouchListener((view, event) -> {
                        // 仅在“设置模式”(闸门=关)处理触摸
                        if (!LlmGate.isBusy()) return false;

                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            // 在手指抬起时延迟执行设置，防止误触
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                int w = view.getWidth();
                                float x = event.getX();
                                // 0=左 1=中 2=右（按横向位置分区）
                                int zone = (x < w / 3f) ? 0 : ((x < 2f * w / 3f) ? 1 : 2);

                                if (settingStep == 0) {
                                    // 第一次点击：只播报指引，不做设置
                                    TtsEngine.speak("进入设置模式，现在设置语速，请在横屏下点击：左侧慢速，中间正常，右侧快速。", true);
                                    settingStep = 1; // 进入语速设置
                                } else if (settingStep == 1) {
                                    // 语速：左=慢 中=正常 右=快
                                    setSpeedOption(zone);
                                    if (zone == 0) {
                                        TtsEngine.speak("语速已设为慢，现在设置检测频率，请在横屏下点击：左侧一千毫秒，中间七百毫秒，右侧五百毫秒。", true);
                                    } else if (zone == 1) {
                                        TtsEngine.speak("语速已设为正常，现在设置检测频率，请在横屏下点击：左侧一千毫秒，中间七百毫秒，右侧五百毫秒。", true);
                                    } else {
                                        TtsEngine.speak("语速已设为快速，现在设置检测频率，请在横屏下点击：左侧一千毫秒，中间七百毫秒，右侧五百毫秒。", true);
                                    }
                                    settingStep = 2; // 进入检测频率设置
                                } else if (settingStep == 2) {
                                    // 检测频率：左=1000ms 中=700ms 右=500ms
                                    setIntervalOption(zone);
                                    if (zone == 0) {
                                        TtsEngine.speak("检测频率已设为一千毫秒，现在设置震动类别，请在横屏下点击：左侧无震动，中间汽车路障自行车，右侧再加杆柱减速带上下行楼梯。", true);
                                    } else if (zone == 1) {
                                        TtsEngine.speak("检测频率已设为七百毫秒，现在设置震动类别，请在横屏下点击：左侧无震动，中间汽车路障自行车，右侧再加杆柱减速带上下行楼梯。", true);
                                    } else {
                                        TtsEngine.speak("检测频率已设为五百毫秒，现在设置震动类别，请在横屏下点击：左侧无震动，中间汽车路障自行车，右侧再加杆柱减速带上下行楼梯。", true);
                                    }
                                    settingStep = 3; // 进入震动设置
                                } else if (settingStep == 3) {
                                    // 震动类别（三档）：左=无震动 中=三类 右=再加四类
                                    int vibIndex = Math.max(0, Math.min(2, zone)); // 仅 0..2
                                    setVibrationOption(vibIndex);
                                    if (vibIndex == 0) {
                                        TtsEngine.speak("震动已关闭，设置已完成。点击任意位置退出设置模式。", true);
                                    } else if (vibIndex == 1) {
                                        TtsEngine.speak("震动对象为汽车、路障和自行车，设置已完成。点击任意位置退出设置模式。", true);
                                    } else {
                                        TtsEngine.speak("震动对象增加为杆柱、减速带、以及上下行楼梯，设置已完成。点击任意位置退出设置模式。", true);
                                    }
                                    // 进入“等待退出”的点击阶段
                                    settingStep = 4;
                                } else if (settingStep == 4) {
                                    // 用户再次点击：退出设置模式，恢复识别与播报
                                    LlmGate.setBusy(false);  // 打开闸门
                                    settingStep = 0;
                                    clickCount = 0;
                                    TtsEngine.speak("设置已退出，恢复正常识别与播报。", true);
                                }
                            }, 100); // 延迟 100 毫秒执行
                        }

                        return true;
                    });

                    // —— 长按：非设置模式→进入；设置模式→（不处理退出，退出靠“再次点击”） —— //
                    mainScreenLayout.setOnLongClickListener(v2 -> {
                        if (!LlmGate.isBusy()) {
                            // 进入设置模式（闸门关）
                            LlmGate.setBusy(true);
                            clickCount = 0;
                            settingStep = 0; // 第一次点击只播报指引，不做设置
                            try {
                                TtsEngine.speak("进入设置模式。请横屏使用。", true);
                            } catch (Throwable ignore3) {
                            }
                        } else {
                            // 设置模式中长按不做任何事（退出由再次点击触发）
                        }
                        return true;
                    });

                    // 解锁初始化：进入正常识别/播报
                    LlmGate.setBusy(false);

                    // ✅ 解锁后再“喂看门狗”，防止刚解锁被误判空旷
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            com.example.mnnllmdemo.llm.DetResultReporter.INSTANCE.onFrame(true);
                        } catch (Throwable ignore2) {
                        }
                    });

                }, waitMs);
                setupWarningObserver();
            }
        }, 300);
    }


    private void startLoadingAnimation() {

        // 定义动画时长和延迟
        long duration = 1500; // 每个元素淡入的时长
        long staggerDelay = 800; // 每个元素之间出现的延迟

        // 中心图标
        splashCenterIcon.animate()
                .alpha(1.0f) // 目标透明度：完全不透明
                .setDuration(duration)
                .setStartDelay(0) // 立即开始
                .start();

        // 顶部的标题 Logo
        splashLogoTitle.animate()
                .alpha(1.0f)
                .setDuration(duration)
                .setStartDelay(staggerDelay) //
                .start();

        // 标语
        splashSlogan.animate()
                .alpha(1.0f)
                .setDuration(duration)
                .setStartDelay(staggerDelay * 2)
                .withEndAction(() -> {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        transferToMainAnimation();
                    }, 500); // 500 毫秒 = 0.5 秒
                })
                .start();

    }

    private void transferToMainAnimation() {
        splashScreenLayout.animate()
                .alpha(0f)
                .setDuration(1500) // 动画时长0.5秒
                .withEndAction(() -> {
                    splashScreenLayout.setVisibility(View.GONE); // 动画结束后彻底隐藏
                })
                .start();

        //主内容界面淡入
        mainScreenLayout.setAlpha(0f);
        mainScreenLayout.setVisibility(View.VISIBLE);
        mainScreenLayout.animate()
                .alpha(1f)
                .setDuration(500)
                .start();
        setupAndStartScrollingAnimation();
    }

    private void setupAndStartScrollingAnimation() {
        // 我们使用 view.post() 方法，它能保证在View被正确布局后才执行内部的代码。
        Drawable drawable = scrollingImageView.getDrawable();
        if (drawable == null) return;

        int imageWidth = drawable.getIntrinsicWidth() / 7;
        int imageHeight = drawable.getIntrinsicHeight() / 2;
        if (imageWidth <= 0) return;

        // 尺寸设置
        ViewGroup.LayoutParams viewportParams = scrollingViewport.getLayoutParams();
        viewportParams.width = imageWidth;
        viewportParams.height = imageHeight;
        scrollingViewport.setLayoutParams(viewportParams);

        ViewGroup.LayoutParams imageParams = scrollingImageView.getLayoutParams();
        imageParams.width = imageWidth;
        imageParams.height = imageHeight;
        scrollingImageView.setLayoutParams(imageParams);

        ObjectAnimator scrollOutAnimator = ObjectAnimator.ofFloat(
                scrollingImageView, "translationX", 0f, -imageWidth);
        scrollOutAnimator.setDuration(4000L);
        scrollOutAnimator.setInterpolator(new LinearInterpolator());
        scrollOutAnimator.setStartDelay(2000L);

        ObjectAnimator scrollInAnimator = ObjectAnimator.ofFloat(
                scrollingImageView, "translationX", imageWidth, 0f); // 从右侧(imageWidth)回到中间(0)
        scrollInAnimator.setDuration(4000L);
        scrollInAnimator.setInterpolator(new LinearInterpolator());

        ValueAnimator endPauseAnimator = ValueAnimator.ofInt(0, 1);
        endPauseAnimator.setDuration(2000L);

        scrollOutAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                // 当文字滚出左边后，立即将它“瞬移”到右边，为“登场”做准备
                scrollingImageView.setTranslationX(imageWidth);
            }
        });

        animatorSet = new AnimatorSet();
        animatorSet.playSequentially(
                scrollOutAnimator,
                scrollInAnimator,
                endPauseAnimator
        );

        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                animatorSet.start();
            }
        });

        animatorSet.start();
    }

    private void setupWarningObserver() {
        // 假设你在XML中已经添加了它，id 为 main_warning_icon
        final ImageView warningIconView = findViewById(R.id.main_warning_icon);
        if (warningIconView == null) return; // 安全检查

        UiState.getCurrentWarning().observe(this, warningState -> {
            // 每当 LiveData 的值发生变化，这里的代码就会被自动执行！

            if (warningState == null) {
                // 如果状态为空，说明没有障碍物，隐藏警告图标
                Log.e("icon", "working but no barrier, i will show car.");
                warningIconView.setVisibility(View.GONE);
                /*
                warningIconView.setVisibility(View.VISIBLE);
                warningIconView.setImageResource(R.drawable.icon_car);
                */
            } else {
                // 如果有障碍物，根据名称匹配并显示对应的图片
                int drawableId = matchObstacleToDrawable(warningState.obstacleName);

                if (drawableId != 0) { // 0 表示没有匹配到
                    warningIconView.setImageResource(drawableId);
                    warningIconView.setVisibility(View.VISIBLE);
                } else {
                    // 如果没有匹配到具体的图标，也隐藏它
                    warningIconView.setVisibility(View.GONE);
                }
            }
        });
    }

    private int matchObstacleToDrawable(String obstacleName) {
        if (obstacleName == null || obstacleName.isEmpty()) {
            return 0;
        }

        String normalizedName = obstacleName.toLowerCase().trim();

        switch (normalizedName) {
            case "汽车": case "car":
                return R.drawable.icon_car;
            // ... (添加你所有的 case)
            case "左转盲道":
                return R.drawable.icon_left;
            case "右转盲道":
                return R.drawable.icon_right;
        }

        if (normalizedName.contains("楼梯") || normalizedName.contains("stair")) {
            return R.drawable.icon_stairs;
        }

        return 0;
    }


    private void setSpeedOption(int index) {
        currentSpeedIndex = Math.max(0, Math.min(2, index)); // 只允许 0..2
        float speed = speedOptions[currentSpeedIndex];
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putFloat(KEY_TTS_ALPHA, speed).apply();
        try {
            TtsEngine.setSpeedAlpha(speed);
        } catch (Throwable ignore) {
        }
    }

    private void setIntervalOption(int index) {
        currentIntervalIndex = Math.max(0, Math.min(2, index)); // 0..2 -> 1000/700/500
        int interval = intervalOptions[currentIntervalIndex];
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putInt(KEY_DETECT_INTERVAL_MS, interval).apply();
        GLRender.YOLO_COOLDOWN_MS = interval;
        GLRender.DEPTH_COOLDOWN_MS = interval;

        // ★ 新增：联动批大小（1000→3, 700→4, 500→6）
        com.example.mnnllmdemo.llm.DetResultReporter.INSTANCE.setBatchSizeFromInterval(interval);

        // （可选）立刻冲洗一次，避免刚换档出现等待过长
        // com.example.mnnllmdemo.llm.DetResultReporter.INSTANCE.flushNow(getApplicationContext());
    }

    private void setVibrationOption(int index) {
        // 仅三档：0 无震动；1 汽车/路障/自行车；2 再加 杆柱/减速带/上下行楼梯
        currentVibrationIndex = Math.max(0, Math.min(2, index));
        List<String> selectedCategories;
        if (currentVibrationIndex == 0) {
            selectedCategories = new ArrayList<>();
        } else if (currentVibrationIndex == 1) {
            selectedCategories = java.util.List.of("汽车", "路障", "自行车");
        } else {
            selectedCategories = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "汽车", "路障", "自行车",
                    "杆柱", "减速带", "上行楼梯", "下行楼梯"
            ));
        }
        DetResultReporter.vibrateTargets.clear();
        DetResultReporter.vibrateTargets.addAll(selectedCategories);
    }


    private void setWindowFlag() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void initView() {
        mGLSurfaceView = findViewById(R.id.glSurfaceView);
        mGLSurfaceView.setEGLContextClientVersion(3);
        mGLRender = new GLRender(mContext);
        mGLSurfaceView.setRenderer(mGLRender);
    }


    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mGLSurfaceView != null) mGLSurfaceView.onResume();
        openCamera();
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        if (mGLSurfaceView != null) mGLSurfaceView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        try {
            TtsEngine.release();
        } catch (Throwable ignore) {
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            FPS_view.setText("Camera permission failed");
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    public void openCamera() {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs();
        try {
            mCameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCameraOutputs() {
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @SuppressLint({"ResourceType", "DefaultLocale", "SetTextI18n"})
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            try {
                SurfaceTexture surfaceTexture = mGLRender.getSurfaceTexture();
                if (surfaceTexture == null) return;

                surfaceTexture.setDefaultBufferSize(camera_width, camera_height);
                surfaceTexture.setOnFrameAvailableListener(surfaceTexture1 -> {
                    mGLSurfaceView.requestRender();
                    runOnUiThread(() -> {
                        FPS_view.setText("FPS: " + String.format(java.util.Locale.US, "%.1f", FPS));
                        depth_view.setText("Central\nDepth: " + String.format(java.util.Locale.US, "%.2f", central_depth) + " m");

                        String snapshot = class_result.toString();
                        class_view.setText(snapshot);


                    });
                });
                Surface surface = new Surface(surfaceTexture);
                mCameraDevice = cameraDevice;
                mPreviewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewRequestBuilder.addTarget(surface);
                mPreviewRequest = mPreviewRequestBuilder.build();
                List<OutputConfiguration> outputConfigurations = new ArrayList<>();
                outputConfigurations.add(new OutputConfiguration(surface));
                SessionConfiguration sessionConfiguration = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigurations,
                        executorService,
                        sessionsStateCallback
                );
                cameraDevice.createCaptureSession(sessionConfiguration);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            mCameraDevice = null;
            finish();
        }
    };

    CameraCaptureSession.StateCallback sessionsStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (null == mCameraDevice) return;
            mCaptureSession = session;
            try {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                mPreviewRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                mPreviewRequest = mPreviewRequestBuilder.build();
                mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        }
    };

    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
        }
    };

    private void closeCamera() {
        if (null != mCaptureSession) {
            try {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            mCaptureSession = null;
        }
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void Read_Assets(String file_name, AssetManager mgr) {
        if (file_name.equals(file_name_class)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(mgr.open(file_name_class)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    labels.add(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static native void Process_Init(int textureId);

    public static native int[] Process_Texture();

    private native boolean Load_Models_A(AssetManager assetManager, boolean USE_XNNPACK);

    private native boolean Load_Models_B(AssetManager assetManager, boolean USE_XNNPACK);

    public static native float[] Run_YOLO(byte[] pixel_values);

    public static native float[] Run_Depth(byte[] pixel_values);
}
