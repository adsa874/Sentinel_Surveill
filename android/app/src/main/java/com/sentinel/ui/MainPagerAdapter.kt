package com.sentinel.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> LivePreviewFragment()
            1 -> ActivityFeedFragment()
            2 -> ControlFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }

    companion object {
        val TAB_TITLES = listOf("Live", "Activity", "Control")
    }
}
