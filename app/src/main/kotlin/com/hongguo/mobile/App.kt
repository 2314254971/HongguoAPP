// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile

import android.app.Application
import com.hongguo.mobile.data.AccountManager
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

    override fun onCreate() {
        super.onCreate()
        repo = ContentRepository()
        library = LocalLibrary(this)
        account = AccountManager(this)
        ImageLoader.init(this)
    }
}
