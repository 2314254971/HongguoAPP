// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile

import android.app.Application
import com.hongguo.mobile.data.AccountManager
import com.hongguo.mobile.data.AppLog
import com.hongguo.mobile.data.ImageLoader
import com.hongguo.mobile.data.LocalLibrary
import com.hongguotv.core.ContentRepository
import okhttp3.OkHttpClient

class App : Application() {

    /** 内容接口与播放共用一个带 Cookie 罐的客户端，CDN 下发的 ttwid 才能自动流转。 */
    lateinit var repo: ContentRepository
        private set

    lateinit var library: LocalLibrary
        private set

    lateinit var account: AccountManager
        private set

    val http: OkHttpClient
        get() = repo.http

    /** 取流优先走登录态：官方接口把匿名设备判成不可信时会返回空包。 */
    val playbackHttp: OkHttpClient by lazy {
        repo.http.newBuilder().addInterceptor { chain ->
            val cookie = account.credentials?.cookie
            val host = chain.request().url.host
            if (!cookie.isNullOrBlank() && (host.endsWith("fqnovel.com") || host.endsWith("snssdk.com"))) {
                chain.proceed(chain.request().newBuilder().header("Cookie", cookie).build())
            } else {
                chain.proceed(chain.request())
            }
        }.build()
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        repo = ContentRepository()
        library = LocalLibrary(this)
        account = AccountManager(this)
        ImageLoader.init(this)
    }
}
