package io.github.fulu2778.geminimi.hooks;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.os.SystemClock;

import io.github.fulu2778.geminimi.Constants;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * Minimal libxposed API102 port for the OS4 power-key -> Gemini Overlay route.
 * Hot-reload friendly: every install() registers new handles; hot reload unhooks old ones.
 */
public final class Api102PowerKeyHook {

    private static final String TAG = Constants.TAG;
    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String MIUI_SHORTCUT_ACTIONS =
            "com.miui.server.input.util.ShortCutActionsUtils";
    private static final String FLOATY_ACTIVITY =
            "com.google.android.googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity";
    private static final String MIUI_POWER_KEY_RULE =
            "com.android.server.input.shortcut.singlekeyrule.PowerKeyRule";

    private static final int SHOW_WITH_ASSIST = 1;
    private static final int SHOW_WITH_SCREENSHOT = 1 << 1;
    private static final int SHOW_SOURCE_PUSH_TO_TALK = 1 << 4;
    private static final int SHOW_POWER_ASSIST_WITH_SCREENSHOT =
            SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_PUSH_TO_TALK;

    private static final List<HookHandle> sHandles = new ArrayList<>();
    private static volatile ClassLoader sSystemServerClassLoader;
    private static android.os.Handler sPowerHandler;
    private static volatile boolean sPowerKeyDown;
    private static volatile boolean sTimerFired;
    private static volatile boolean sVibrated;
    private static Runnable sPowerMenuTimer;
    private static Object sPwmObject;
    private static Runnable sPowerTimer;

    private Api102PowerKeyHook() {}

    public static ClassLoader getLastSystemServerClassLoader() {
        return sSystemServerClassLoader;
    }

    public static synchronized void install(XposedModule module, ClassLoader cl) throws Exception {
        for (HookHandle h : sHandles) {
            try {
                h.unhook();
            } catch (Throwable ignored) {
            }
        }
        sHandles.clear();
        if (cl != null) {
            sSystemServerClassLoader = cl;
        }
        if (sSystemServerClassLoader == null) {
            return;
        }

        // MIUI shortcut path: directly starts XiaoAi for long_press_power_key.
        try {
            Class<?> utils = Class.forName(MIUI_SHORTCUT_ACTIONS, false, sSystemServerClassLoader);
            Method m = utils.getDeclaredMethod("launchVoiceAssistant", String.class, android.os.Bundle.class);
            hookOne(module, m, "ShortCutActionsUtils#launchVoiceAssistant");
        } catch (Throwable t) {
            log(module, "ShortCutActionsUtils hook failed: " + t);
        }

        // AOSP PhoneWindowManager assistant/voice methods.
        try {
            Class<?> pwm = Class.forName(PWM, false, sSystemServerClassLoader);
            for (Method m : pwm.getDeclaredMethods()) {
                if (HookPolicy.shouldHookPowerMethod(m)) {
                    hookOne(module, m, "PhoneWindowManager#" + m.getName());
                }
            }
        } catch (Throwable t) {
            log(module, "PhoneWindowManager scan failed: " + t);
        }

        hookSystemServerSettings(module);
        hookSettingsRewriteBlock(module);
        hookVoiceInteractionSessionReset(module);
        hookMiuiPowerKeyRule(module);
        hookPowerKeyDown(module);
        hookPowerKeyUp(module);

        log(module, "API102 PowerKeyOverlay installed handles=" + sHandles.size());
    }

    private static void hookPowerKeyUp(XposedModule module) {
        try {
            Class<?> pwm = Class.forName(PWM, false, sSystemServerClassLoader);
            for (Method m : pwm.getDeclaredMethods()) {
                if (!"interceptPowerKeyUp".equals(m.getName())) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            sPowerKeyDown = false;
                            sVibrated = false;
                            if (sPowerHandler != null) {
                                if (sPowerTimer != null) {
                                    sPowerHandler.removeCallbacks(sPowerTimer);
                                    sPowerTimer = null;
                                }
                                if (sPowerMenuTimer != null) {
                                    sPowerHandler.removeCallbacks(sPowerMenuTimer);
                                    sPowerMenuTimer = null;
                                }
                            }
                        } catch (Throwable t) {
                            log(module, "hookPowerKeyUp failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
                break;
            }
        } catch (Throwable t) {
            log(module, "hookPowerKeyUp failed: " + t);
        }
    }

    private static void hookPowerKeyDown(XposedModule module) {
        try {
            Class<?> pwm = Class.forName(PWM, false, sSystemServerClassLoader);
            for (Method m : pwm.getDeclaredMethods()) {
                if (!"interceptPowerKeyDown".equals(m.getName())) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            if (chain.getArg(0) instanceof android.view.KeyEvent) {
                                android.view.KeyEvent event = (android.view.KeyEvent) chain.getArg(0);
                                Context ctx = (Context) getField(chain.getThisObject(), "mContext");
                                if (ctx != null) {
                                    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                                            && !sPowerKeyDown) {
                                        sPowerKeyDown = true;
                                        sTimerFired = false;
                                        sVibrated = false;
                                        sPwmObject = chain.getThisObject();
                                        schedulePowerTimer(module, ctx);
                                    } else if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                                        sPowerKeyDown = false;
                                        if (sPowerTimer != null) {
                                            sPowerHandler.removeCallbacks(sPowerTimer);
                                            sPowerTimer = null;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            log(module, "hookPowerKeyDown callback failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
                break;
            }
        } catch (Throwable t) {
            log(module, "hookPowerKeyDown failed: " + t);
        }
    }

    private static void schedulePowerTimer(final XposedModule module, final Context ctx) {
        if (sPowerHandler == null) {
            sPowerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        if (sPowerTimer != null) {
            sPowerHandler.removeCallbacks(sPowerTimer);
        }
        sPowerTimer = new Runnable() {
            @Override
            public void run() {
                sPowerTimer = null;
                if (!sPowerKeyDown) return;
                sTimerFired = true;
                sendAssist(module, ctx);
            }
        };
        sPowerHandler.postDelayed(sPowerTimer, 500L);

        sPowerMenuTimer = new Runnable() {
            @Override
            public void run() {
                sPowerMenuTimer = null;
                if (!sPowerKeyDown) return;
                try {
                    Object pwm = sPwmObject;
                    if (pwm != null) {
                        Method m = findMethod(pwm.getClass(), "showGlobalActions");
                        if (m != null) {
                            m.setAccessible(true);
                            m.invoke(pwm);
                        }
                    }
                } catch (Throwable t) {
                    log(module, "power menu fallback failed: " + t);
                }
            }
        };
        sPowerHandler.postDelayed(sPowerMenuTimer, 3000L);
    }

    private static void hookMiuiPowerKeyRule(XposedModule module) {
        try {
            Class<?> rule = Class.forName(MIUI_POWER_KEY_RULE, false, sSystemServerClassLoader);
            for (Method m : rule.getDeclaredMethods()) {
                if (!"onMiuiLongPress".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 1) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object thisObj = chain.getThisObject();
                            if (thisObj == null) return chain.proceed();
                            Context ctx = (Context) getField(thisObj, "mContext");
                            if (sTimerFired) {
                                // 已经由 500ms 自定义计时器触发过，不再重复弹
                                sTimerFired = false;
                                return null;
                            }
                            if (ctx != null && sendAssist(module, ctx)) {
                                return null;
                            }
                        } catch (Throwable t) {
                            log(module, "PowerKeyRule.onMiuiLongPress failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
            }
        } catch (Throwable t) {
            log(module, "hookMiuiPowerKeyRule failed: " + t);
        }
    }

    private static void hookVoiceInteractionSessionReset(XposedModule module) {
        try {
            Class<?> stub = Class.forName(
                    "com.android.server.voiceinteraction.VoiceInteractionManagerService"
                            + "$VoiceInteractionManagerServiceStub", false, sSystemServerClassLoader);
            for (Method m : stub.getDeclaredMethods()) {
                if (!"showSessionForActiveService".equals(m.getName())) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object serviceStub = chain.getThisObject();
                            if (serviceStub != null) {
                                clearStaleVoiceInteractionSession(serviceStub);
                            }
                        } catch (Throwable t) {
                            log(module, "clearStaleVoiceInteractionSession failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
                break;
            }
        } catch (Throwable t) {
            log(module, "hookVoiceInteractionSessionReset failed: " + t);
        }
    }

    private static void clearStaleVoiceInteractionSession(Object serviceStub) {
        try {
            Object impl = getField(serviceStub, "mImpl");
            if (impl == null) return;
            Object active = getField(impl, "mActiveSession");
            Object service = getField(impl, "mService");
            if (active != null) {
                Object sessionService = getField(active, "mService");
                if (sessionService != null) {
                    // 健康的已有会话：不销毁，直接复用，避免影响滑动/其他助手
                    return;
                }
            }
            if (active == null && service != null) return;

            Method switchImpl = findMethod(serviceStub.getClass(),
                    "switchImplementationIfNeeded", boolean.class);
            if (switchImpl == null) {
                switchImpl = findMethod(serviceStub.getClass(),
                        "switchImplementationIfNeededLocked", boolean.class);
            }
            if (switchImpl == null) return;
            switchImpl.setAccessible(true);

            // Use the non-locked async variant when available: it posts to FgThread and avoids
            // "Can't create handler inside thread" crashes.
            switchImpl.invoke(serviceStub, true);

            long deadline = SystemClock.uptimeMillis() + 2500;
            while (SystemClock.uptimeMillis() < deadline) {
                Object current = getField(serviceStub, "mImpl");
                if (current == null) {
                    Thread.sleep(30);
                    continue;
                }
                Object currentActive = getField(current, "mActiveSession");
                Object currentService = getField(current, "mService");
                if (currentActive == null && currentService != null) {
                    log(null, "voice interaction session reset done");
                    return;
                }
                Thread.sleep(30);
            }
        } catch (Throwable t) {
            log(null, "clearStaleVoiceInteractionSession error: " + t);
        }
    }

    private static void hookSystemServerSettings(XposedModule module) {
        try {
            Class<?> sys = Class.forName("com.android.server.SystemServer", false, sSystemServerClassLoader);
            for (Method m : sys.getDeclaredMethods()) {
                if ("startOtherServices".equals(m.getName())) {
                    m.setAccessible(true);
                    HookHandle h = module.hook(m).intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                writeAllSettings(module);
                            } catch (Throwable t) {
                                log(module, "writeAllSettings after startOtherServices failed: " + t);
                            }
                            return result;
                        }
                    });
                    sHandles.add(h);
                }
            }
        } catch (Throwable t) {
            log(module, "hookSystemServerSettings failed: " + t);
        }
    }

    private static void writeAllSettings(XposedModule module) {
        Context ctx = getSystemContext();
        if (ctx == null) return;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            Settings.Secure.putString(cr, Constants.SECURE_ASSISTANT, Constants.GSB_ASSIST_SERVICE);
            Settings.Secure.putString(cr, Constants.SECURE_VOICE_INTERACT, Constants.GSB_ASSIST_SERVICE);
            Settings.Secure.putString(cr, Constants.SECURE_VOICE_RECOG, Constants.GSB_RECOG_SERVICE);
            Settings.Secure.putInt(cr, Constants.SECURE_ASSIST_STRUCTURE, 1);
            Settings.Secure.putInt(cr, Constants.SECURE_ASSIST_SCREENSHOT, 1);
            Settings.Global.putInt(cr, Constants.GLOBAL_POWER_LONG_PRESS, Constants.LONG_PRESS_POWER_ASSIST);
            log(module, "assistant settings written");
        } catch (Throwable t) {
            log(module, "writeAllSettings failed: " + t);
        }
    }

    /**
     * 拦截 MIUI 开机时把 voice_interaction_service / assistant 写回小爱的重写。
     * 只要发现目标 key 是这两条、且新值指向小爱（voiceassist / xiaomi.voiceassistant），
     * 就把值改回 Google，防止助手设置被系统还原。
     */
    private static void hookSettingsRewriteBlock(XposedModule module) {
        try {
            Class<?> secure = Class.forName(
                    "android.provider.Settings$Secure", false, sSystemServerClassLoader);
            for (Method m : secure.getDeclaredMethods()) {
                if (!"putStringForUser".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 3 || pts[1] != String.class || pts[2] != String.class) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object[] args = chain.getArgs().toArray();
                            if (args == null || args.length < 3) return chain.proceed();
                            if (!(args[1] instanceof String) || !(args[2] instanceof String)) {
                                return chain.proceed();
                            }
                            String key = (String) args[1];
                            String val = (String) args[2];
                            if (!isAssistantServiceKey(key)) return chain.proceed();
                            boolean wantsXiaoAi = val.contains("voiceassist")
                                    || val.contains("xiaomi.voiceassistant");
                            if (!wantsXiaoAi) return chain.proceed();
                            args[2] = Constants.GSB_ASSIST_SERVICE;
                            log(module, "rewrote " + key + "=" + val + " -> Google");
                            return chain.proceed(args);
                        } catch (Throwable t) {
                            log(module, "hookSettingsRewriteBlock error: " + t);
                            return chain.proceed();
                        }
                    }
                });
                sHandles.add(h);
            }
        } catch (Throwable t) {
            log(module, "hookSettingsRewriteBlock failed: " + t);
        }
    }

    private static boolean isAssistantServiceKey(String key) {
        return Constants.SECURE_VOICE_INTERACT.equals(key)
                || Constants.SECURE_ASSISTANT.equals(key);
    }

    /**
     * 在 com.miui.voiceassist 进程内安装：禁止小爱把 power_wakeup 重新置位，
     * 避免它抢占"电源键唤醒"。SharedPreferencesImpl 是 framework 类，这里用一个
     * 目标进程的 classLoader 解析到同一份 boot classloader 里的实现。
     * 通过 onPackageLoaded 在 target 包加载时调用。
     */
    public static synchronized void installXiaoAiHooks(XposedModule module, ClassLoader cl) {
        try {
            if (cl == null) return;
            // EditorImpl.putBoolean(String, boolean): 写 power_wakeup 时强制 false
            Class<?> editor = Class.forName(
                    "android.app.SharedPreferencesImpl$EditorImpl", false, cl);
            for (Method m : editor.getDeclaredMethods()) {
                if (!"putBoolean".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2 || pts[0] != String.class || pts[1] != boolean.class) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length >= 2 && args[0] instanceof String
                                    && HookPolicy.isPowerWakeupKey((String) args[0])) {
                                args[1] = false;
                                return chain.proceed(args);
                            }
                        } catch (Throwable t) {
                            log(module, "xiaomi putBoolean hook error: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
                break;
            }
            // SharedPreferencesImpl.getBoolean(String, boolean): 读 power_wakeup 时强制 false
            Class<?> prefs = Class.forName(
                    "android.app.SharedPreferencesImpl", false, cl);
            for (Method m : prefs.getDeclaredMethods()) {
                if (!"getBoolean".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2 || pts[0] != String.class || pts[1] != boolean.class) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String
                                    && HookPolicy.isPowerWakeupKey((String) arg0)) {
                                return false;
                            }
                        } catch (Throwable t) {
                            log(module, "xiaomi getBoolean hook error: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
                break;
            }
        } catch (Throwable t) {
            log(module, "installXiaoAiHooks failed: " + t);
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            return (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookOne(XposedModule module, Method m, String desc) {
        try {
            m.setAccessible(true);
            HookHandle h = module.hook(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    try {
                        Object thisObj = chain.getThisObject();
                        if (thisObj == null) {
                            return chain.proceed();
                        }
                        Context ctx = (Context) getField(thisObj, "mContext");
                        if (ctx != null && sendAssist(module, ctx)) {
                            return skipResult(m);
                        }
                    } catch (Throwable t) {
                        log(module, desc + " callback failed: " + t);
                    }
                    return chain.proceed();
                }
            });
            sHandles.add(h);
        } catch (Throwable t) {
            log(module, "hookOne failed " + desc + ": " + t);
        }
    }

    private static Object skipResult(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == Void.TYPE) return null;
        if (rt == Boolean.TYPE) return true;
        if (rt == Integer.TYPE) return 0;
        return null;
    }

    private static boolean sendAssist(XposedModule module, Context ctx) {
        ensureGoogleVis(module, ctx);

        // 快速路径：Google 已由 keep-alive 保活，不再每次 hide/startService。
        // 不打开 Google App。让系统准备 VoiceInteractionSession，
        // 然后把已有的 FlotyActivity 带到前台。
        if (showSessionForActiveService(module)) {
            if (startFloatyActivity(module, ctx)) {
                log(module, "Gemini overlay launched");
                vibrate(ctx);
                return true;
            }
            // showSession 已返回 true 时按成功处理，避免 fallback 打开 Google App。
            log(module, "Gemini overlay launched");
            vibrate(ctx);
            return true;
        }
        log(module, "overlay launch failed; fallback to original");
        return false;
    }

    private static void vibrate(Context ctx) {
        if (sVibrated) return;
        sVibrated = true;
        try {
            Object vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator instanceof android.os.Vibrator) {
                ((android.os.Vibrator) vibrator).vibrate(
                        android.os.VibrationEffect.createPredefined(
                                android.os.VibrationEffect.EFFECT_CLICK));
            }
        } catch (Throwable ignored) {
        }
    }

    // 有意使用原始 flag 组合把 FlotyActivity 带到前台（system_server 环境，真机验证过）。
    @SuppressLint("WrongConstant")
    private static boolean startFloatyActivity(XposedModule module, Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setComponent(ComponentName.unflattenFromString(FLOATY_ACTIVITY));
            intent.addFlags(0x14058000); // NEW_TASK | CLEAR_TASK etc.
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            log(module, "startFloatyActivity failed: " + t);
            return false;
        }
    }

    private static void ensureGoogleVis(XposedModule module, Context ctx) {
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Constants.SECURE_VOICE_INTERACT);
            if (!Constants.GSB_ASSIST_SERVICE.equals(cur)) {
                Settings.Secure.putString(ctx.getContentResolver(),
                        Constants.SECURE_VOICE_INTERACT, Constants.GSB_ASSIST_SERVICE);
                log(module, "forced voice_interaction_service to Google");
            }
        } catch (Throwable t) {
            log(module, "ensureGoogleVis failed: " + t);
        }
    }

    private static boolean showSessionForActiveService(XposedModule module) {
        try {
            Object svc = getVoiceInteractionService();
            if (svc == null) return false;
            Method m = null;
            for (Method cand : svc.getClass().getMethods()) {
                if (!"showSessionForActiveService".equals(cand.getName())) continue;
                if (isShowSessionSignature(cand.getParameterTypes())) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                for (Method cand : svc.getClass().getDeclaredMethods()) {
                    if ("showSessionForActiveService".equals(cand.getName())
                            && isShowSessionSignature(cand.getParameterTypes())) {
                        m = cand;
                        break;
                    }
                }
            }
            if (m == null) return false;
            m.setAccessible(true);
            Object[] args = buildShowSessionArgs(m.getParameterTypes());
            Object result = m.invoke(svc, args);
            if (result instanceof Boolean && !((Boolean) result)) return false;
            return true;
        } catch (Throwable t) {
            log(module, "showSessionForActiveService failed: " + t);
            return false;
        }
    }

    private static boolean isShowSessionSignature(Class<?>[] pts) {
        boolean hasBundle = false;
        boolean hasFlags = false;
        for (Class<?> pt : pts) {
            if (pt == android.os.Bundle.class) {
                hasBundle = true;
            } else if (pt == int.class || pt == Integer.class) {
                hasFlags = true;
            } else if (pt == String.class) {
            } else if (pt == boolean.class || pt == Boolean.class) {
            } else if (pt == android.os.IBinder.class) {
            } else if (pt.isInterface() && pt.getSimpleName().endsWith("Callback")) {
            } else {
                return false;
            }
        }
        return hasBundle && hasFlags;
    }

    private static Object[] buildShowSessionArgs(Class<?>[] pts) {
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Class<?> pt = pts[i];
            if (pt == android.os.Bundle.class) {
                args[i] = assistArgs();
            } else if (pt == int.class || pt == Integer.class) {
                args[i] = SHOW_POWER_ASSIST_WITH_SCREENSHOT;
            } else if (pt == boolean.class || pt == Boolean.class) {
                args[i] = true;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static android.os.Bundle assistArgs() {
        android.os.Bundle args = new android.os.Bundle();
        args.putInt("invocation_type", 7);
        args.putString("invocation_source", "POWER_LONG_PRESS");
        args.putBoolean("request_assist_structure", true);
        args.putBoolean("request_assist_screenshot", true);
        return args;
    }

    private static Object getVoiceInteractionService() throws Exception {
        Object binder = ServiceManagerGet("voiceinteraction");
        if (binder == null) return null;
        Class<?> stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService$Stub");
        return stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    private static Object ServiceManagerGet(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return sm.getMethod("getService", String.class).invoke(null, name);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... pts) {
        try {
            Method m = clazz.getMethod(name, pts);
            if (m != null) return m;
        } catch (Throwable ignored) {
        }
        try {
            Method m = clazz.getDeclaredMethod(name, pts);
            if (m != null) return m;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getField(Object owner, String name) {
        Class<?> c = owner.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void log(XposedModule module, String msg) {
        try {
            if (module != null) {
                module.log(android.util.Log.INFO, TAG, msg);
            } else {
                android.util.Log.i(TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}
