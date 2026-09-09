package com.example.travianfarmassistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class FarmList(val id: String, val name: String)

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var farmContainer: LinearLayout
    private lateinit var farmStatus: TextView
    private lateinit var status: TextView
    private lateinit var lastRun: TextView
    private lateinit var nextRun: TextView
    private lateinit var serverInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var nextAt = 0L
    private var intervalMs = 5 * 60_000L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scheduler = object : Runnable {
        override fun run() {
            if (!running) return
            executeSelectedFarmLists()
            nextAt = System.currentTimeMillis() + intervalMs
            updateNextRun()
            handler.postDelayed(this, intervalMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverInput = findViewById(R.id.server)
        val username = findViewById<EditText>(R.id.username)
        val password = findViewById<EditText>(R.id.password)
        farmContainer = findViewById(R.id.farmListContainer)
        farmStatus = findViewById(R.id.farmListStatus)
        status = findViewById(R.id.status)
        lastRun = findViewById(R.id.lastRun)
        nextRun = findViewById(R.id.nextRun)
        webView = findViewById(R.id.webView)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        serverInput.setText(prefs.getString("server", "https://ts20.x2.europe.travian.com"))
        username.setText(prefs.getString("username", ""))

        val interval = findViewById<Spinner>(R.id.interval)
        interval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("5 menit", "10 menit", "15 menit", "30 menit", "60 menit"))
        val duration = findViewById<Spinner>(R.id.duration)
        duration.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1 jam", "6 jam", "12 jam", "24 jam"))

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.addJavascriptInterface(FarmBridge(), "AndroidFarm")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                if (url.contains("gid=16") && url.contains("tt=99")) {
                    farmStatus.text = "Membaca Farm List akun..."
                    extractFarmLists()
                }
            }
        }

        findViewById<Button>(R.id.loginTravian).setOnClickListener {
            val server = normalizeServer(serverInput.text.toString())
            prefs.edit().putString("server", server).putString("username", username.text.toString()).apply()
            farmStatus.text = "Login di halaman Travian. Setelah login, Farm List akan dibaca otomatis."
            webView.loadUrl(server)
        }

        findViewById<Button>(R.id.openFarm).setOnClickListener { openFarmList() }

        findViewById<Button>(R.id.start).setOnClickListener {
            saveSelectedFarmLists()
            intervalMs = when (interval.selectedItemPosition) {
                0 -> 5 * 60_000L; 1 -> 10 * 60_000L; 2 -> 15 * 60_000L; 3 -> 30 * 60_000L; else -> 60 * 60_000L
            }
            running = true
            status.text = "Status: RUNNING"
            executeSelectedFarmLists()
            nextAt = System.currentTimeMillis() + intervalMs
            updateNextRun()
            handler.removeCallbacks(scheduler)
            handler.postDelayed(scheduler, intervalMs)
        }

        findViewById<Button>(R.id.stop).setOnClickListener { stopScheduler() }

        createNotificationChannel()
    }

    private fun openFarmList() {
        webView.loadUrl("${normalizeServer(serverInput.text.toString())}/build.php?gid=16&tt=99")
    }

    private fun extractFarmLists() {
        val js = """
            (() => {
              const out=[]; const seen=new Set();
              const add=(id,name)=>{name=(name||'').trim(); if(!name)return; const k=(id||'')+'|'+name; if(seen.has(k))return; seen.add(k); out.push({id:id||'',name});};
              document.querySelectorAll('.raidList,[class*="raidList"],[data-list-id]').forEach(el=>{
                const id=el.getAttribute('data-list-id')||el.getAttribute('data-id')||el.id||'';
                const t=el.querySelector('.raidListTitle,.raidListTitleText,.farmListTitle,h3,h4,.title');
                if(t)add(id,t.innerText);
              });
              document.querySelectorAll('[data-list-id]').forEach(el=>{
                const id=el.getAttribute('data-list-id')||'';
                const p=el.closest('.raidList,[class*="raidList"]');
                const t=p&&p.querySelector('.raidListTitle,.raidListTitleText,.farmListTitle,h3,h4,.title');
                if(t)add(id,t.innerText);
              });
              AndroidFarm.onFarmLists(JSON.stringify(out));
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun executeSelectedFarmLists() {
        val selected = mutableListOf<FarmList>()
        for (i in 0 until farmContainer.childCount) {
            val v = farmContainer.getChildAt(i)
            if (v is CheckBox && v.isChecked) selected += FarmList(v.tag?.toString().orEmpty(), v.text.toString())
        }
        if (selected.isEmpty()) {
            status.text = "Status: RUNNING — tidak ada Farm List dipilih"
            lastRun.text = "Last run: ${timeFormat.format(Date())}"
            return
        }
        lastRun.text = "Last run: ${timeFormat.format(Date())}"
        selected.forEach { list ->
            val id = list.id.replace("'", "\\'")
            val js = """
                (() => {
                  const root=document.querySelector('[data-list-id="$id"]') || document.getElementById('$id');
                  const all=[...(root?root.querySelectorAll('button,input[type=submit],a'):[])];
                  const btn=all.find(x=>/send all|send|raid/i.test((x.innerText||x.value||x.title||'')));
                  if(btn){btn.click(); return 'clicked';}
                  return 'not-found';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    private fun saveSelectedFarmLists() {
        val selected = buildSet { for (i in 0 until farmContainer.childCount) { val v=farmContainer.getChildAt(i); if(v is CheckBox && v.isChecked) add(v.tag?.toString().orEmpty()) } }
        getSharedPreferences("config",0).edit().putStringSet("selectedFarmLists", selected).apply()
    }

    private fun updateNextRun() { nextRun.text = "Next run: ${timeFormat.format(Date(nextAt))}" }
    private fun stopScheduler() { running=false; handler.removeCallbacks(scheduler); status.text="Status: STOPPED"; nextRun.text="Next run: --" }

    private fun normalizeServer(value: String): String {
        var s=value.trim(); if(s.isBlank()) s="https://ts20.x2.europe.travian.com"; if(!s.startsWith("http")) s="https://$s"; return s.trimEnd('/')
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("farm", "Farm reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    inner class FarmBridge {
        @JavascriptInterface fun onFarmLists(json: String) {
            runOnUiThread {
                try {
                    val arr=JSONArray(json); farmContainer.removeAllViews()
                    val saved=getSharedPreferences("config",0).getStringSet("selectedFarmLists", emptySet()) ?: emptySet()
                    for(i in 0 until arr.length()) {
                        val o=arr.getJSONObject(i); val id=o.optString("id"); val name=o.optString("name")
                        val cb=CheckBox(this@MainActivity); cb.text=name; cb.tag=id; cb.isChecked=saved.isEmpty() || saved.contains(id); farmContainer.addView(cb)
                    }
                    farmStatus.text="${farmContainer.childCount} Farm List ditemukan"
                } catch(e:Exception) { farmStatus.text="Gagal membaca Farm List: ${e.message}" }
            }
        }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
