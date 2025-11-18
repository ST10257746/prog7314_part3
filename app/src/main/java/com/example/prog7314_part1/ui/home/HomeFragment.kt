package com.example.prog7314_part1.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.example.prog7314_part1.R
import com.example.prog7314_part1.data.local.AppDatabase
import com.example.prog7314_part1.data.local.entity.SessionStatus
import com.example.prog7314_part1.data.repository.ApiUserRepository
import com.example.prog7314_part1.data.repository.NetworkRepository
import com.google.firebase.auth.FirebaseAuth
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private lateinit var userRepository: ApiUserRepository
    private lateinit var networkRepository: NetworkRepository
    private lateinit var database: AppDatabase
    private var rootView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        rootView = view

        userRepository = ApiUserRepository(requireContext())
        networkRepository = NetworkRepository(requireContext())
        database = AppDatabase.getDatabase(requireContext())

        setupProfileNavigation(view)
        loadUserData(view)
        loadTodayActivity(view)
        loadWeeklyGoals(view)
        loadGoalsList(view)
        loadWeeklyChart(view)

        return view
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        rootView = null
    }

    private fun setupProfileNavigation(view: View) {
        view.findViewById<View>(R.id.profileIconContainer)?.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_profileFragment)
        }
    }

    private fun loadUserData(view: View) {
        lifecycleScope.launch {
            userRepository.getCurrentUser().collect { user ->
                if (user != null) {
                    // Debug: Log user goals
                    android.util.Log.d("HomeFragment", "📊 User Goals - Steps: ${user.dailyStepGoal}, Calories: ${user.dailyCalorieGoal}, Workouts: ${user.weeklyWorkoutGoal}")
                    
                    // Update greeting with user's name
                    view.findViewById<TextView>(R.id.userGreeting)?.text = user.displayName

                    // Load profile image
                    loadProfileImage(view, user.profileImageUrl)

                    // Show badge if profile is incomplete
                    updateProfileBadge(view, user)
                }
            }
        }
    }
    
    private fun loadTodayActivity(view: View) {
        lifecycleScope.launch {
            userRepository.getCurrentUser().collectLatest { user ->
                if (user != null) {
                    // Calculate today's time range
                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val todayStart = calendar.timeInMillis
                    
                    // Observe database changes continuously
                    database.workoutSessionDao()
                        .getSessionsInTimeRange(user.userId, todayStart, System.currentTimeMillis())
                        .collectLatest { sessions ->
                            val todaySessions = sessions.filter { it.status == SessionStatus.COMPLETED }
                            
                            // Calculate totals from today's workouts
                            val totalSteps = todaySessions.sumOf { it.steps }
                            val totalCalories = todaySessions.sumOf { it.caloriesBurned }
                            val totalDistance = todaySessions.sumOf { it.distanceKm }
                            val totalActiveMinutes = todaySessions.sumOf { it.durationSeconds / 60 }
                            
                            // Update main steps display
                            view.findViewById<TextView>(R.id.mainStepsValue)?.text = 
                                String.format("%,d", totalSteps)
                            
                            // Update supporting stats
                            view.findViewById<TextView>(R.id.caloriesValue)?.text = 
                                String.format("%,d", totalCalories)
                            view.findViewById<TextView>(R.id.activeMinutesValue)?.text = 
                                "$totalActiveMinutes"
                            view.findViewById<TextView>(R.id.distanceValue)?.text = 
                                String.format("%.1f km", totalDistance)
                            
                            // Update progress circles based on goals
                            updateProgressCircles(view, user, totalSteps, totalCalories)
                            
                            // Update weekly target
                            updateWeeklyTarget(view, user)
                            
                            // Update quick stats grid
                            updateQuickStats(view, todaySessions, totalSteps, totalCalories)
                        }
                }
            }
        }
    }
    
    private fun updateProgressCircles(
        view: View, 
        user: com.example.prog7314_part1.data.local.entity.User,
        totalSteps: Int,
        totalCalories: Int
    ) {
        // Steps progress
        user.dailyStepGoal?.let { goal ->
            view.findViewById<TextView>(R.id.stepsProgressText)?.text = 
                String.format("%,d", totalSteps)
            view.findViewById<TextView>(R.id.stepsGoalText)?.text = 
                "/${String.format("%,d", goal)}"
        }
        
        // Calories progress
        user.dailyCalorieGoal?.let { goal ->
            view.findViewById<TextView>(R.id.caloriesProgressText)?.text = 
                String.format("%,d", totalCalories)
            view.findViewById<TextView>(R.id.caloriesGoalText)?.text = 
                "/${String.format("%,d", goal)}"
        }
    }
    
    private fun updateWeeklyTarget(view: View, user: com.example.prog7314_part1.data.local.entity.User) {
        lifecycleScope.launch {
            try {
                // Calculate week start (Monday of current week)
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                
                // Go back to Monday
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
                calendar.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
                val weekStart = calendar.timeInMillis
                
                // Observe database changes continuously for weekly target
                database.workoutSessionDao()
                    .getSessionsInTimeRange(user.userId, weekStart, System.currentTimeMillis())
                    .collectLatest { sessions ->
                        val weekSessions = sessions.filter { it.status == com.example.prog7314_part1.data.local.entity.SessionStatus.COMPLETED }
                        
                        val weeklySteps = weekSessions.sumOf { it.steps }
                        val weeklyGoal = (user.dailyStepGoal ?: 10000) * 7
                        
                        val percentage = if (weeklyGoal > 0) {
                            ((weeklySteps.toFloat() / weeklyGoal) * 100).toInt().coerceIn(0, 100)
                        } else 0
                        
                        view.findViewById<TextView>(R.id.weeklyTargetText)?.text = 
                            "${String.format("%,d", weeklySteps)} of ${String.format("%,d", weeklyGoal)} steps"
                        view.findViewById<TextView>(R.id.weeklyTargetPercentage)?.text = "$percentage%"
                        view.findViewById<android.widget.ProgressBar>(R.id.weeklyTargetProgress)?.progress = percentage
                    }
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "❌ Error updating weekly target: ${e.message}", e)
            }
        }
    }
    
    private fun updateQuickStats(
        view: View,
        sessions: List<com.example.prog7314_part1.data.local.entity.WorkoutSession>,
        totalSteps: Int,
        totalCalories: Int
    ) {
        // Optional: Update additional stats if views exist
        // These views are optional and may not be in all layouts
    }

    /**
     * Load weekly goals checkmarks (check if all goals in Goals To-Do List are completed for each day)
     */
    private fun loadWeeklyGoals(view: View) {
        lifecycleScope.launch {
            try {
                userRepository.getCurrentUser().collectLatest { currentUser ->
                    if (currentUser == null) {
                        android.util.Log.e("HomeFragment", "❌ No current user found")
                        return@collectLatest
                    }
                    
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val calendar = Calendar.getInstance()
                    
                    // Calculate week start (Monday of current week)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    
                    // Go back to Monday (or first day of week)
                    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                    val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
                    calendar.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
                    val weekStart = calendar.timeInMillis
                    
                    android.util.Log.d("HomeFragment", "📅 Week start: ${dateFormat.format(Date(weekStart))}")
                    
                    // Check goal completion for each day of the week
                    val goalsCompletedByDay = mutableMapOf<Int, Boolean>() // Day of week -> all goals completed
                    
                    // Check each day from Monday to Sunday
                    for (dayOffset in 0..6) {
                        val dayCalendar = Calendar.getInstance()
                        dayCalendar.timeInMillis = weekStart
                        dayCalendar.add(Calendar.DAY_OF_MONTH, dayOffset)
                        val dateString = dateFormat.format(Date(dayCalendar.timeInMillis))
                        val dayOfWeek = dayCalendar.get(Calendar.DAY_OF_WEEK)
                        val dayName = dayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
                        
                        // Get sessions for this day
                        val dayStart = dayCalendar.timeInMillis
                        dayCalendar.add(Calendar.DAY_OF_MONTH, 1)
                        val dayEnd = dayCalendar.timeInMillis - 1
                        
                        val daySessions = withContext(Dispatchers.IO) {
                            database.workoutSessionDao()
                                .getSessionsInTimeRange(currentUser.userId, dayStart, dayEnd)
                                .first()
                                .filter { it.status == SessionStatus.COMPLETED }
                        }
                        
                        val daySteps = daySessions.sumOf { it.steps }
                        val dayCalories = daySessions.sumOf { it.caloriesBurned }
                        
                        android.util.Log.d("HomeFragment", "📋 $dayName ($dateString): Found ${daySessions.size} sessions, Steps=$daySteps, Calories=$dayCalories")
                        
                        // Get Goal entities for this day
                        val dayGoals = withContext(Dispatchers.IO) {
                            database.goalDao().getGoalsForDateSuspend(currentUser.userId, dateString)
                        }
                        
                        // Check if all goals are completed
                        var allGoalsCompleted = true
                        
                        // Check step goal
                        currentUser.dailyStepGoal?.let { stepGoal ->
                            if (daySteps < stepGoal) {
                                allGoalsCompleted = false
                            }
                        }
                        
                        // Check calorie goal
                        currentUser.dailyCalorieGoal?.let { calorieGoal ->
                            if (dayCalories < calorieGoal) {
                                allGoalsCompleted = false
                            }
                        }
                        
                        // Check Goal entities
                        if (dayGoals.isNotEmpty()) {
                            val allGoalEntitiesCompleted = dayGoals.all { it.isCompleted }
                            if (!allGoalEntitiesCompleted) {
                                allGoalsCompleted = false
                            }
                        }
                        
                        // If no goals exist for this day, don't show checkmark
                        val hasGoals = (currentUser.dailyStepGoal != null || currentUser.dailyCalorieGoal != null || dayGoals.isNotEmpty())
                        goalsCompletedByDay[dayOfWeek] = hasGoals && allGoalsCompleted
                        
                        android.util.Log.d("HomeFragment", "📋 $dayName: Steps=$daySteps/${currentUser.dailyStepGoal}, Calories=$dayCalories/${currentUser.dailyCalorieGoal}, Goal entities=${dayGoals.size} (${dayGoals.count { it.isCompleted }}/${dayGoals.size} completed), All completed=$allGoalsCompleted")
                    }
                    
                    // Update checkmarks on main thread
                    withContext(Dispatchers.Main) {
                        updateDayCheckmark(view, R.id.mondayCheckmark, goalsCompletedByDay[Calendar.MONDAY] == true)
                        updateDayCheckmark(view, R.id.tuesdayCheckmark, goalsCompletedByDay[Calendar.TUESDAY] == true)
                        updateDayCheckmark(view, R.id.wednesdayCheckmark, goalsCompletedByDay[Calendar.WEDNESDAY] == true)
                        updateDayCheckmark(view, R.id.thursdayCheckmark, goalsCompletedByDay[Calendar.THURSDAY] == true)
                        updateDayCheckmark(view, R.id.fridayCheckmark, goalsCompletedByDay[Calendar.FRIDAY] == true)
                        updateDayCheckmark(view, R.id.saturdayCheckmark, goalsCompletedByDay[Calendar.SATURDAY] == true)
                        updateDayCheckmark(view, R.id.sundayCheckmark, goalsCompletedByDay[Calendar.SUNDAY] == true)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "❌ Error loading weekly goals: ${e.message}", e)
            }
        }
    }
    
    private fun updateDayCheckmark(view: View, checkmarkId: Int, hasWorkout: Boolean) {
        val checkmark = view.findViewById<TextView>(checkmarkId) ?: return
        if (hasWorkout) {
            checkmark.text = "✓"
            checkmark.setTextColor(requireContext().getColor(R.color.Secondary))
            checkmark.setTypeface(null, android.graphics.Typeface.BOLD)
            checkmark.alpha = 1.0f
        } else {
            checkmark.text = "○"
            checkmark.setTextColor(requireContext().getColor(R.color.Text))
            checkmark.setTypeface(null, android.graphics.Typeface.NORMAL)
            checkmark.alpha = 0.5f
        }
    }

    /**
     * Load goals list for today - includes both daily Goal entities and fitness goals from User
     */
    private fun loadGoalsList(view: View) {
        lifecycleScope.launch {
            try {
                userRepository.getCurrentUser().collectLatest { currentUser ->
                    if (currentUser == null) {
                        android.util.Log.e("HomeFragment", "❌ No current user found for goals")
                        return@collectLatest
                    }
                    
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val today = dateFormat.format(Date())
                    
                    android.util.Log.d("HomeFragment", "📋 Loading goals for date: $today, userId: ${currentUser.userId}")
                    
                    // Get daily Goal entities
                    val dailyGoals = withContext(Dispatchers.IO) {
                        database.goalDao().getGoalsForDateSuspend(currentUser.userId, today)
                    }
                    
                    android.util.Log.d("HomeFragment", "📋 Found ${dailyGoals.size} daily goals for today")
                    
                    // Calculate today's time range
                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val todayStart = calendar.timeInMillis
                    
                    // Observe today's sessions to check progress
                    database.workoutSessionDao()
                        .getSessionsInTimeRange(currentUser.userId, todayStart, System.currentTimeMillis())
                        .collectLatest { sessions ->
                            val todaySessions = sessions.filter { it.status == SessionStatus.COMPLETED }
                            val todaySteps = todaySessions.sumOf { it.steps }
                            val todayCalories = todaySessions.sumOf { it.caloriesBurned }
                            
                            // Get daily Goal entities (re-fetch to get latest completion status)
                            val updatedDailyGoals = withContext(Dispatchers.IO) {
                                database.goalDao().getGoalsForDateSuspend(currentUser.userId, today)
                            }
                            
                            val goalsContainer = view.findViewById<android.widget.LinearLayout>(R.id.goalsListContainer)
                            val emptyState = view.findViewById<TextView>(R.id.goalsEmptyState)
                            
                            withContext(Dispatchers.Main) {
                                goalsContainer?.removeAllViews()
                                
                                // Create fitness goals from User's goals
                                val fitnessGoals = mutableListOf<com.example.prog7314_part1.data.local.entity.Goal>()
                                
                                // Step goal
                                currentUser.dailyStepGoal?.let { stepGoal ->
                                    val isCompleted = todaySteps >= stepGoal
                                    fitnessGoals.add(
                                        com.example.prog7314_part1.data.local.entity.Goal(
                                            goalId = -1, // Temporary ID for fitness goals
                                            userId = currentUser.userId,
                                            date = today,
                                            title = getString(R.string.reach_steps_goal, String.format("%,d", stepGoal)),
                                            description = "${String.format("%,d", todaySteps)} / ${String.format("%,d", stepGoal)}",
                                            isCompleted = isCompleted,
                                            completedAt = if (isCompleted) System.currentTimeMillis() else null
                                        )
                                    )
                                }
                                
                                // Calorie goal
                                currentUser.dailyCalorieGoal?.let { calorieGoal ->
                                    val isCompleted = todayCalories >= calorieGoal
                                    fitnessGoals.add(
                                        com.example.prog7314_part1.data.local.entity.Goal(
                                            goalId = -2,
                                            userId = currentUser.userId,
                                            date = today,
                                            title = getString(R.string.reach_calories_goal, String.format("%,d", calorieGoal)),
                                            description = "${String.format("%,d", todayCalories)} / ${String.format("%,d", calorieGoal)}",
                                            isCompleted = isCompleted,
                                            completedAt = if (isCompleted) System.currentTimeMillis() else null
                                        )
                                    )
                                }
                                
                                // Combine fitness goals with daily goals
                                val allGoals = fitnessGoals + updatedDailyGoals
                                
                                if (allGoals.isEmpty()) {
                                    emptyState?.visibility = View.VISIBLE
                                    goalsContainer?.visibility = View.GONE
                                } else {
                                    emptyState?.visibility = View.GONE
                                    goalsContainer?.visibility = View.VISIBLE
                                    
                                    // Add each goal
                                    allGoals.forEach { goal ->
                                        val goalView = createGoalView(goal)
                                        goalsContainer?.addView(goalView)
                                    }
                                }
                                
                                // Setup add goal button (only once)
                                view.findViewById<android.widget.Button>(R.id.addGoalButton)?.setOnClickListener {
                                    // TODO: Navigate to add goal screen or show dialog
                                    android.util.Log.d("HomeFragment", "Add goal clicked")
                                }
                            }
                        }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "❌ Error loading goals list: ${e.message}", e)
            }
        }
    }
    
    private fun createGoalView(goal: com.example.prog7314_part1.data.local.entity.Goal): View {
        val inflater = LayoutInflater.from(requireContext())
        val goalView = inflater.inflate(R.layout.item_home_goal, null)
        
        val checkbox = goalView.findViewById<android.widget.CheckBox>(R.id.goalCheckbox)
        val titleText = goalView.findViewById<TextView>(R.id.goalTitle)
        val statusText = goalView.findViewById<TextView>(R.id.goalStatus)
        
        titleText?.text = goal.title
        checkbox?.isChecked = goal.isCompleted
        
        if (goal.isCompleted) {
            statusText?.text = "✓"
            statusText?.setTextColor(requireContext().getColor(R.color.Secondary))
            statusText?.setTypeface(null, android.graphics.Typeface.BOLD)
            statusText?.alpha = 1.0f
        } else {
            statusText?.text = "○"
            statusText?.setTextColor(requireContext().getColor(R.color.Text))
            statusText?.setTypeface(null, android.graphics.Typeface.NORMAL)
            statusText?.alpha = 0.5f
        }
        
        checkbox?.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                val updatedGoal = goal.copy(isCompleted = isChecked, completedAt = if (isChecked) System.currentTimeMillis() else null)
                database.goalDao().updateGoal(updatedGoal)
                // Refresh the view if still attached
                rootView?.let { 
                    loadGoalsList(it)
                    loadWeeklyGoals(it) // Refresh weekly checkmarks when goal is updated
                }
            }
        }
        
        return goalView
    }

    /**
     * Load weekly progress chart with actual step data using MPAndroidChart
     */
    private fun loadWeeklyChart(view: View) {
        lifecycleScope.launch {
            try {
                userRepository.getCurrentUser().collectLatest { currentUser ->
                    if (currentUser == null) {
                        android.util.Log.e("HomeFragment", "❌ No current user found for chart")
                        return@collectLatest
                    }
                    
                    // Calculate week start (Monday of current week)
                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    
                    // Go back to Monday
                    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                    val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
                    calendar.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
                    val weekStart = calendar.timeInMillis
                    
                    android.util.Log.d("HomeFragment", "📊 Loading chart data from week start: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(weekStart))}")
                    
                    // Observe database changes continuously for chart
                    database.workoutSessionDao()
                        .getSessionsInTimeRange(currentUser.userId, weekStart, System.currentTimeMillis())
                        .collectLatest { sessions ->
                            val weekSessions = sessions.filter { it.status == SessionStatus.COMPLETED }
                            
                            android.util.Log.d("HomeFragment", "📊 Found ${weekSessions.size} sessions for chart")
                            
                            // Group sessions by day of week
                            val dayData = mutableMapOf<Int, Int>() // Day of week -> total steps
                            for (i in Calendar.SUNDAY..Calendar.SATURDAY) {
                                dayData[i] = 0
                            }
                            
                            // Aggregate data by day
                            val chartCalendar = Calendar.getInstance()
                            weekSessions.forEach { session ->
                                chartCalendar.timeInMillis = session.startTime
                                val dayOfWeek = chartCalendar.get(Calendar.DAY_OF_WEEK)
                                dayData[dayOfWeek] = (dayData[dayOfWeek] ?: 0) + session.steps
                                android.util.Log.d("HomeFragment", "📈 Session: ${session.workoutName}, Steps: ${session.steps}, Day: ${chartCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())}")
                            }
                            
                            android.util.Log.d("HomeFragment", "📊 Steps by day: $dayData")
                            
                            // Update chart on main thread
                            updateStepsChart(view, dayData)
                        }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "❌ Error loading weekly chart: ${e.message}", e)
            }
        }
    }
    
    /**
     * Update weekly steps bar chart using MPAndroidChart
     */
    private fun updateStepsChart(view: View, dayData: Map<Int, Int>) {
        val chart = view.findViewById<BarChart>(R.id.weeklyStepsChart) ?: return
        
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        
        // Order: Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday
        val dayOrder = listOf(
            Calendar.SUNDAY to getString(R.string.sunday),
            Calendar.MONDAY to getString(R.string.monday),
            Calendar.TUESDAY to getString(R.string.tuesday),
            Calendar.WEDNESDAY to getString(R.string.wednesday),
            Calendar.THURSDAY to getString(R.string.thursday),
            Calendar.FRIDAY to getString(R.string.friday),
            Calendar.SATURDAY to getString(R.string.saturday)
        )
        
        dayOrder.forEachIndexed { index, (dayOfWeek, label) ->
            val steps = dayData[dayOfWeek] ?: 0
            entries.add(BarEntry(index.toFloat(), steps.toFloat()))
            labels.add(label)
        }
        
        val dataSet = BarDataSet(entries, getString(R.string.steps)).apply {
            color = requireContext().getColor(R.color.Primary)
            valueTextColor = Color.WHITE
            valueTextSize = 10f
        }
        
        val barData = BarData(dataSet)
        chart.data = barData
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setFitBars(true)
        
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.textSize = 10f
        xAxis.textColor = requireContext().getColor(R.color.Text)
        
        chart.axisLeft.setDrawGridLines(false)
        chart.axisRight.isEnabled = false
        chart.axisLeft.textColor = requireContext().getColor(R.color.Text)
        
        chart.invalidate()
    }

    private fun loadProfileImage(view: View, photoUrl: String?) {
        val profileIcon = view.findViewById<ImageView>(R.id.profileIcon) ?: return

        // Try to load from photoUrl first (Google Sign-In profile picture)
        if (!photoUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(photoUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.icon_default_profile)
                .error(R.drawable.icon_default_profile)
                .into(profileIcon)
        } else {
            // Try to load from Firebase Auth (Google profile picture)
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            val googlePhotoUrl = firebaseUser?.photoUrl

            if (googlePhotoUrl != null) {
                Glide.with(this)
                    .load(googlePhotoUrl)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.icon_default_profile)
                    .error(R.drawable.icon_default_profile)
                    .into(profileIcon)
            } else {
                // Use default profile icon
                Glide.with(this)
                    .load(R.drawable.icon_default_profile)
                    .transform(CircleCrop())
                    .into(profileIcon)
            }
        }
    }

    private fun updateProfileBadge(view: View, user: com.example.prog7314_part1.data.local.entity.User) {
        val badge = view.findViewById<View>(R.id.profileBadge) ?: return

        // Show badge if profile is incomplete (age or weight not set)
        val isProfileIncomplete = user.age == 0 || user.weightKg == 0.0

        badge.visibility = if (isProfileIncomplete) View.VISIBLE else View.GONE
    }
}

