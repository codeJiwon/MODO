package com.modo.drive

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.modo.drive.navigation.ModoApp
import com.modo.drive.presentation.JourneyViewModel

class MainActivity : ComponentActivity() {

    private val vm: JourneyViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JourneyViewModel((application as ModoApplication).environmentFactory!!) as T
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.setLocationSharing(enabled = true, permissionGranted = result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ModoApp(vm = vm, onRequestLocationConsent = ::requestLocationConsent)
        }
    }

    /**
     * 위치 공유 동의 → Pleos FUSED_LOCATION(dangerous) 런타임 권한 요청.
     * 기기에 정의되지 않은 권한(Pleos 환경이 아님)은 요청하지 않고, 위치 SDK가 미지원으로 보고한다.
     */
    private fun requestLocationConsent() {
        val needed = LOCATION_PERMISSIONS.filter { permission ->
            val info = runCatching { packageManager.getPermissionInfo(permission, 0) }.getOrNull() ?: return@filter false
            val dangerous = (info.protection and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
            dangerous && ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) vm.setLocationSharing(enabled = true, permissionGranted = true)
        else locationPermission.launch(needed.toTypedArray())
    }

    private companion object {
        val LOCATION_PERMISSIONS = listOf("pleos.car.permission.FUSED_LOCATION")
    }
}
