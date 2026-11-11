package com.yanzhong.app.ui.legal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import kotlinx.coroutines.launch

/**
 * 首启同意门:只负责本地落库。
 *
 * 服务端补报不在这里做——补报要等登录态就绪,由 [YanZhongApp] 的启动联动统一处理,
 * 否则「未登录时同意」这条路径永远补不上,而且会把一次慢请求塞进按钮的 loading。
 */
class ConsentViewModel(app: Application) : AndroidViewModel(app) {
    fun accept(onDone: () -> Unit) {
        viewModelScope.launch {
            getApplication<YanZhongApp>().consentStore.accept()
            onDone()
        }
    }
}
