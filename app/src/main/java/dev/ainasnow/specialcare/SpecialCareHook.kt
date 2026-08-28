/*
 * This standalone reduction is based on the notification-channel portions of
 * QAuxiliary's SpecialCareNewChannel.
 * Modified and separated on 2026-08-28. See LICENSE-QAUXILIARY.md.
 * Original project: https://github.com/cinit/QAuxiliary
 */

package dev.ainasnow.specialcare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only behavior implemented by this module is channel rerouting.
 *
 * We hook the final Android NotificationManager.notify entry point instead of
 * trying to correlate QQ NT's internal objects. This is the same boundary used
 * by QAuxiliary's working SpecialCareNewChannel implementation, and it works
 * for both NT and legacy QQ notification builders.
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

    private val installedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val notifyHookSeenLogged = AtomicBoolean(false)
    private val specialMatchLogged = AtomicBoolean(false)
    private val rewriteErrorLogged = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            log("Android O or later is required")
            return
        }
        if (!installedClassLoaders.add(classLoader)) {
            return
        }

        try {
            val appContext = context.applicationContext ?: context
            createSpecialCareChannel(appContext)
            installNotificationManagerHook()
            log("installed final NotificationManager.notify hook")
        } catch (t: Throwable) {
            installedClassLoaders.remove(classLoader)
            log("failed to install NotificationManager.notify hook")
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

    private fun installNotificationManagerHook() {
        XposedBridge.hookAllMethods(
            NotificationManager::class.java,
            "notify",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (notifyHookSeenLogged.compareAndSet(false, true)) {
                            log("NotificationManager.notify was reached")
                        }

                        val notification = param.args.lastOrNull { it is Notification }
                            as? Notification ?: return
                        val title = notification.extras
                            ?.getCharSequence(Notification.EXTRA_TITLE)
                            ?.toString()
                            ?: return
                        if (!title.contains(SPECIAL_TITLE_MARKER)) {
                            return
                        }

                        val oldChannelId = notification.channelId
                        if (oldChannelId == SPECIAL_CHANNEL_ID) {
                            return
                        }

                        // QQ's Notification object is mutable. Changing the
                        // channel at the final notify boundary preserves QQ's
                        // content/style while letting Android route it to the
                        // independent channel.
                        XposedHelpers.setObjectField(
                            notification,
                            "mChannelId",
                            SPECIAL_CHANNEL_ID
                        )

                        if (specialMatchLogged.compareAndSet(false, true)) {
                            log("matched special-care notification and changed channel")
                        }
                    } catch (t: Throwable) {
                        if (rewriteErrorLogged.compareAndSet(false, true)) {
                            log("failed while processing NotificationManager.notify")
                            XposedBridge.log(t)
                        }
                    }
                }
            }
        )
    }

    private fun log(message: String) {
        XposedBridge.log("$LOG_TAG: $message")
    }
}
