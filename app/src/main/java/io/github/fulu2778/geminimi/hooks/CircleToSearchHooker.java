package io.github.fulu2778.geminimi.hooks;

import android.content.Intent;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Android 15+「圈定即搜」(Circle to Search / Contextual Search)，最小实现。
 *
 * 工作链路（都是实测跑通的）：
 *  小爱进程: 桌面把「长按小白条」显式路由成 ACTION_ASSIST → com.xiaomi.voiceassistant.VoiceService；
 *           我们 hook VoiceService.onStartCommand，抓到该手势时改调 startContextualSearch(1)。
 *  system_server: 接管 ContextualSearchManagerService.startContextualSearch，对到达的调用
 *           清调用身份 + BYPASS（provider 指向 Google），让真正的 CS 会话跑起来；
 *           另让系统认为设备支持 CS（deviceHasConfigString）。
 *  Google 进程: 伪装 Build.MANUFACTURER 等为 Pixel 9 Pro，使 Google 渲染圈定即搜 UI。
 *
 * 不依赖 com.miui.home 注入（该 ROM 上模块进不去桌面），也绝不碰电源键 Gemini Overlay 逻辑。
 */
public final class CircleToSearchHooker {

    private CircleToSearchHooker() {}

    private static final String CS_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final List<HookHandle> sHandles = new ArrayList<>();
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();
    private static volatile ClassLoader sSystemServerClassLoader;

    // 去抖：桌面一次小白条手势会连发两次 startService，只放行第一次，避免 CS 被调两遍。
    private static volatile long sLastRedirectMs;

    // ---- system_server ----

    public static synchronized void installSystemServer(XposedModule module, ClassLoader cl) {
        for (HookHandle h : sHandles) {
            try { h.unhook(); } catch (Throwable ignored) {}
        }
        sHandles.clear();
        if (cl != null) sSystemServerClassLoader = cl;
        if (sSystemServerClassLoader == null) return;
        try {
            hookDeviceHasConfigString(module);
            hookContextualSearchManager(module);
            hookNativeLauncherTrigger(module);
            log(module, "CS system-server installed handles=" + sHandles.size());
        } catch (Throwable t) {
            log(module, "CS installSystemServer failed: " + t);
        }
    }

    /** SystemServer.deviceHasConfigString(Context, int) -> true（用于 CS 包名），让系统认为设备支持 CS。 */
    private static void hookDeviceHasConfigString(XposedModule module) {
        try {
            Class<?> rString = Class.forName(
                    "com.android.internal.R$string", false, sSystemServerClassLoader);
            final int pkgResId = rString.getField(
                    "config_defaultContextualSearchPackageName").getInt(null);
            Class<?> ss = Class.forName("com.android.server.SystemServer",
                    false, sSystemServerClassLoader);
            for (Method m : ss.getDeclaredMethods()) {
                if (!"deviceHasConfigString".equals(m.getName())) continue;
                m.setAccessible(true);
                final Method target = m;
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object arg1 = chain.getArg(1);
                        return (arg1 instanceof Integer && (Integer) arg1 == pkgResId)
                                ? Boolean.TRUE : chain.proceed();
                    }
                });
                sHandles.add(h);
            }
        } catch (Throwable t) {
            log(module, "deviceHasConfigString hook failed: " + t);
        }
    }

    /** 权限与 provider：enforcePermission 绕过 + getContextualSearchPackageName -> Google（仅在 BYPASS 时）。 */
    private static void hookContextualSearchManager(XposedModule module) {
        try {
            Class<?> csms = Class.forName(
                    "com.android.server.contextualsearch.ContextualSearchManagerService",
                    false, sSystemServerClassLoader);
            for (Method m : csms.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] pts = m.getParameterTypes();
                boolean isEnforce = "enforcePermission".equals(name)
                        && pts.length == 1 && pts[0] == String.class;
                boolean isGetPkg = "getContextualSearchPackageName".equals(name)
                        && pts.length == 0;
                if (!isEnforce && !isGetPkg) continue;
                m.setAccessible(true);
                final boolean wantPkg = isGetPkg;
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if (BYPASS.get() != Boolean.TRUE) return chain.proceed();
                        return wantPkg ? CS_PACKAGE : null;
                    }
                });
                sHandles.add(h);
            }
        } catch (Throwable t) {
            log(module, "ContextualSearchManager hook failed: " + t);
        }
    }

    /**
     * 接管 startContextualSearch：该 ROM 在方法内部内联做权限检查（调用方桌面/小爱均无权限）。
     * 任何到达的调用都：套 BYPASS(provider 指向 Google) + 清调用身份(以 system 身份 proceed) 放行。
     */
    private static void hookNativeLauncherTrigger(XposedModule module) {
        try {
            Class<?> stub = Class.forName(
                    "com.android.server.contextualsearch.ContextualSearchManagerService"
                            + "$ContextualSearchManagerStub", false, sSystemServerClassLoader);
            int hooked = 0;
            for (Method m : stub.getDeclaredMethods()) {
                String name = m.getName();
                if (!"startContextualSearch".equals(name)
                        && !"startContextualSearchForApp".equals(name)) continue;
                if (m.getReturnType() != Void.TYPE) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        log(module, "startContextualSearch bridged (callerUid="
                                + android.os.Binder.getCallingUid() + ")");
                        return withPermissionBypass(() -> {
                            long token = android.os.Binder.clearCallingIdentity();
                            try {
                                return chain.proceed();
                            } finally {
                                android.os.Binder.restoreCallingIdentity(token);
                            }
                        });
                    }
                });
                sHandles.add(h);
                hooked++;
            }
        } catch (Throwable t) {
            log(module, "native launcher trigger hook failed: " + t);
        }
    }

    // ---- Google 进程 ----

    /** 伪装成 Pixel 9 Pro，使 Google 认可并渲染圈定即搜。 */
    public static void installGoogleDeviceSpoof(XposedModule module, ClassLoader cl) {
        try {
            if (cl == null) return;
            Class<?> build = Class.forName("android.os.Build", false, cl);
            setStaticField(build, "MANUFACTURER", "Google");
            setStaticField(build, "BRAND", "google");
            setStaticField(build, "MODEL", "Pixel 9 Pro");
            setStaticField(build, "DEVICE", "caiman");
            log(module, "CS device spoof applied");
        } catch (Throwable t) {
            log(module, "CS device spoof failed: " + t);
        }
    }

    // ---- 小爱进程 ----

    /** 小白条被路由到小爱 VoiceService 时改走圈定即搜。 */
    public static synchronized void installXiaoAiVoiceRedirect(XposedModule module, ClassLoader cl) {
        if (cl == null) return;
        try {
            Class<?> vs = Class.forName("com.xiaomi.voiceassistant.VoiceService", false, cl);
            for (Method m : vs.getDeclaredMethods()) {
                if (!"onStartCommand".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 3) continue;
                m.setAccessible(true);
                HookHandle h = module.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof Intent
                                    && Intent.ACTION_ASSIST.equals(((Intent) arg0).getAction())) {
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (now - sLastRedirectMs < 1000) return null; // 去抖
                                sLastRedirectMs = now;
                                log(module, "white-bar ACTION_ASSIST -> Circle to Search");
                                triggerContextualSearch(module, 1);
                                // 不调用 super，跳过小爱语音 UI。
                                return Integer.valueOf(android.app.Service.START_NOT_STICKY);
                            }
                        } catch (Throwable t) {
                            log(module, "voice redirect err: " + t);
                        }
                        return chain.proceed();
                    }
                });
                sHandles.add(h);
                log(module, "VoiceService.onStartCommand hooked");
                return;
            }
            log(module, "VoiceService.onStartCommand not found");
        } catch (Throwable t) {
            log(module, "installXiaoAiVoiceRedirect failed: " + t);
        }
    }

    // ---- CS 调用 ----

    private static boolean triggerContextualSearch(XposedModule module, int entryPoint) {
        try {
            IBinder cs = (IBinder) ServiceManagerGet("contextual_search");
            if (cs == null) return false;
            Class<?> stub = Class.forName(
                    "android.app.contextualsearch.IContextualSearchManager$Stub");
            Object icsm = stub.getMethod("asInterface", IBinder.class).invoke(null, cs);
            Class<?> icsmClass = Class.forName(
                    "android.app.contextualsearch.IContextualSearchManager");
            Method start;
            try {
                start = icsmClass.getDeclaredMethod("startContextualSearch", int.class);
            } catch (NoSuchMethodException e) {
                Class<?> cfg = Class.forName("android.app.contextualsearch.ContextualSearchConfig");
                start = icsmClass.getDeclaredMethod("startContextualSearch", int.class, cfg);
            }
            start.setAccessible(true);
            Object[] args = start.getParameterTypes().length == 1
                    ? new Object[]{entryPoint}
                    : new Object[]{entryPoint, null};
            start.invoke(icsm, args);
            return true;
        } catch (Throwable t) {
            log(module, "triggerContextualSearch failed: " + t);
            return false;
        }
    }

    private static Object ServiceManagerGet(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return sm.getMethod("getService", String.class).invoke(null, name);
    }

    // ---- helpers ----

    private static <T> T withPermissionBypass(ThrowingSupplier<T> block) throws Throwable {
        Boolean prev = BYPASS.get();
        BYPASS.set(Boolean.TRUE);
        try {
            return block.get();
        } finally {
            if (prev == null) BYPASS.remove(); else BYPASS.set(prev);
        }
    }

    private static void setStaticField(Class<?> clazz, String name, Object value) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            try {
                Field accessFlags = Field.class.getDeclaredField("accessFlags");
                accessFlags.setAccessible(true);
                accessFlags.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            } catch (Throwable ignored) {
            }
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    private static void log(XposedModule module, String msg) {
        try {
            if (module != null) module.log(android.util.Log.INFO, "[GeminiMi]", msg);
            else android.util.Log.i("[GeminiMi]", msg);
        } catch (Throwable ignored) {
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}
