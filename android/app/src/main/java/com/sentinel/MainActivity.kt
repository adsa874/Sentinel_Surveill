package com.sentinel

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.sentinel.databinding.ActivityMainBinding
import com.sentinel.ui.DetectionEventManager
import com.sentinel.ui.MainPagerAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViewPager()
        observeDetections()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = MainPagerAdapter.TAB_TITLES[position]
        }.attach()

        // Start on Control tab
        binding.viewPager.setCurrentItem(2, false)
    }

    private fun observeDetections() {
        lifecycleScope.launch {
            DetectionEventManager.activeCount.collectLatest { count ->
                if (count > 0) {
                    showPulseIndicator()
                } else {
                    hidePulseIndicator()
                }
            }
        }
    }

    private fun showPulseIndicator() {
        binding.pulseIndicator.visibility = View.VISIBLE

        if (pulseAnimator == null || !pulseAnimator!!.isRunning) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f, 1f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f, 1f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f, 1f)

            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                binding.pulseIndicator, scaleX, scaleY, alpha
            ).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun hidePulseIndicator() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.pulseIndicator.visibility = View.INVISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}
