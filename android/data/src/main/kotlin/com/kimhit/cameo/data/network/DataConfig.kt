package com.kimhit.cameo.data.network

/** 由 :app 的 build config 傳入，不在畫面或 repository 寫死。 */
class DataConfig(
    val baseUrl: String,
    val authToken: String,
    /** 只有 debug 版設值，分享時以 `debug_base_url` 帶給後端；release 為 null。 */
    val shareDebugBaseUrl: String? = null,
)
