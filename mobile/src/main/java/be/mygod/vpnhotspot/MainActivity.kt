package be.mygod.vpnhotspot

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.client.ClientViewModel
import be.mygod.vpnhotspot.client.ClientsFragment
import be.mygod.vpnhotspot.databinding.ActivityMainBinding
import be.mygod.vpnhotspot.manage.TetheringFragment
import be.mygod.vpnhotspot.net.IpNeighbour
import be.mygod.vpnhotspot.net.wifi.WifiDoubleLock
import be.mygod.vpnhotspot.util.ServiceForegroundConnector
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.UpdateChecker
import be.mygod.vpnhotspot.util.ApiKeyManager
import be.mygod.vpnhotspot.util.WebServerManager
import be.mygod.vpnhotspot.widget.SmartSnackbar
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.Inet4Address

class MainActivity : AppCompatActivity(), NavigationBarView.OnItemSelectedListener {
    lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            view.setPadding(tappable.left, tappable.top, tappable.right, 0)
            WindowInsetsCompat.Builder(insets).apply {
                setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, tappable.bottom))
            }.build()
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.navigation.setOnItemSelectedListener(this)
        val badge = binding.navigation.getOrCreateBadge(R.id.navigation_clients).apply {
            backgroundColor = resources.getColor(R.color.colorSecondary, theme)
            badgeTextColor = resources.getColor(androidx.appcompat.R.color.primary_text_default_material_light, theme)
        }
        if (savedInstanceState == null) displayFragment(TetheringFragment())
        val model by viewModels<ClientViewModel>()
        lifecycle.addObserver(model)
        if (Services.p2p != null) ServiceForegroundConnector(this, model, RepeaterService::class)
        model.clients.observe(this) { clients ->
            val count = clients.count {
                it.ip.any { (ip, info) -> ip is Inet4Address && info.state == IpNeighbour.State.VALID }
            }
            badge.isVisible = count > 0
            badge.number = count
        }
        SmartSnackbar.Register(binding.fragmentHolder)
        WifiDoubleLock.ActivityListener(this)
        lifecycleScope.launch { BootReceiver.startIfEnabled() }
        
        // 启动蓝牙网络共享自动启动器
        BluetoothTetheringAutoStarter.getInstance(this).start()
        
        // 启动WiFi热点自动启动器
        WifiTetheringAutoStarter.getInstance(this).start()
        
        // 启动以太网络共享自动启动器（Android 11及以上版本）
        if (Build.VERSION.SDK_INT >= 30) {
            EthernetTetheringAutoStarter.getInstance(this).start()
        }

        // 启动Usb网络共享自动启动器
        UsbTetheringAutoStarter.getInstance(this).start()
        
        // 初始化API Key管理器和WebServer管理器
        ApiKeyManager.init(this)
        WebServerManager.init(this)
        
        // 启动WebServer（默认启动，API Key保护是可选的）
        try {
            WebServerManager.start(this)
            Timber.i("WebServer successfully started on port ${WebServerManager.getPort()}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start WebServer on port ${WebServerManager.getPort()}")
            // 显示错误提示给用户
            SmartSnackbar.make(getString(R.string.webserver_start_failed, WebServerManager.getPort()))
                .show()
        }
        
        lastUpdate = UpdateChecker.check()
        val updateItem = binding.navigation.menu.findItem(R.id.navigation_update)
        updateItem.isCheckable = false
        updateItem.isVisible = lastUpdate != null
        if (lastUpdate == null) {
            updateItem.isEnabled = false
            return
        }
        updateItem.setIcon(R.drawable.ic_action_update)
        updateItem.title = getText(R.string.title_update)
        binding.navigation.getOrCreateBadge(R.id.navigation_update).apply {
            backgroundColor = resources.getColor(R.color.colorSecondary, theme)
            badgeTextColor = resources.getColor(androidx.appcompat.R.color.primary_text_default_material_light, theme)
            isVisible = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 停止WebServer以释放资源
        try {
            if (WebServerManager.isRunning()) {
                Timber.i("Stopping WebServer in MainActivity.onDestroy()")
                WebServerManager.stop()
                Timber.i("WebServer successfully stopped")
            } else {
                Timber.d("WebServer was not running during MainActivity.onDestroy()")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error occurred while stopping WebServer in MainActivity.onDestroy()")
        }
    }

    private var lastUpdate: Uri? = null

    override fun onNavigationItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.navigation_clients -> {
            displayFragment(ClientsFragment())
            true
        }
        R.id.navigation_tethering -> {
            displayFragment(TetheringFragment())
            true
        }
        R.id.navigation_remote_control -> {
            displayFragment(RemoteControlFragment())
            true
        }
        R.id.navigation_settings -> {
            displayFragment(SettingsPreferenceFragment())
            true
        }
        R.id.navigation_update -> {
            app.customTabsIntent.launchUrl(this, lastUpdate!!)
            false
        }
        else -> false
    }

    private fun displayFragment(fragment: Fragment) =
            supportFragmentManager.beginTransaction().replace(R.id.fragmentHolder, fragment).commitAllowingStateLoss()
}
