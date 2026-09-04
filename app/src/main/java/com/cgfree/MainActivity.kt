package com.cgfree

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.cgfree.databinding.ActivityMainBinding
import com.cgfree.ui.AccountFragment
import com.cgfree.ui.ChatFragment
import com.cgfree.ui.ServerFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val fragments = HashMap<Int, Fragment>()
    private var currentId: Int = R.id.navChat

    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = R.id.navChat
        showTab(R.id.navChat)
    }

    private fun showTab(id: Int) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        var f = fragments[id]
        if (f == null) {
            f = when (id) {
                R.id.navServer -> ServerFragment()
                R.id.navAccount -> AccountFragment()
                else -> ChatFragment()
            }
            fragments[id] = f
            tx.add(R.id.container, f, id.toString())
        }
        fragments[currentId]?.let { tx.hide(it) }
        tx.show(f)
        tx.commit()
        currentId = id
    }
}