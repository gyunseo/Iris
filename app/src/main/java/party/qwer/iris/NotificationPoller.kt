package party.qwer.iris

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread

class NotificationPoller {
    // senderId → 마지막으로 저장한 이름. 이름이 달라지면 재저장(오염 자가 교정).
    private val cachedSenderNames = mutableMapOf<String, String>()
    private val processedNotifications = mutableMapOf<String, Long>()

    fun startPolling() {
        thread(start = true) {
            while (true) {
                try {
                    pollNotifications()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Thread.sleep(3000)
            }
        }
    }

    private fun pollNotifications() {
        val sbns = getActiveNotifications()

        val currentActiveKeys = mutableSetOf<String>()

        for (sbn in sbns) {
            if (sbn.packageName != "com.kakao.talk") continue

            val key = sbn.key
            val postTime = sbn.postTime
            currentActiveKeys.add(key)

            val lastProcessedTime = processedNotifications[key]

            if (lastProcessedTime == postTime) {
                continue
            }

            val notification = sbn.notification
            val extras = notification.extras ?: continue

            val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

            if (rawTitle == null && rawText == null) {
                processedNotifications[key] = postTime
                continue
            }

            val senderName = rawTitle ?: ""
            val subText = extras.getString(Notification.EXTRA_SUB_TEXT)
            val summaryText = extras.getString(Notification.EXTRA_SUMMARY_TEXT)
            val room = subText ?: summaryText ?: senderName

            // 누적형(MessagingStyle) 알림에서는 EXTRA_TITLE(=최신 발신자 이름)와
            // EXTRA_MESSAGES[0](=가장 오래된 메시지)의 ID가 서로 다른 사람일 수 있다.
            // 이름은 반드시 "같은 메시지 번들"의 person.name 에서 뽑아 짝이 어긋나지
            // 않게 하고, 각 메시지를 개별 저장한다.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                    if (!messages.isNullOrEmpty()) {
                        for (m in messages) {
                            val messageBundle = m as? Bundle ?: continue
                            val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                messageBundle.getParcelable("sender_person", Person::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                messageBundle.getParcelable("sender_person") as? Person
                            } ?: continue

                            val pid = person.key ?: continue
                            // 같은 번들의 이름을 사용 (EXTRA_TITLE 폐기)
                            val pname = person.name?.toString()
                                ?: messageBundle.getCharSequence("sender")?.toString()
                                ?: continue
                            if (pid.isEmpty() || pname.isEmpty()) continue

                            // 캐시엔 마지막으로 저장한 이름을 함께 들고 있어, 이름이
                            // 바뀌면 재저장해 과거 오염을 스스로 교정한다.
                            if (cachedSenderNames[pid] != pname) {
                                NamesDB.saveName(pid, pname, room)
                                cachedSenderNames[pid] = pname
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // pass
            }

            processedNotifications[key] = postTime
        }

        processedNotifications.keys.retainAll(currentActiveKeys)

        if (cachedSenderNames.size > 5000) {
            cachedSenderNames.clear()
        }
    }

    private fun getActiveNotifications(): Array<StatusBarNotification> {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "notification") as IBinder

            val stub = Class.forName("android.app.INotificationManager\$Stub")
            val inpm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

            val methods = inpm.javaClass.methods

            val userId = try {
                val userHandleClass = Class.forName("android.os.UserHandle")
                userHandleClass.getMethod("myUserId").invoke(null) as Int
            } catch (e: Exception) {
                0
            }

            try {
                val getActiveMethod = methods.find {
                    it.name == "getActiveNotifications" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
                }
                if (getActiveMethod != null) {
                    val result = getActiveMethod.invoke(inpm, "com.android.shell")
                    val notifications = extractNotifications(result)
                    if (notifications.isNotEmpty()) return notifications
                }
            } catch (e: Exception) {
                // pass
            }

            try {
                val getAppActiveMethod = methods.find {
                    it.name == "getAppActiveNotifications" && it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
                }
                if (getAppActiveMethod != null) {
                    val result = getAppActiveMethod.invoke(inpm, "com.kakao.talk", userId)
                    val notifications = extractNotifications(result)
                    if (notifications.isNotEmpty()) return notifications
                }
            } catch (e: Exception) {
                // pass
            }

            try {
                val getActiveMethod = methods.find {
                    it.name == "getActiveNotifications" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
                }
                if (getActiveMethod != null) {
                    val result = getActiveMethod.invoke(inpm, "com.kakao.talk")
                    val notifications = extractNotifications(result)
                    if (notifications.isNotEmpty()) return notifications
                }
            } catch (e: Exception) {
                // pass
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyArray()
    }

    private fun extractNotifications(result: Any?): Array<StatusBarNotification> {
        if (result == null) return emptyArray()

        if (result is Array<*>) {
            return result.filterIsInstance<StatusBarNotification>().toTypedArray()
        }

        try {
            val getListMethod = result.javaClass.getMethod("getList")
            val list = getListMethod.invoke(result) as? List<*>
            if (list != null) {
                return list.filterIsInstance<StatusBarNotification>().toTypedArray()
            }
        } catch (e: Exception) {
            // pass
        }

        return emptyArray()
    }
}