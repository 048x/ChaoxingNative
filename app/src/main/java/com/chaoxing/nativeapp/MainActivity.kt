package com.chaoxing.nativeapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chaoxing.nativeapp.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val api = ChaoxingApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val userPrefs by lazy { getSharedPreferences("cx_user", Context.MODE_PRIVATE) }

    private var adapter: CourseAdapter? = null
    private var allCourses: List<Course> = emptyList()
    private val selected = mutableSetOf<String>()

    private val logHandler = Handler(Looper.getMainLooper())
    private var logFlushScheduled = false
    private val logListener: () -> Unit = { scheduleLogFlush() }

    private var screen = SCREEN_LOGIN

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝仅导致不显示通知，任务仍可运行 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(applicationContext)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupUi()
        restoreAccount()
        observeWork()

        Logx.addListener(logListener)

        if (StudyBus.isRunning) showScreen(SCREEN_RUN)
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------
    private fun setupUi() {
        b.rvCourses.layoutManager = LinearLayoutManager(this)

        b.sbSpeed.max = 10
        b.sbSpeed.progress = userPrefs.getInt("speed", 10).coerceIn(0, 10)
        b.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                b.tvSpeed.text = String.format(Locale.CHINA, "%.1fx", 1.0 + progress / 10.0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        b.tvSpeed.text = String.format(Locale.CHINA, "%.1fx", 1.0 + b.sbSpeed.progress / 10.0)

        b.sbJobs.max = 7
        b.sbJobs.progress = userPrefs.getInt("jobs", 3).coerceIn(0, 7)
        b.sbJobs.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                b.tvJobs.text = (progress + 1).toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        b.tvJobs.text = (b.sbJobs.progress + 1).toString()

        b.btnLogin.setOnClickListener { doLogin() }
        b.tvClearAccount.setOnClickListener { clearAccount() }
        b.tvLogout.setOnClickListener { logout() }
        b.btnStart.setOnClickListener { startStudy() }
        b.btnStop.setOnClickListener { stopStudy() }
        b.tvBack.setOnClickListener { showScreen(SCREEN_COURSES) }

        b.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter?.selectAll(checked)
            syncSelectAll()
        }
    }

    /** 已保存账号时回填，密码框以掩码显示，可直接点登录。 */
    private fun restoreAccount() {
        val username = userPrefs.getString("username", null).orEmpty()
        val password = userPrefs.getString("password", null).orEmpty()

        if (username.isNotEmpty()) {
            b.etUsername.setText(username)
            b.etUsername.setSelection(b.etUsername.text.length)
        }
        if (password.isNotEmpty()) {
            b.etPassword.setText(password)
            b.etPassword.setSelection(b.etPassword.text.length)
        }

        b.tvClearAccount.visibility =
            if (username.isEmpty() && password.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun doLogin() {
        val username = b.etUsername.text.toString().trim()
        val password = b.etPassword.text.toString()

        if (username.isEmpty()) {
            b.tvLoginMsg.text = "请输入账号"
            return
        }
        if (password.isEmpty()) {
            b.tvLoginMsg.text = "请输入密码"
            return
        }

        b.btnLogin.isEnabled = false
        b.tvLoginMsg.text = "登录中…"

        scope.launch {
            try {
                val result = api.login(username, password)
                if (result.success) {
                    userPrefs.edit()
                        .putString("username", username)
                        .putString("password", password)
                        .apply()
                    b.tvClearAccount.visibility = View.VISIBLE
                    b.tvLoginMsg.text = ""
                    loadCourses()
                } else {
                    b.tvLoginMsg.text = "登录失败: ${result.message}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.tvLoginMsg.text = "网络错误: ${e.message}"
                Logx.error("登录异常: ${e.message}")
            } finally {
                b.btnLogin.isEnabled = true
            }
        }
    }

    private fun loadCourses() {
        showScreen(SCREEN_COURSES)
        b.tvCourseLoading.visibility = View.VISIBLE
        b.tvCourseLoading.text = getString(R.string.loading_courses)
        b.rvCourses.visibility = View.GONE

        scope.launch {
            try {
                val list = api.getCourseList().distinctBy { it.courseId }
                allCourses = list

                selected.clear()
                selected.addAll(list.map { it.courseId })

                adapter = CourseAdapter(list, selected) { syncSelectAll() }
                b.rvCourses.adapter = adapter

                val empty = list.isEmpty()
                b.rvCourses.visibility = if (empty) View.GONE else View.VISIBLE
                b.tvCourseLoading.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) b.tvCourseLoading.text = getString(R.string.no_courses)

                syncSelectAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.tvCourseLoading.visibility = View.VISIBLE
                b.tvCourseLoading.text = "读取课程失败: ${e.message}"
                Logx.error("读取课程失败: ${e.message}")
            }
        }
    }

    private fun syncSelectAll() {
        val total = allCourses.size
        b.cbSelectAll.setOnCheckedChangeListener(null)
        b.cbSelectAll.isChecked = total > 0 && selected.size == total
        b.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter?.selectAll(checked)
            syncSelectAll()
        }
    }

    private fun clearAccount() {
        userPrefs.edit().clear().apply()
        b.etUsername.setText("")
        b.etPassword.setText("")
        b.tvLoginMsg.text = ""
        b.tvClearAccount.visibility = View.GONE
        Toast.makeText(this, "已清除保存的账号", Toast.LENGTH_SHORT).show()
    }

    private fun logout() {
        WorkManager.getInstance(this).cancelUniqueWork(StudyWorker.WORK_NAME)
        Net.clearSession()

        allCourses = emptyList()
        selected.clear()
        adapter = null
        b.rvCourses.adapter = null

        showScreen(SCREEN_LOGIN)
        b.tvLoginMsg.text = ""
    }

    private fun startStudy() {
        // 与原交互一致：未勾选任何课程时学习全部课程
        val chosen = adapter?.selectedCourses().orEmpty().ifEmpty { allCourses }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "课程列表为空，请先登录读取课程", Toast.LENGTH_SHORT).show()
            return
        }

        val speed = 1.0 + b.sbSpeed.progress / 10.0
        val jobs = b.sbJobs.progress + 1
        val notOpenAction = if (b.swNotOpen.isChecked) "retry" else "continue"

        StudyRepo.save(this, speed, jobs, notOpenAction, chosen)
        userPrefs.edit()
            .putInt("speed", b.sbSpeed.progress)
            .putInt("jobs", b.sbJobs.progress)
            .apply()

        requestNotificationPermission()
        Logx.clear()

        WorkManager.getInstance(this).enqueueUniqueWork(
            StudyWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<StudyWorker>().build()
        )

        b.tvRunState.text = getString(R.string.state_running)
        showScreen(SCREEN_RUN)
    }

    private fun stopStudy() {
        WorkManager.getInstance(this).cancelUniqueWork(StudyWorker.WORK_NAME)
        b.tvRunState.text = getString(R.string.state_stopped)
        Logx.warn("已请求停止任务")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun observeWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(StudyWorker.WORK_NAME)
            .observe(this) { infos ->
                val info = infos?.lastOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED -> b.tvRunState.text = getString(R.string.state_running)

                    WorkInfo.State.SUCCEEDED ->
                        b.tvRunState.text = getString(R.string.state_done)

                    WorkInfo.State.FAILED ->
                        b.tvRunState.text = getString(R.string.state_error)

                    WorkInfo.State.CANCELLED ->
                        b.tvRunState.text = getString(R.string.state_stopped)
                }
            }
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------
    private fun scheduleLogFlush() {
        if (logFlushScheduled) return
        logFlushScheduled = true
        logHandler.postDelayed({
            logFlushScheduled = false
            renderLogs()
        }, 250)
    }

    private fun renderLogs() {
        if (screen != SCREEN_RUN) return
        b.tvLogs.text = Logx.snapshot()
        b.tvLogCount.text = "${Logx.lineCount} 行"
        b.svLogs.post { b.svLogs.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // 屏幕切换
    // ------------------------------------------------------------------
    private fun showScreen(target: Int) {
        screen = target
        b.screenLogin.visibility = if (target == SCREEN_LOGIN) View.VISIBLE else View.GONE
        b.screenCourses.visibility = if (target == SCREEN_COURSES) View.VISIBLE else View.GONE
        b.screenRun.visibility = if (target == SCREEN_RUN) View.VISIBLE else View.GONE
        if (target == SCREEN_RUN) renderLogs()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == SCREEN_RUN) {
            showScreen(SCREEN_COURSES)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        Logx.removeListener(logListener)
        logHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SCREEN_LOGIN = 0
        private const val SCREEN_COURSES = 1
        private const val SCREEN_RUN = 2
    }
}
