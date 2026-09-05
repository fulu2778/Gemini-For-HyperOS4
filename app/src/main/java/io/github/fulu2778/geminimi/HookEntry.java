package io.github.fulu2778.geminimi;

import android.util.Log;

import io.github.fulu2778.geminimi.hooks.Api102PowerKeyHook;
import io.github.fulu2778.geminimi.hooks.CircleToSearchHooker;

import java.util.List;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class HookEntry extends XposedModule {

    private static HookEntry instance;

    public static HookEntry getInstance() {
        return instance;
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        instance = this;
        log(Log.INFO, Constants.TAG, "API102 module loaded in " + param.getProcessName());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        instance = this;
        log(Log.INFO, Constants.TAG, "API102 system_server starting");
        try {
            Api102PowerKeyHook.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, Constants.TAG, "API102 system hook install failed: " + t, t);
        }
        // Android 15+ 圈定即搜(CS)：system_server 侧。
        try {
            CircleToSearchHooker.installSystemServer(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, Constants.TAG, "CS system-server install failed: " + t, t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        instance = this;
        // com.miui.voiceassist hooks are optional for now; main route is in system_server.
        if (Constants.XIAOAI_PKG.equals(param.getPackageName())) {
            log(Log.INFO, Constants.TAG, "API102 loaded into " + param.getPackageName());
            try {
                Api102PowerKeyHook.installXiaoAiHooks(this, param.getDefaultClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, Constants.TAG, "API102 installXiaoAiHooks failed: " + t, t);
            }
            // 圈定即搜：小白条被路由到小爱 VoiceService 时改走 CS。
            try {
                CircleToSearchHooker.installXiaoAiVoiceRedirect(this, param.getDefaultClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, Constants.TAG, "CS xiaoai redirect failed: " + t, t);
            }
        } else if (Constants.GSB_PKG.equals(param.getPackageName())) {
            // 圈定即搜：在 Google 进程里伪装成支持 CS 的设备。
            log(Log.INFO, Constants.TAG, "API102 loaded into Google App");
            try {
                CircleToSearchHooker.installGoogleDeviceSpoof(this, param.getDefaultClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, Constants.TAG, "CS device spoof failed: " + t, t);
            }
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        instance = this;
        log(Log.INFO, Constants.TAG, "API102 hot reloaded");
        try {
            ClassLoader cl = null;
            List<HookHandle> oldHandles = param.getOldHookHandles();
            if (oldHandles != null) {
                for (HookHandle h : oldHandles) {
                    try {
                        cl = h.getExecutable().getDeclaringClass().getClassLoader();
                        if (cl != null) break;
                    } catch (Throwable ignored) {
                    }
                }
                for (HookHandle h : oldHandles) {
                    try {
                        h.unhook();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (cl == null) {
                cl = Api102PowerKeyHook.getLastSystemServerClassLoader();
            }
            Api102PowerKeyHook.install(this, cl);
            CircleToSearchHooker.installSystemServer(this, cl);
        } catch (Throwable t) {
            log(Log.ERROR, Constants.TAG, "API102 hot reload reinstall failed: " + t, t);
        }
    }
}
