/*
 * This standalone reduction is based on the notification-channel portions of
 * QAuxiliary's MessagingStyleNotification and NonNTMessageStyleNotification.
 * Modified and separated on 2026-08-28. See LICENSE-QAUXILIARY.md.
 * Original project: https://github.com/cinit/QAuxiliary
 */

package dev.ainasnow.specialcare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only behavior implemented by this module is channel rerouting.
 *
 * The NT path follows the three-object chain used by QQ's notification facade:
 * RecentContactInfo -> notification-building object -> contentIntent. We do
 * not rebuild MessagingStyle, add replies/bubbles, or intercept cancellation.
 * The legacy path only looks for QQ's final Notification builder and the
 * [特别关心] title marker.
 */
object SpecialCareHook {
    const val LOG_TAG = "SpecialCareNotification"
    const val QQ_PACKAGE = "com.tencent.mobileqq"

    private const val SPECIAL_CHANNEL_ID = "QQ_Friend_Special"
    private const val CHANNEL_GROUP_ID = "qq_evolution"
    private const val CHANNEL_GROUP_NAME = "QQ通知进化 Plus"
    private const val CHANNEL_NAME = "特别关心消息"
    private const val CHANNEL_DESCRIPTION = "QQ 特别关心好友私聊消息通知"
    private const val SPECIAL_TITLE_MARKER = "[特别关心]"
    private const val SPECIAL_EVENT_MARKER = "eventTypeInMsgBox=1006"

    private data class NotificationInfo(
        val recentInfo: Any
    )

    private val installedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val notificationInfoMap = Collections.synchronizedMap(
        WeakHashMap<Any, NotificationInfo>()
    )
    private val copyErrorLogged = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (!installedClassLoaders.add(classLoader)) {
            return
        }

        try {
            val appContext = context.applicationContext ?: context
            createSpecialCareChannel(appContext)

            val ntInstalled = installNtHooks(appContext, classLoader)
            val legacyInstalled = if (!ntInstalled) {
                installLegacyHook(appContext, classLoader)
            } else {
                false
            }

            if (!ntInstalled && !legacyInstalled) {
                installedClassLoaders.remove(classLoader)
                log("QQ notification entry points were not found")
            } else {
                val path = if (ntInstalled) "NT" else "legacy"
                log("installed $path notification hook")
            }
        } catch (t: Throwable) {
            installedClassLoaders.remove(classLoader)
            log("failed to install notification hook")
            XposedBridge.log(t)
        }
    }

    private fun createSpecialCareChannel(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager.notificationChannelGroups.none { it.id == CHANNEL_GROUP_ID }) {
            notificationManager.createNotificationChannelGroup(
                NotificationChannelGroup(CHANNEL_GROUP_ID, CHANNEL_GROUP_NAME)
            )
        }

        if (notificationManager.getNotificationChannel(SPECIAL_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                SPECIAL_CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                group = CHANNEL_GROUP_ID
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun installNtHooks(context: Context, classLoader: ClassLoader): Boolean {
        val facadeClass = loadClass("com.tencent.qqnt.notification.NotificationFacade", classLoader)
            ?: return false
        val appRuntimeClass = loadClass("mqq.app.AppRuntime", classLoader)
            ?: return false
        val commonInfoClass = loadClass(
            "com.tencent.qqnt.kernel.nativeinterface.NotificationCommonInfo",
            classLoader
        ) ?: return false
        val recentInfoClass = loadClass(
            "com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo",
            classLoader
        ) ?: return false

        val postMethodAndIndex = findNtPostMethod(facadeClass) ?: return false
        val buildMethod = allDeclaredMethods(facadeClass)
            .filter { isNtBuildMethod(it, appRuntimeClass, commonInfoClass, recentInfoClass) }
            .maxWithOrNull(compareBy<Method> { it.parameterTypes.size }.thenBy { it.name })
            ?: return false
        val recentInfoBuilder = allDeclaredMethods(facadeClass)
            .filter { isRecentInfoBuilder(it, recentInfoClass) }
            .maxWithOrNull(compareBy<Method> { it.parameterTypes.size }.thenBy { it.name })
            ?: return false

        XposedBridge.hookMethod(
            recentInfoBuilder,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recentInfo = param.args.getOrNull(1) ?: return
                    val result = param.result ?: return
                    val intent = findFieldValue(result, Intent::class.java) as? Intent ?: return
                    notificationInfoMap[result] = NotificationInfo(recentInfo)
                }
            }
        )

        XposedBridge.hookMethod(
            buildMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val notificationElement = param.args.getOrNull(1) ?: return
                    val pendingIntent = findFieldValue(
                        notificationElement,
                        PendingIntent::class.java
                    ) as? PendingIntent ?: return
                    val info = notificationInfoMap.remove(notificationElement) ?: return
                    notificationInfoMap[pendingIntent] = info
                }
            }
        )

        XposedBridge.hookMethod(
            postMethodAndIndex.first,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val oldNotification = param.args.getOrNull(postMethodAndIndex.second)
                        as? Notification ?: return
                    val pendingIntent = oldNotification.contentIntent ?: return
                    val info = notificationInfoMap.remove(pendingIntent) ?: return
                    if (!isSpecialPrivateContact(info.recentInfo)) {
                        return
                    }
                    val rewritten = copyToSpecialCareChannel(context, oldNotification) ?: return
                    param.args[postMethodAndIndex.second] = rewritten
                }
            }
        )

        return true
    }

    private fun installLegacyHook(context: Context, classLoader: ClassLoader): Boolean {
        val serviceClass = findLegacyServiceClass(classLoader) ?: return false
        val buildMethod = allDeclaredMethods(serviceClass).firstOrNull { method ->
            val parameters = method.parameterTypes
            method.returnType == Notification::class.java &&
                parameters.size == 5 &&
                parameters[0] == Intent::class.java &&
                parameters[1] == Bitmap::class.java &&
                parameters[2] == String::class.java &&
                parameters[3] == String::class.java &&
                parameters[4] == String::class.java
        } ?: return false

        XposedBridge.hookMethod(
            buildMethod,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val intent = param.args.getOrNull(0) as? Intent ?: return
                    val uinType = intent.getIntExtra(
                        "uintype",
                        intent.getIntExtra("param_uinType", -1)
                    )
                    if (uinType != 0) {
                        return
                    }
                    val title = param.args.getOrNull(3)?.toString() ?: return
                    if (!title.contains(SPECIAL_TITLE_MARKER)) {
                        return
                    }
                    val oldNotification = param.result as? Notification ?: return
                    param.result = copyToSpecialCareChannel(context, oldNotification)
                }
            }
        )
        return true
    }

    private fun isSpecialPrivateContact(recentInfo: Any): Boolean {
        val chatType = asInt(readField(recentInfo, "chatType")) ?: return false
        if (chatType != 1) {
            return false
        }

        val specialFlag = readField(recentInfo, "specialCareFlag")
        val flagMatches = when (specialFlag) {
            is Boolean -> specialFlag
            is Number -> specialFlag.toInt() == 1
            is String -> specialFlag == "1" || specialFlag.equals("true", ignoreCase = true)
            else -> false
        }
        if (flagMatches) {
            return true
        }

        return readField(recentInfo, "listOfSpecificEventTypeInfosInMsgBox")
            ?.toString()
            ?.contains(SPECIAL_EVENT_MARKER) == true
    }

    private fun copyToSpecialCareChannel(
        context: Context,
        original: Notification
    ): Notification? {
        return try {
            Notification.Builder.recoverBuilder(context, original)
                .setChannelId(SPECIAL_CHANNEL_ID)
                .build()
        } catch (t: Throwable) {
            if (copyErrorLogged.compareAndSet(false, true)) {
                log("failed to copy QQ notification")
                XposedBridge.log(t)
            }
            null
        }
    }

    private fun findNtPostMethod(facadeClass: Class<*>): Pair<Method, Int>? {
        return allDeclaredMethods(facadeClass).firstOrNull { method ->
            val parameters = method.parameterTypes
            parameters.size == 2 &&
                parameters[0] == Notification::class.java &&
                parameters[1] == Int::class.javaPrimitiveType
        }?.let { it to 0 } ?: allDeclaredMethods(facadeClass).firstOrNull { method ->
            val parameters = method.parameterTypes
            parameters.size == 3 &&
                parameters[0] == String::class.java &&
                parameters[1] == Notification::class.java &&
                parameters[2] == Int::class.javaPrimitiveType
        }?.let { it to 1 }
    }

    private fun isNtBuildMethod(
        method: Method,
        appRuntimeClass: Class<*>,
        commonInfoClass: Class<*>,
        recentInfoClass: Class<*>
    ): Boolean {
        val parameters = method.parameterTypes
        if (parameters.size !in 3..5 || parameters[0] != appRuntimeClass) {
            return false
        }
        if (parameters[2] != commonInfoClass) {
            return false
        }
        return when (parameters.size) {
            3 -> true
            4 -> parameters[3] == recentInfoClass
            5 -> parameters[3] == recentInfoClass &&
                parameters[4] == Boolean::class.javaPrimitiveType
            else -> false
        }
    }

    private fun isRecentInfoBuilder(method: Method, recentInfoClass: Class<*>): Boolean {
        val parameters = method.parameterTypes
        if (parameters.size < 3 || parameters[1] != recentInfoClass) {
            return false
        }
        return parameters[2] == Boolean::class.java ||
            parameters[2] == Boolean::class.javaPrimitiveType
    }

    private fun findLegacyServiceClass(classLoader: ClassLoader): Class<*>? {
        loadClass("com.tencent.mobileqq.service.MobileQQServiceExtend", classLoader)?.let {
            return it
        }

        val qqAppInterface = loadClass("com.tencent.mobileqq.app.QQAppInterface", classLoader)
            ?: return null
        return findFieldType(qqAppInterface, "mqqService")
    }

    private fun loadClass(name: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun allDeclaredMethods(start: Class<*>): List<Method> {
        val methods = ArrayList<Method>()
        var current: Class<*>? = start
        while (current != null && current != Any::class.java) {
            try {
                methods.addAll(current.declaredMethods.toList())
            } catch (_: Throwable) {
                // A partially loaded QQ class can reject reflection. Keep scanning
                // its parent so a compatible inherited method can still be found.
            }
            current = current.superclass
        }
        return methods
    }

    private fun findFieldType(start: Class<*>, name: String): Class<*>? {
        var current: Class<*>? = start
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name).type
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun findFieldValue(target: Any, requestedType: Class<*>): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            try {
                for (field in current.declaredFields) {
                    if (field.type != requestedType) {
                        continue
                    }
                    field.isAccessible = true
                    return field.get(target)
                }
            } catch (_: Throwable) {
                // Try the parent class or simply abandon this mapping.
            }
            current = current.superclass
        }
        return null
    }

    private fun readField(target: Any, name: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            try {
                val field: Field = current.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun asInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun log(message: String) {
        XposedBridge.log("$LOG_TAG: $message")
    }
}
