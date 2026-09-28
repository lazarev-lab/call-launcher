package com.grandpalauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var clock: TextView? = null
    private var clockTapCount = 0
    private var lastClockTap = 0L
    private var selectedSlot = 0
    private var settingsVisible = false
    private var lastCallLaunch = 0L

    private val clockTick = object : Runnable {
        override fun run() {
            clock?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientationPreference()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNeededPermissions()
        importExistingEndpointOnce()
        migrateAutoAnswerPreference()
        migrateWhatsAppContactNames()
        showHome()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        if (!settingsVisible) hideSystemBars()
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (settingsVisible) showHome() else super.onBackPressed()
    }

    private fun showHome() {
        settingsVisible = false
        hideSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(14), dp(26), dp(20))
            background = launcherBackground()
        }
        clock = TextView(this).apply {
            textSize = 54f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(232, 240, 237))
            gravity = Gravity.CENTER
            setOnClickListener { onClockTapped() }
        }
        val date = TextView(this).apply {
            text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }
            textSize = 21f
            setTextColor(Color.rgb(178, 197, 190))
            gravity = Gravity.CENTER
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(clock, LinearLayout.LayoutParams(-1, dp(64)))
            addView(date, LinearLayout.LayoutParams(-1, dp(34)))
        }, LinearLayout.LayoutParams(-1, dp(102)))
        val count = if (isScreenshotDemo()) {
            intent.getIntExtra("demo_tile_count", 2).coerceIn(1, 4)
        } else {
            prefs.getInt("tile_count", 2).coerceIn(1, 4)
        }
        root.addView(contactGrid(count), LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        handler.post(clockTick)
    }

    private fun contactGrid(count: Int): View {
        if (count < 4) return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            repeat(count) { slot ->
                addView(contactCard(slot, count), LinearLayout.LayoutParams(0, -1, 1f).apply {
                    val gap = dp(9)
                    marginStart = if (slot == 0) 0 else gap
                    marginEnd = if (slot == count - 1) 0 else gap
                })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            repeat(2) { rowIndex ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    repeat(2) { columnIndex ->
                        val slot = rowIndex * 2 + columnIndex
                        addView(contactCard(slot, count), LinearLayout.LayoutParams(0, -1, 1f).apply {
                            val gap = dp(8)
                            marginStart = if (columnIndex == 0) 0 else gap
                            marginEnd = if (columnIndex == 1) 0 else gap
                        })
                    }
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                    val gap = dp(7)
                    topMargin = if (rowIndex == 0) 0 else gap
                    bottomMargin = if (rowIndex == 1) 0 else gap
                })
            }
        }
    }

    private fun contactCard(slot: Int, tileCount: Int): View {
        val name = prefs.getString("name_$slot", null)
            ?: if (isScreenshotDemo()) DEMO_NAMES[slot] else null
        val jid = prefs.getString("jid_$slot", null)
            ?: if (isScreenshotDemo()) "demo-$slot" else null
        val photoUri = prefs.getString("photo_$slot", null)
        val configured = !jid.isNullOrBlank()
        val brightTiles = prefs.getBoolean("bright_tile_colors", false)
        val tileTextColor = if (brightTiles) Color.rgb(27, 39, 40) else Color.WHITE
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = rounded(if (brightTiles) BRIGHT_TILE_COLORS[slot] else TILE_COLORS[slot], 24f)
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true
            addView(ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.rgb(35, 74, 65), 1000f)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                if (!photoUri.isNullOrBlank()) runCatching { setImageURI(Uri.parse(photoUri)) }
                else setImageDrawable(initialDrawable(name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "+"))
            }, LinearLayout.LayoutParams(dp(if (tileCount == 2) 225 else if (tileCount == 3) 175 else 125),
                dp(if (tileCount == 2) 225 else if (tileCount == 3) 175 else 125)))
            addView(TextView(this@MainActivity).apply {
                text = name ?: getString(R.string.add_person)
                textSize = if (tileCount == 2) 72f else if (tileCount == 3) 60f else 52f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(tileTextColor)
                setPadding(0, dp(if (tileCount == 4) 8 else 14), 0, dp(4))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                val isAudio = prefs.getString("call_type_$slot", CALL_TYPE_VIDEO) == CALL_TYPE_AUDIO
                text = if (configured) getString(if (isAudio) R.string.audio_call else R.string.video_call)
                    else getString(R.string.configure)
                textSize = if (tileCount == 4) 30f else 36f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (brightTiles) Color.rgb(36, 50, 49)
                    else if (configured) Color.rgb(215, 244, 225) else Color.rgb(218, 224, 222))
            }, LinearLayout.LayoutParams(-1, dp(if (tileCount == 4) 48 else 60)))
            setOnClickListener { if (configured) startCall(slot) else showSettings(slot) }
        }
    }

    private fun initialDrawable(initial: String): android.graphics.drawable.Drawable =
        object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = 90f * resources.displayMetrics.scaledDensity
            }
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawColor(Color.rgb(35, 74, 65))
                canvas.drawText(initial, bounds.exactCenterX(), bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }

    private fun onClockTapped() {
        val now = System.currentTimeMillis()
        if (now - lastClockTap > 3500) clockTapCount = 0
        lastClockTap = now
        if (++clockTapCount >= 5) {
            clockTapCount = 0
            showSettings(0)
        }
    }

    private fun showSettings(initialSlot: Int, restoreScrollY: Int = 0) {
        selectedSlot = initialSlot
        settingsVisible = true
        showSystemBars()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SETTINGS_BACKGROUND)
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(7), dp(22), dp(7))
            background = outlinedRounded(SETTINGS_BACKGROUND, SETTINGS_BORDER, 0f, 1)
            addView(TextView(this@MainActivity).apply {
                text = "‹"
                textSize = 42f
                gravity = Gravity.CENTER
                setTextColor(SETTINGS_SECONDARY_TEXT)
                setOnClickListener { showHome() }
            }, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(settingsTextBlock(
                getString(R.string.settings_title),
                getString(R.string.settings_subtitle),
                31.25f
            ), LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, dp(76)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }

        val generalCard = settingsCard(
            "▦",
            getString(R.string.general_settings),
            getString(R.string.general_settings_description)
        )
        val tileCountSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.tile_count_1),
                    getString(R.string.tile_count_2),
                    getString(R.string.tile_count_3),
                    getString(R.string.tile_count_4)
                )
            )
            setSelection(prefs.getInt("tile_count", 2).coerceIn(1, 4) - 1)
            setPadding(dp(14), 0, dp(10), 0)
            background = outlinedRounded(SETTINGS_SURFACE_DARK, SETTINGS_BORDER, 10f, 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt("tile_count", position + 1).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        generalCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = outlinedRounded(SETTINGS_SURFACE_DARK, SETTINGS_BORDER, 12f, 1)
            addView(settingsTextBlock(
                getString(R.string.tile_count),
                getString(R.string.tile_count_description)
            ), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tileCountSpinner, LinearLayout.LayoutParams(dp(190), dp(52)))
        }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(16) })
        generalCard.addView(settingsToggle(
            getString(R.string.landscape_only),
            getString(R.string.landscape_only_description),
            prefs.getBoolean("landscape_only", true)
        ) { checked ->
            prefs.edit().putBoolean("landscape_only", checked).apply()
            applyOrientationPreference()
        }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })
        generalCard.addView(settingsToggle(
            getString(R.string.bright_tile_colors),
            getString(R.string.bright_tile_colors_description),
            prefs.getBoolean("bright_tile_colors", false)
        ) { checked ->
            prefs.edit().putBoolean("bright_tile_colors", checked).apply()
        }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })
        body.addView(generalCard, LinearLayout.LayoutParams(-1, -2))

        val tileCard = settingsCard(
            "☷",
            getString(R.string.tile_settings),
            getString(R.string.tile_settings_description)
        )
        tileCard.addView(TextView(this).apply {
            text = getString(R.string.choose_tile).uppercase(Locale.getDefault())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SETTINGS_SECONDARY_TEXT)
            setPadding(0, dp(16), 0, dp(8))
        })
        val tileButtons = mutableListOf<Button>()
        val tileContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun renderTile(slot: Int) {
            val savedScrollY = scroll.scrollY
            selectedSlot = slot
            tileButtons.forEachIndexed { index, button ->
                val active = index == selectedSlot
                button.setTextColor(if (active) Color.WHITE else SETTINGS_SECONDARY_TEXT)
                button.background = outlinedRounded(
                    if (active) ACCENT_COLOR else SETTINGS_SURFACE_DARK,
                    if (active) ACCENT_COLOR else SETTINGS_BORDER,
                    10f,
                    1
                )
            }
            tileContent.removeAllViews()
            val currentName = prefs.getString("name_$selectedSlot", "") ?: ""
            val displayName = currentName.ifBlank { getString(R.string.not_configured) }
            val currentWhatsAppName = prefs.getString("whatsapp_name_$selectedSlot", null)
                ?.trim().orEmpty().ifBlank { currentName }
            val whatsAppName = currentWhatsAppName.ifBlank { getString(R.string.not_configured) }
            val currentAction = if (prefs.getString("call_type_$selectedSlot", CALL_TYPE_VIDEO) == CALL_TYPE_AUDIO) {
                getString(R.string.audio_call_option)
            } else {
                getString(R.string.video_call_option)
            }
            tileContent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = outlinedRounded(SETTINGS_SURFACE_DARK, SETTINGS_BORDER, 12f, 1)
                addView(TextView(this@MainActivity).apply {
                    text = currentWhatsAppName.firstOrNull()?.uppercaseChar()?.toString() ?: "+"
                    textSize = 31.25f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = outlinedRounded(ACCENT_DARK, ACCENT_COLOR, 14f, 1)
                }, LinearLayout.LayoutParams(dp(58), dp(58)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, dp(12), 0)
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.whatsapp_contact_label).uppercase(Locale.getDefault())
                        textSize = 13.75f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(ACCENT_COLOR)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = whatsAppName
                        textSize = 26.25f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(SETTINGS_TEXT)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, dp(90)).apply { topMargin = dp(12) })

            tileContent.addView(settingsActionRow(
                getString(R.string.whatsapp_contact_label),
                whatsAppName
            ) { openWhatsAppPicker() }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })

            tileContent.addView(settingsActionRow(
                getString(R.string.display_name_setting),
                displayName
            ) { showRenameDialog() }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })

            tileContent.addView(settingsActionRow(
                getString(R.string.default_action_setting),
                currentAction
            ) { showCallTypeDialog() }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })

            val hasPhoto = !prefs.getString("photo_$selectedSlot", null).isNullOrBlank()
            tileContent.addView(settingsActionRow(
                getString(R.string.contact_photo_setting),
                getString(if (hasPhoto) R.string.status_selected else R.string.status_not_selected)
            ) { openPhotoPicker() }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })

            tileContent.addView(settingsToggle(
                getString(R.string.auto_answer_for_tile),
                getString(R.string.auto_answer_for_tile_description),
                prefs.getBoolean("auto_answer_$selectedSlot", false)
            ) { checked ->
                prefs.edit().putBoolean("auto_answer_$selectedSlot", checked).apply()
                if (checked && !isAutoAnswerServiceEnabled()) {
                    toast(getString(R.string.auto_answer_enable_service))
                }
            }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })

            tileContent.addView(settingsActionRow(
                getString(R.string.test_call),
                getString(R.string.test_call_description)
            ) { startCall(selectedSlot) }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })
            scroll.post { scroll.scrollTo(0, savedScrollY) }
        }

        tileCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = outlinedRounded(SETTINGS_SURFACE_DARK, SETTINGS_BORDER, 12f, 1)
            repeat(4) { slot ->
                val button = Button(this@MainActivity).apply {
                    text = getString(R.string.tile_button, slot + 1)
                    textSize = 20f
                    isAllCaps = false
                    minHeight = 0
                    minWidth = 0
                    setOnClickListener { renderTile(slot) }
                }
                tileButtons.add(button)
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = if (slot == 0) 0 else dp(3)
                    marginEnd = if (slot == 3) 0 else dp(3)
                })
            }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        tileCard.addView(tileContent, LinearLayout.LayoutParams(-1, -2))
        body.addView(tileCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        body.addView(TextView(this).apply {
            text = getString(R.string.developer_info_title)
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(SETTINGS_SECONDARY_TEXT)
            setPadding(dp(16), dp(22), dp(16), dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = getString(R.string.developer_info)
            textSize = 18.75f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(SETTINGS_TEXT)
            setPadding(dp(16), 0, dp(16), dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = getString(R.string.license_info)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(SETTINGS_SECONDARY_TEXT)
            setPadding(dp(16), dp(4), dp(16), dp(2))
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = getString(R.string.github_repository)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ACCENT_COLOR)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(dp(16), 0, dp(16), dp(12))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        renderTile(initialSlot)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply {
            setPadding(dp(22), dp(9), dp(22), dp(9))
            background = outlinedRounded(SETTINGS_BACKGROUND, SETTINGS_BORDER, 0f, 1)
            addView(stitchActionButton(getString(R.string.back_home)) { showHome() },
                LinearLayout.LayoutParams(-1, dp(54)))
        }, LinearLayout.LayoutParams(-1, dp(72)))
        setContentView(root)
        if (restoreScrollY > 0) scroll.post { scroll.scrollTo(0, restoreScrollY) }
    }

    private fun settingsCard(icon: String, title: String, description: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = outlinedRounded(SETTINGS_SURFACE, SETTINGS_BORDER, 16f, 1)
        elevation = dp(3).toFloat()
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(ACCENT_COLOR)
                background = rounded(ACCENT_TRANSLUCENT, 9f)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(settingsTextBlock(title, description).apply {
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun settingsTextBlock(title: String, description: String, titleSize: Float = 20f) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = titleSize
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SETTINGS_TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = description
                textSize = 15f
                setTextColor(SETTINGS_SECONDARY_TEXT)
            })
        }

    private fun settingsToggle(
        title: String,
        description: String,
        checked: Boolean,
        action: (Boolean) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(12), dp(8))
        background = outlinedRounded(SETTINGS_SURFACE_DARK, SETTINGS_BORDER, 12f, 1)
        addView(settingsTextBlock(title, description), LinearLayout.LayoutParams(0, -2, 1f))
        addView(brightSettingsSwitch(title, checked, action),
            LinearLayout.LayoutParams(dp(60), dp(36)))
    }

    private fun brightSettingsSwitch(
        label: String,
        initialValue: Boolean,
        action: (Boolean) -> Unit
    ) = FrameLayout(this).apply {
        var value = initialValue
        isClickable = true
        isFocusable = true
        contentDescription = label
        val thumb = View(this@MainActivity).apply {
            background = rounded(Color.WHITE, 1000f)
            elevation = dp(2).toFloat()
        }
        fun render() {
            background = rounded(if (value) ACCENT_COLOR else SETTINGS_MUTED, 1000f)
            thumb.layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL or if (value) Gravity.END else Gravity.START
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            val stateText = getString(if (value) R.string.status_enabled else R.string.status_disabled)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                stateDescription = stateText
            } else {
                contentDescription = "$label, $stateText"
            }
        }
        addView(thumb)
        setOnClickListener {
            value = !value
            render()
            action(value)
        }
        render()
    }

    private fun settingsActionRow(title: String, description: String, action: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = outlinedRounded(SETTINGS_SURFACE_DARK, SETTINGS_BORDER, 12f, 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(settingsTextBlock(title, description), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(ACCENT_COLOR)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

    private fun stitchActionButton(
        label: String,
        accent: Boolean = false,
        compact: Boolean = false,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        textSize = if (compact) 17.5f else 20f
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        setTextColor(Color.WHITE)
        background = if (accent) {
            outlinedRounded(ACCENT_DARK, ACCENT_COLOR, 11f, 1)
        } else {
            outlinedRounded(SETTINGS_SURFACE, SETTINGS_BORDER, 11f, 1)
        }
        setOnClickListener { action() }
    }

    @Suppress("unused")
    private fun showSettingsLegacy(initialSlot: Int, restoreScrollY: Int = 0) {
        selectedSlot = initialSlot
        settingsVisible = true
        showSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(24))
            setBackgroundColor(SETTINGS_BACKGROUND)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SETTINGS_TEXT)
        }, LinearLayout.LayoutParams(-1, dp(58)))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(TextView(this).apply {
            text = getString(R.string.tile_count)
            textSize = 18f
            setTextColor(SETTINGS_SECONDARY_TEXT)
        }, LinearLayout.LayoutParams(-1, dp(32)))
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.tile_count_1),
                    getString(R.string.tile_count_2),
                    getString(R.string.tile_count_3),
                    getString(R.string.tile_count_4)
                )
            )
            setSelection(prefs.getInt("tile_count", 2).coerceIn(1, 4) - 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt("tile_count", position + 1).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(CheckBox(this).apply {
            text = getString(R.string.landscape_only)
            textSize = 19f
            isChecked = prefs.getBoolean("landscape_only", true)
            setTextColor(SETTINGS_TEXT)
            buttonTintList = ColorStateList.valueOf(ACCENT_COLOR)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("landscape_only", checked).apply()
                applyOrientationPreference()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(CheckBox(this).apply {
            text = getString(R.string.bright_tile_colors)
            textSize = 19f
            isChecked = prefs.getBoolean("bright_tile_colors", false)
            setTextColor(SETTINGS_TEXT)
            buttonTintList = ColorStateList.valueOf(ACCENT_COLOR)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("bright_tile_colors", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(TextView(this).apply {
            text = getString(R.string.choose_tile)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SETTINGS_TEXT)
            setPadding(0, dp(14), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, dp(52)))
        val tileButtons = mutableListOf<Button>()
        val tileSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun renderTileSettings(slot: Int) {
            selectedSlot = slot
            refreshWhatsAppContactName(selectedSlot)
            tileButtons.forEachIndexed { index, button ->
                button.setTextColor(if (index == selectedSlot) Color.WHITE else SETTINGS_TEXT)
                button.backgroundTintList = ColorStateList.valueOf(
                    if (index == selectedSlot) ACCENT_COLOR else SETTINGS_SURFACE
                )
            }
            tileSettings.removeAllViews()
            val currentName = prefs.getString("name_$selectedSlot", "") ?: ""
            tileSettings.addView(TextView(this).apply {
                text = getString(R.string.whatsapp_contact,
                    if (currentName.isBlank()) getString(R.string.not_configured) else currentName)
                textSize = 22f
                setTextColor(SETTINGS_TEXT)
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(48)))
            tileSettings.addView(CheckBox(this).apply {
                text = getString(R.string.auto_answer_for_tile)
                textSize = 19f
                isChecked = prefs.getBoolean("auto_answer_$selectedSlot", false)
                setTextColor(SETTINGS_TEXT)
                buttonTintList = ColorStateList.valueOf(ACCENT_COLOR)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("auto_answer_$selectedSlot", checked).apply()
                    if (checked && !isAutoAnswerServiceEnabled()) {
                        toast(getString(R.string.auto_answer_enable_service))
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
            tileSettings.addView(actionButton(getString(R.string.select_whatsapp)) { openWhatsAppPicker() })
            val currentAction = if (prefs.getString("call_type_$selectedSlot", CALL_TYPE_VIDEO) == CALL_TYPE_AUDIO) {
                getString(R.string.audio_call_option)
            } else {
                getString(R.string.video_call_option)
            }
            tileSettings.addView(actionButton(getString(R.string.default_action_button, currentAction)) {
                showCallTypeDialog()
            })
            tileSettings.addView(actionButton(getString(R.string.change_display_name)) { showRenameDialog() })
            tileSettings.addView(actionButton(getString(R.string.select_photo)) { openPhotoPicker() })
            tileSettings.addView(actionButton(getString(R.string.test_call)) { startCall(selectedSlot) })
            tileSettings.addView(TextView(this).apply {
                text = diagnosticsText(selectedSlot)
                textSize = 17f
                setTextColor(SETTINGS_SECONDARY_TEXT)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = rounded(SETTINGS_SURFACE, 14f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(4) { slot ->
                val button = Button(this@MainActivity).apply {
                    text = getString(R.string.tile_button, slot + 1)
                    textSize = 16f
                    isAllCaps = false
                    setOnClickListener { renderTileSettings(slot) }
                }
                tileButtons.add(button)
                addView(button, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    marginStart = if (slot == 0) 0 else dp(4)
                    marginEnd = if (slot == 3) 0 else dp(4)
                })
            }
        }, LinearLayout.LayoutParams(-1, dp(66)))
        body.addView(tileSettings, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = getString(R.string.developer_info_title)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(SETTINGS_TEXT)
            setPadding(dp(16), dp(24), dp(16), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = getString(R.string.developer_info)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(SETTINGS_SECONDARY_TEXT)
            setPadding(dp(16), dp(24), dp(16), dp(20))
        }, LinearLayout.LayoutParams(-1, -2))
        renderTileSettings(initialSlot)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton(getString(R.string.back_home)) { showHome() }, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
        if (restoreScrollY > 0) {
            scroll.post { scroll.scrollTo(0, restoreScrollY) }
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setTextColor(SETTINGS_TEXT)
        backgroundTintList = ColorStateList.valueOf(SETTINGS_SURFACE)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(8) }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.name_hint)
            setText(prefs.getString("name_$selectedSlot", "") ?: "")
            setTextColor(SETTINGS_TEXT)
            setHintTextColor(SETTINGS_SECONDARY_TEXT)
            textSize = 20f
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_display_name)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                prefs.edit().putString("name_$selectedSlot", name).apply()
                toast(getString(R.string.name_saved))
                showSettings(selectedSlot)
            }
            .show()
    }

    private fun showCallTypeDialog() {
        val options = arrayOf(getString(R.string.audio_call_option), getString(R.string.video_call_option))
        val currentType = prefs.getString("call_type_$selectedSlot", CALL_TYPE_VIDEO)
        AlertDialog.Builder(this)
            .setTitle(R.string.default_action)
            .setSingleChoiceItems(options, if (currentType == CALL_TYPE_AUDIO) 0 else 1) { dialog, which ->
                prefs.edit().putString(
                    "call_type_$selectedSlot",
                    if (which == 0) CALL_TYPE_AUDIO else CALL_TYPE_VIDEO
                ).apply()
                dialog.dismiss()
                showSettings(selectedSlot)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openWhatsAppPicker() {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
            component = ComponentName(WHATSAPP_PACKAGE, WHATSAPP_PICKER)
        }
        runCatching { startActivityForResult(intent, REQUEST_WHATSAPP) }
            .onFailure { toast(getString(R.string.picker_failed)) }
    }

    private fun openPhotoPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, REQUEST_PHOTO)
    }

    @Deprecated("Required for Android 11 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_WHATSAPP -> saveWhatsAppSelection(data)
            REQUEST_PHOTO -> data.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                prefs.edit().putString("photo_$selectedSlot", uri.toString()).apply()
                showSettings(selectedSlot)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveWhatsAppSelection(result: Intent) {
        val shortcut = result.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        val jid = shortcut?.getStringExtra("jid")
        val whatsAppName = shortcut?.getStringExtra("displayname")
            ?: result.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: getString(R.string.contact)
        if (jid.isNullOrBlank()) {
            toast(getString(R.string.missing_jid))
            return
        }
        val callMime = callMime(selectedSlot)
        val previousWhatsAppName = prefs.getString("whatsapp_name_$selectedSlot", null)
        val currentDisplayName = prefs.getString("name_$selectedSlot", null)
        val shouldUpdateDisplayName = currentDisplayName.isNullOrBlank() ||
            previousWhatsAppName.isNullOrBlank() || currentDisplayName == previousWhatsAppName
        val endpointId = createEndpoint(selectedSlot, whatsAppName, jid, callMime)
        prefs.edit().putString("jid_$selectedSlot", jid)
            .putString("whatsapp_name_$selectedSlot", whatsAppName)
            .apply {
                if (shouldUpdateDisplayName) putString("name_$selectedSlot", whatsAppName)
            }
            .putLong("endpoint_$selectedSlot", endpointId)
            .putString("endpoint_mime_$selectedSlot", callMime).apply()
        toast(getString(R.string.contact_selected))
        showSettings(selectedSlot)
    }

    private fun createEndpoint(slot: Int, name: String, jid: String, mime: String): Long {
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return -1L
        removeOwnedEndpoint(slot)
        val rawUri = contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
            put(ContactsContract.RawContacts.SYNC1, "grandpa_launcher_slot_$slot")
        }) ?: return -1L
        val rawId = ContentUris.parseId(rawUri)
        contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        })
        return contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, mime)
            put(ContactsContract.Data.DATA1, jid)
            put(ContactsContract.Data.DATA2, "Call Launcher")
        })?.let(ContentUris::parseId) ?: -1L
    }

    private fun removeOwnedEndpoint(slot: Int) {
        contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts.SYNC1}=?", arrayOf("grandpa_launcher_slot_$slot"))
    }

    private fun startCall(slot: Int) {
        val now = System.currentTimeMillis()
        if (now - lastCallLaunch < 2500) return
        lastCallLaunch = now
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            toast(getString(R.string.allow_calls))
            return
        }
        val jid = prefs.getString("jid_$slot", null)
        if (jid.isNullOrBlank()) {
            toast(getString(R.string.select_contact_first))
            return
        }
        val mime = callMime(slot)
        var endpointId = prefs.getLong("endpoint_$slot", -1L)
        if (!endpointExists(endpointId, jid, mime)) {
            endpointId = createEndpoint(slot, whatsAppContactName(slot), jid, mime)
            prefs.edit().putLong("endpoint_$slot", endpointId).putString("endpoint_mime_$slot", mime).apply()
        }
        if (endpointId <= 0) {
            toast(getString(R.string.endpoint_failed))
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, endpointId), mime)
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) }.onFailure { toast(getString(R.string.call_failed)) }
    }

    private fun endpointExists(id: Long, jid: String, mime: String): Boolean {
        if (id <= 0 || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return contentResolver.query(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id),
            arrayOf(ContactsContract.Data.DATA1, ContactsContract.Data.MIMETYPE), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == jid && it.getString(1) == mime
        } == true
    }

    private fun callMime(slot: Int): String =
        if (prefs.getString("call_type_$slot", CALL_TYPE_VIDEO) == CALL_TYPE_AUDIO) AUDIO_MIME else VIDEO_MIME

    private fun whatsAppContactName(slot: Int): String =
        (prefs.getString("whatsapp_name_$slot", null) ?: prefs.getString("name_$slot", null))
            ?.trim()?.takeIf(String::isNotEmpty) ?: getString(R.string.contact)

    private fun importExistingEndpointOnce() {
        if (prefs.getBoolean("import_done", false)) return
        prefs.edit().putBoolean("import_done", true).apply()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1, ContactsContract.Data.DISPLAY_NAME),
            "${ContactsContract.Data.MIMETYPE}=?", arrayOf(VIDEO_MIME), "${ContactsContract.Data._ID} ASC")?.use {
            if (it.moveToFirst()) prefs.edit().putLong("endpoint_0", it.getLong(0))
                .putString("jid_0", it.getString(1))
                .putString("name_0", it.getString(2) ?: getString(R.string.contact))
                .putString("whatsapp_name_0", it.getString(2) ?: getString(R.string.contact))
                .putString("endpoint_mime_0", VIDEO_MIME).apply()
        }
    }

    private fun migrateWhatsAppContactNames() {
        repeat(4) { slot ->
            if (!refreshWhatsAppContactName(slot) && !prefs.contains("whatsapp_name_$slot") &&
                !prefs.getString("jid_$slot", null).isNullOrBlank()) {
                val existingName = prefs.getString("name_$slot", null)?.trim()
                if (!existingName.isNullOrEmpty()) {
                    prefs.edit().putString("whatsapp_name_$slot", existingName).apply()
                }
            }
        }
    }

    private fun refreshWhatsAppContactName(slot: Int): Boolean {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val endpointId = prefs.getLong("endpoint_$slot", -1L)
        val jid = prefs.getString("jid_$slot", null)?.trim()
        if (endpointId <= 0 || jid.isNullOrEmpty()) return false
        val contactName = contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, endpointId),
            arrayOf(
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.MIMETYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(1) != jid ||
                cursor.getString(2)?.contains("whatsapp", ignoreCase = true) != true) null
            else cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty)
        } ?: return false
        if (prefs.getString("whatsapp_name_$slot", null) != contactName) {
            prefs.edit().putString("whatsapp_name_$slot", contactName).apply()
        }
        return true
    }

    private fun diagnosticsText(slot: Int): String {
        val whatsapp = packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE) != null
        val jid = prefs.getString("jid_$slot", null)
        val endpoint = prefs.getLong("endpoint_$slot", -1L)
        val mime = callMime(slot)
        val isHome = if (android.os.Build.VERSION.SDK_INT >= 29)
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true else false
        return listOf(
            getString(R.string.diagnostic_whatsapp,
                getString(if (whatsapp) R.string.status_installed else R.string.status_not_found)),
            getString(R.string.diagnostic_contact,
                getString(if (jid.isNullOrBlank()) R.string.status_not_selected else R.string.status_selected)),
            getString(R.string.diagnostic_endpoint, getString(
                if (!jid.isNullOrBlank() && endpointExists(endpoint, jid, mime)) R.string.status_ready else R.string.status_not_ready)),
            getString(R.string.diagnostic_home,
                getString(if (isHome) R.string.status_yes else R.string.status_no)),
            getString(R.string.diagnostic_camera, permissionState(Manifest.permission.CAMERA)),
            getString(R.string.diagnostic_microphone, permissionState(Manifest.permission.RECORD_AUDIO)),
            getString(R.string.diagnostic_auto_answer, getString(
                if (prefs.getBoolean("auto_answer_$slot", false)) R.string.status_enabled else R.string.status_disabled)),
            getString(R.string.diagnostic_auto_service, getString(
                if (isAutoAnswerServiceEnabled()) R.string.status_active else R.string.status_inactive))
        ).joinToString("\n")
    }

    private fun isAutoAnswerServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any {
            it.equals("$packageName/$packageName.AutoAnswerService", ignoreCase = true) ||
                it.equals("$packageName/.AutoAnswerService", ignoreCase = true)
        }
    }

    private fun migrateAutoAnswerPreference() {
        if (prefs.getBoolean("auto_answer_per_tile_migrated", false)) return
        val wasEnabled = prefs.getBoolean("auto_answer", false)
        prefs.edit()
            .putBoolean("auto_answer_0", wasEnabled)
            .putBoolean("auto_answer_per_tile_migrated", true)
            .remove("auto_answer")
            .apply()
    }

    private fun permissionState(permission: String) =
        getString(if (packageManager.checkPermission(permission, WHATSAPP_PACKAGE) == PackageManager.PERMISSION_GRANTED)
            R.string.status_allowed else R.string.status_denied)

    private fun applyOrientationPreference() {
        requestedOrientation = if (prefs.getBoolean("landscape_only", true)) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun requestNeededPermissions() {
        val needed = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 77)
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun showSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = SETTINGS_BACKGROUND
        window.navigationBarColor = SETTINGS_BACKGROUND
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun outlinedRounded(color: Int, strokeColor: Int, radiusDp: Float, strokeDp: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setStroke(dp(strokeDp), strokeColor)
        }

    private fun launcherBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.rgb(49, 53, 55),
            Color.rgb(18, 29, 32),
            Color.rgb(9, 50, 47),
            Color.rgb(43, 83, 59)
        )
    )

    private fun isScreenshotDemo(): Boolean =
        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("screenshot_demo", false)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_PICKER = "com.whatsapp.contact.ui.picker.ContactPicker"
        private const val VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
        private const val AUDIO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        private const val CALL_TYPE_VIDEO = "video"
        private const val CALL_TYPE_AUDIO = "audio"
        private const val GITHUB_URL = "https://github.com/lazarev-lab/call-launcher"
        private const val REQUEST_WHATSAPP = 1001
        private const val REQUEST_PHOTO = 1002
        private val TILE_COLORS = intArrayOf(
            Color.rgb(49, 91, 86),
            Color.rgb(62, 79, 96),
            Color.rgb(88, 94, 65),
            Color.rgb(96, 65, 74)
        )
        private val BRIGHT_TILE_COLORS = intArrayOf(
            Color.rgb(255, 242, 130),
            Color.rgb(134, 195, 234),
            Color.rgb(139, 226, 143),
            Color.rgb(188, 126, 232)
        )
        private val DEMO_NAMES = arrayOf("Anna", "Michael", "Sophie", "Daniel")
        private val SETTINGS_BACKGROUND = Color.rgb(11, 19, 41)
        private val SETTINGS_SURFACE = Color.rgb(30, 41, 59)
        private val SETTINGS_SURFACE_DARK = Color.rgb(18, 29, 52)
        private val SETTINGS_BORDER = Color.rgb(51, 65, 85)
        private val SETTINGS_MUTED = Color.rgb(71, 85, 105)
        private val SETTINGS_TEXT = Color.rgb(248, 250, 252)
        private val SETTINGS_SECONDARY_TEXT = Color.rgb(148, 163, 184)
        private val ACCENT_COLOR = Color.rgb(16, 185, 129)
        private val ACCENT_DARK = Color.rgb(5, 150, 105)
        private val ACCENT_TRANSLUCENT = Color.rgb(20, 70, 72)
    }
}
