package com.sentinel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sentinel.databinding.FragmentActivityFeedBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ActivityFeedFragment : Fragment() {

    private var _binding: FragmentActivityFeedBinding? = null
    private val binding get() = _binding!!

    private val feedAdapter = ActivityFeedAdapter()
    private var currentFilter: FilterType = FilterType.ALL

    enum class FilterType {
        ALL, PEOPLE, VEHICLES, ALERTS
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActivityFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilters()
        observeEvents()
        loadInitialEvents()
    }

    private fun setupRecyclerView() {
        binding.rvActivityFeed.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = feedAdapter
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.chipPeople.setOnClickListener { applyFilter(FilterType.PEOPLE) }
        binding.chipVehicles.setOnClickListener { applyFilter(FilterType.VEHICLES) }
        binding.chipAlerts.setOnClickListener { applyFilter(FilterType.ALERTS) }
    }

    private fun applyFilter(filter: FilterType) {
        currentFilter = filter
        val allEvents = DetectionEventManager.getRecentEvents()
        val filtered = filterEvents(allEvents)
        feedAdapter.submitList(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun filterEvents(events: List<ActivityEvent>): List<ActivityEvent> {
        return when (currentFilter) {
            FilterType.ALL -> events
            FilterType.PEOPLE -> events.filter {
                it.label.lowercase().contains("person") ||
                it.type == ActivityEvent.EventType.FACE_RECOGNIZED ||
                it.type == ActivityEvent.EventType.FACE_UNKNOWN
            }
            FilterType.VEHICLES -> events.filter {
                it.label.lowercase().let { label ->
                    label.contains("car") ||
                    label.contains("truck") ||
                    label.contains("vehicle") ||
                    label.contains("motorcycle") ||
                    label.contains("bicycle")
                } || it.type == ActivityEvent.EventType.VEHICLE_PLATE
            }
            FilterType.ALERTS -> events.filter {
                it.type == ActivityEvent.EventType.FACE_UNKNOWN ||
                it.type == ActivityEvent.EventType.LOITERING
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.activityEvents.collectLatest { event ->
                val currentList = feedAdapter.currentList.toMutableList()

                // Check if event passes current filter
                val shouldAdd = when (currentFilter) {
                    FilterType.ALL -> true
                    FilterType.PEOPLE -> event.label.lowercase().contains("person") ||
                            event.type == ActivityEvent.EventType.FACE_RECOGNIZED ||
                            event.type == ActivityEvent.EventType.FACE_UNKNOWN
                    FilterType.VEHICLES -> event.label.lowercase().let { label ->
                        label.contains("car") || label.contains("truck") ||
                        label.contains("vehicle") || label.contains("motorcycle")
                    } || event.type == ActivityEvent.EventType.VEHICLE_PLATE
                    FilterType.ALERTS -> event.type == ActivityEvent.EventType.FACE_UNKNOWN ||
                            event.type == ActivityEvent.EventType.LOITERING
                }

                if (shouldAdd) {
                    currentList.add(0, event)
                    feedAdapter.submitList(currentList)
                    updateEmptyState(false)
                }
            }
        }
    }

    private fun loadInitialEvents() {
        val events = DetectionEventManager.getRecentEvents()
        val filtered = filterEvents(events)
        feedAdapter.submitList(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvActivityFeed.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
