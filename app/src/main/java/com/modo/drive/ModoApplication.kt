package com.modo.drive

import android.app.Application
import com.modo.drive.core.AppContainer
import com.modo.drive.core.EnvironmentFactory

class ModoApplication : Application() {
    /** 테스트에서 교체할 수 있도록 var로 둔다. */
    var environmentFactory: EnvironmentFactory? = null
        get() = field ?: AppContainer(this).also { field = it }
}
