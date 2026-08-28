package dev.ainasnow.specialcare

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * Minimal classic Xposed entry point.
 *
 * The temporary Application.attach hook is only used to obtain QQ's Context. It
 * is removed immediately after the notification hooks are installed.
 */
class SpecialCareXposed : IXposedHookLoadPackage {
    private val attachedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SpecialCareHook.QQ_PACKAGE) {
            return
        }
        if (!attachedClassLoaders.add(lpparam.classLoader)) {
            return
        }

        var attachUnhook: XC_MethodHook.Unhook? = null
        try {
            val attachMethod = Application::class.java.getDeclaredMethod(
                "attach",
                Context::class.java
            )
            attachUnhook = XposedBridge.hookMethod(
                attachMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val application = param.thisObject as? Application ?: return
                        if (application.packageName != SpecialCareHook.QQ_PACKAGE) {
                            return
                        }
                        attachUnhook?.unhook()
                        SpecialCareHook.install(application, lpparam.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            attachedClassLoaders.remove(lpparam.classLoader)
            XposedBridge.log("${SpecialCareHook.LOG_TAG} failed to hook Application.attach")
            XposedBridge.log(t)
        }
    }
}
