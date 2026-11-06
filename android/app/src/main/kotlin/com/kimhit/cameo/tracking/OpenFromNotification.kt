package com.kimhit.cameo.tracking

/** 完成通知的 PendingIntent 帶這個 extra，MainActivity 看到就切到「我的客串」。 */
internal const val EXTRA_OPEN_MY_CAMEOS = "com.kimhit.cameo.extra.OPEN_MY_CAMEOS"

/** 純判斷：只有「帶 extra、不是從最近使用清單重開、不是 Activity 還原」才跳頁。 */
internal fun shouldOpenMyCameos(hasExtra: Boolean, launchedFromHistory: Boolean, restored: Boolean): Boolean =
    hasExtra && !launchedFromHistory && !restored
