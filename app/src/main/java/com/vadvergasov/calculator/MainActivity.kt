package com.vadvergasov.calculator

import android.animation.LayoutTransition
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues.TAG
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.sothree.slidinguppanel.PanelSlideListener
import com.sothree.slidinguppanel.PanelState
import com.vadvergasov.calculator.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormatSymbols
import java.util.UUID
import kotlin.math.sqrt
import androidx.core.content.edit


var currentTheme: Int = 0

class MainActivity : AppCompatActivity() {
    object HistoryParams {
        const val KEY_HISTORY = "vadvergasov.calculator.HISTORY"
        const val KEY_HISTORY_SIZE = "vadvergasov.calculator.HISTORY_SIZE"
    }

    private lateinit var view: View

    private val decimalSeparatorSymbol =
        DecimalFormatSymbols.getInstance().decimalSeparator.toString()
    private val groupingSeparatorSymbol =
        DecimalFormatSymbols.getInstance().groupingSeparator.toString()

    private var preferences: SharedPreferences? = null

    private var history: String?
        set(value) = preferences!!.edit { putString(HistoryParams.KEY_HISTORY, value) }
        get() = preferences?.getString(HistoryParams.KEY_HISTORY, null)

    private var historySize: String?
        set(value) = preferences!!.edit { putString(HistoryParams.KEY_HISTORY_SIZE, value) }
        get() = preferences?.getString(HistoryParams.KEY_HISTORY_SIZE, null)

    private var isInvButtonClicked = false
    private var isEqualLastAction = false
    private var isDegreeModeActivated = true
    private var errorStatusOld = false

    private var calculationResult = BigDecimal.ZERO

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var historyLayoutMgr: LinearLayoutManager

    private var sensorManager: SensorManager? = null
    private var acceleration = 0f
    private var currentAcceleration = 0f
    private var lastAcceleration = 0f

    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private lateinit var auth: FirebaseAuth
    private val db = Firebase.firestore

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                applicationContext,
                getString(R.string.no_notifications),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkBiometricInDevice(): Boolean {
        val biometricManager = BiometricManager.from(this)

        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                true
            }

            else -> {
                false
            }
        }
    }

    private val sensorListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {

            // Fetching x,y,z values
            val x = event.values[0]
            val y = event.values[1]
            lastAcceleration = currentAcceleration

            // Getting current accelerations
            currentAcceleration = sqrt((x * x + y * y).toDouble()).toFloat()
            val delta: Float = currentAcceleration - lastAcceleration
            acceleration = acceleration * 0.8f + delta

            if (acceleration > 8) {
                binding.input.setText("")
                binding.resultDisplay.text = ""
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        history = preferences?.getString(HistoryParams.KEY_HISTORY, null)
        historySize = preferences?.getString(HistoryParams.KEY_HISTORY_SIZE, "100")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        askPermissions()

        FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@OnCompleteListener
            }

            val token = task.result

            val msg = getString(R.string.msg_token_fmt, token)
            Log.e(TAG, msg)
        })

        firebaseAnalytics = Firebase.analytics
        auth = Firebase.auth

        super.onCreate(savedInstanceState)

        // Themes
        val themes = Themes()
        setTheme(themes.getTheme())

        currentTheme = themes.getTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        view = binding.root
        setContentView(view)

        // Disable the keyboard on display EditText
        binding.input.showSoftInputOnFocus = false

        binding.backspaceButton.setOnLongClickListener {
            binding.input.setText("")
            binding.resultDisplay.text = ""
            true
        }

        val lt = LayoutTransition()
        lt.disableTransitionType(LayoutTransition.DISAPPEARING)
        binding.tableLayout.layoutTransition = lt

        // Set decimalSeparator
        binding.pointButton.setImageResource(if (decimalSeparatorSymbol == ",") R.drawable.comma else R.drawable.dot)

        // Set history
        historyLayoutMgr = LinearLayoutManager(
            this,
            LinearLayoutManager.VERTICAL,
            false
        )
        binding.historyRecylcleView.layoutManager = historyLayoutMgr
        historyAdapter = HistoryAdapter(mutableListOf()) { value ->
            run {
                //val valueUpdated = value.replace(".", NumberFormatter.decimalSeparatorSymbol)
                updateDisplay(window.decorView, value)
            }
        }
        binding.historyRecylcleView.adapter = historyAdapter
        // Set values
        val historyList = getHistory()
        historyAdapter.appendHistory(historyList)
        // Scroll to the bottom of the recycle view
        if (historyAdapter.itemCount > 0) {
            binding.historyRecylcleView.scrollToPosition(historyAdapter.itemCount - 1)
        }

        binding.historyRecylcleView.visibility = View.VISIBLE
        binding.slidingLayoutButton.visibility = View.VISIBLE
        binding.slidingLayout.isEnabled = true

        binding.slidingLayout.addPanelSlideListener(object : PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) {
                if (slideOffset == 0f) { // If the panel got collapsed
                    binding.slidingLayout.scrollableView = binding.historyRecylcleView
                }
            }

            override fun onPanelStateChanged(
                panel: View,
                previousState: PanelState,
                newState: PanelState
            ) {
                if (newState == PanelState.ANCHORED) { // To prevent the panel from getting stuck in the middle
                    binding.slidingLayout.setPanelState(PanelState.EXPANDED)
                }
            }
        })

        // Prevent the phone from sleeping (if option enabled)
        view.keepScreenOn = false

        // Focus by default
        binding.input.requestFocus()

        // Makes the input take the whole width of the screen by default
        val screenWidthPX = resources.displayMetrics.widthPixels
        binding.input.minWidth =
            screenWidthPX - (binding.input.paddingRight + binding.input.paddingLeft) // remove the paddingHorizontal

        // Do not clear after equal button if you move the cursor
        binding.input.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    isEqualLastAction = false
                }
                if (!binding.input.isCursorVisible) {
                    binding.input.isCursorVisible = true
                }
            }
        }

        // LongClick on result to copy it
        binding.resultDisplay.setOnLongClickListener {
            when {
                binding.resultDisplay.text.toString() != "" -> {
                    val clipboardManager =
                        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText(
                            R.string.copied_result.toString(),
                            binding.resultDisplay.text
                        )
                    )
                    // Only show a toast for Android 12 and lower.
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                        Toast.makeText(this, R.string.value_copied, Toast.LENGTH_SHORT).show()
                    true
                }

                else -> false
            }
        }

        // Handle changes into input to update resultDisplay
        binding.input.addTextChangedListener(object : TextWatcher {
            private var beforeTextLength = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                beforeTextLength = s?.length ?: 0
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateResultDisplay()
            }

            override fun afterTextChanged(s: Editable?) {
                // Do nothing
            }
        })

    }

    fun openAppMenu(view: View) {
        val popup = PopupMenu(this, view)
        val inflater = popup.menuInflater
        inflater.inflate(R.menu.app_menu, popup.menu)
        popup.show()

        if (auth.currentUser != null) {
            popup.menu.findItem(R.id.app_menu_sign_in_button).setVisible(false)
        } else {
            popup.menu.findItem(R.id.app_menu_sign_out_button).setVisible(false)
        }
    }

    fun clearHistory(@Suppress("UNUSED_PARAMETER") menu: MenuItem) {
        // Clear preferences
        saveHistory(mutableListOf())
        // Clear drawer
        historyAdapter.clearHistory()

        if (auth.currentUser != null) {
            db.collection("default").document(auth.currentUser!!.uid).delete()
                .addOnSuccessListener {
                    Log.e(
                        TAG,
                        "Cleared history from firebase"
                    )
                }.addOnFailureListener { e -> Log.e(TAG, "Failure on clearing: ${e.message}") }
        } else {
            Log.e(TAG, "User isn't signed in, can't clear history")
        }

        Toast.makeText(applicationContext, getString(R.string.cleared_history), Toast.LENGTH_SHORT)
            .show()
    }

    fun signIn(@Suppress("UNUSED_PARAMETER") menu: MenuItem) {
        val credentialManager = CredentialManager.create(applicationContext)

        val rawNonce = UUID.randomUUID().toString()
        val bytes = rawNonce.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256")
        val digest = hash.digest(bytes)
        val hashed = digest.fold("") { str, it ->
            str + "%02x".format(it)
        }

        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.CLIENT_ID))
            .setNonce(hashed)
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity,
                )
                val credential = result.credential

                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)

                val googleIdToken = googleIdTokenCredential.idToken

                Log.e(TAG, googleIdToken)

                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                auth.signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.e(TAG, "Success")
                        runOnUiThread {
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.sign_in_success),
                                Toast.LENGTH_SHORT
                            )
                                .show()
                            var history: String
                            db.collection("default").document(auth.currentUser!!.uid).get()
                                .addOnSuccessListener { document ->
                                    Log.e(
                                        TAG,
                                        "Read history from firebase: ${document.data}"
                                    )
                                    if (document.data != null) {
                                        history = document.data!!["history"].toString()
                                        this@MainActivity.history = history
                                        this@MainActivity.historyAdapter.appendHistory(
                                            Gson().fromJson(
                                                history,
                                                Array<History>::class.java
                                            ).asList().toMutableList()
                                        )
                                        if (historyAdapter.itemCount > 0) {
                                            binding.historyRecylcleView.scrollToPosition(
                                                historyAdapter.itemCount - 1
                                            )
                                        }
                                    }
                                }.addOnFailureListener { e ->
                                    Log.e(TAG, "Failure on reading: ${e.message}")
                                }
                        }
                    } else {
                        Log.e(TAG, "Error")
                        runOnUiThread {
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.something_went_wrong),
                                Toast.LENGTH_SHORT
                            )
                                .show()
                        }
                    }
                }
            } catch (e: GetCredentialException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, e.message, Toast.LENGTH_SHORT).show()
                }
                Log.e(TAG, e.message.toString())
            } catch (e: GoogleIdTokenParsingException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, e.message, Toast.LENGTH_SHORT).show()
                }
                Log.e(TAG, e.message.toString())
            }
        }
    }

    private fun createBiometricPromptInfo(): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.menu_sign_out))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

    fun signOut(menu: MenuItem) {
        if (checkBiometricInDevice()) {
            val biometricPrompt = BiometricPrompt(
                this@MainActivity,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        runOnUiThread {
                            auth.signOut()
                            clearHistory(menu)
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.sign_out_success),
                                Toast.LENGTH_SHORT
                            )
                                .show()
                            binding.input.setText("")
                            binding.resultDisplay.text = ""
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.something_went_wrong),
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e(TAG, "Error: ${errorCode}, $errString")
                    }

                    override fun onAuthenticationFailed() {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.something_went_wrong),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            biometricPrompt.authenticate(createBiometricPromptInfo())
        } else {
            auth.signOut()
            clearHistory(menu)
            Toast.makeText(
                applicationContext,
                getString(R.string.sign_out_success),
                Toast.LENGTH_SHORT
            )
                .show()
            binding.input.setText("")
            binding.resultDisplay.text = ""
        }
    }

    private fun keyVibration(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun setErrorColor(errorStatus: Boolean) {
        // Only run if the color needs to be updated
        if (errorStatus != errorStatusOld) {
            // Set error color
            if (errorStatus) {
                binding.input.setTextColor(
                    ContextCompat.getColor(
                        this,
                        R.color.calculation_error_color
                    )
                )
                binding.resultDisplay.setTextColor(
                    ContextCompat.getColor(
                        this,
                        R.color.calculation_error_color
                    )
                )
            }
            // Clear error color
            else {
                binding.input.setTextColor(ContextCompat.getColor(this, R.color.text_color))
                binding.resultDisplay.setTextColor(
                    ContextCompat.getColor(
                        this,
                        R.color.text_second_color
                    )
                )
            }
            errorStatusOld = errorStatus
        }
    }

    private fun updateDisplay(view: View, value: String) {
        val valueNoSeparators = value.replace(groupingSeparatorSymbol, "")
        val isValueInt = valueNoSeparators.toIntOrNull() != null

        // Reset input with current number if following "equal"
        if (isEqualLastAction) {
            if (isValueInt || value == decimalSeparatorSymbol) {
                binding.input.setText("")
            } else {
                binding.input.setSelection(binding.input.text.length)
                binding.inputHorizontalScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT)
            }
            isEqualLastAction = false
        }

        if (!binding.input.isCursorVisible) {
            binding.input.isCursorVisible = true
        }

        lifecycleScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                // Vibrate when key pressed
                keyVibration(view)
            }

            val formerValue = binding.input.text.toString()
            val cursorPosition = binding.input.selectionStart
            val leftValue = formerValue.subSequence(0, cursorPosition).toString()
            val leftValueFormatted =
                NumberFormatter.format(leftValue, decimalSeparatorSymbol, groupingSeparatorSymbol)
            val rightValue = formerValue.subSequence(cursorPosition, formerValue.length).toString()

            val newValue = leftValue + value + rightValue

            val newValueFormatted =
                NumberFormatter.format(newValue, decimalSeparatorSymbol, groupingSeparatorSymbol)

            withContext(Dispatchers.Main) {
                // Avoid two decimalSeparator in the same number
                // when you click on the decimalSeparator button
                if (value == decimalSeparatorSymbol && decimalSeparatorSymbol in binding.input.text.toString()) {
                    if (binding.input.text.toString().isNotEmpty()) {
                        var lastNumberBefore = ""
                        if (cursorPosition > 0 && binding.input.text.toString()
                                .substring(0, cursorPosition)
                                .last() in "0123456789\\$decimalSeparatorSymbol"
                        ) {
                            lastNumberBefore = NumberFormatter.extractNumbers(
                                binding.input.text.toString().substring(0, cursorPosition),
                                decimalSeparatorSymbol
                            ).last()
                        }
                        var firstNumberAfter = ""
                        if (cursorPosition < binding.input.text.length - 1) {
                            firstNumberAfter = NumberFormatter.extractNumbers(
                                binding.input.text.toString()
                                    .substring(cursorPosition, binding.input.text.length),
                                decimalSeparatorSymbol
                            ).first()
                        }
                        if (decimalSeparatorSymbol in lastNumberBefore || decimalSeparatorSymbol in firstNumberAfter) {
                            return@withContext
                        }
                    }
                }

                // Update Display
                binding.input.setText(newValueFormatted)

                // Set cursor position
                if (isValueInt) {
                    val cursorOffset = newValueFormatted.length - newValue.length
                    binding.input.setSelection(cursorPosition + value.length + cursorOffset)
                } else {
                    binding.input.setSelection(leftValueFormatted.length + value.length)
                }
            }
        }
    }

    private fun roundResult(result: BigDecimal): BigDecimal {
        val newResult = result.setScale(100, RoundingMode.HALF_EVEN)

        // Fix how is displayed 0 with BigDecimal
        val tempResult = newResult.toString().replace("E-", "").replace("E", "")
        val allCharsEqualToZero = tempResult.all { it == '0' }
        if (
            allCharsEqualToZero
            || newResult.toString().startsWith("0E")
        ) {
            return BigDecimal.ZERO
        }

        return newResult
    }

    // Switch between degree and radian mode
    private fun toggleDegreeMode() {
        if (isDegreeModeActivated) binding.degreeButton.text = getString(R.string.radian)
        else binding.degreeButton.text = getString(R.string.degree)

        binding.degreeTextView.text = binding.degreeButton.text

        // Flip the variable afterwards
        isDegreeModeActivated = !isDegreeModeActivated
    }

    private fun updateResultDisplay() {
        lifecycleScope.launch(Dispatchers.Default) {
            // Reset text color
            setErrorColor(false)

            val calculation = binding.input.text.toString()

            if (calculation != "") {
                division_by_0 = false
                domain_error = false
                syntax_error = false
                is_infinity = false
                require_real_number = false

                val calculationTmp = Expression().getCleanExpression(
                    binding.input.text.toString(),
                    decimalSeparatorSymbol,
                    groupingSeparatorSymbol
                )
                calculationResult =
                    Calculator(100).evaluate(
                        calculationTmp,
                        isDegreeModeActivated
                    )

                // If result is a number and it is finite
                if (!(division_by_0 || domain_error || syntax_error || is_infinity || require_real_number)) {

                    // Round
                    calculationResult = roundResult(calculationResult)
                    var formattedResult = NumberFormatter.format(
                        calculationResult.toString().replace(".", decimalSeparatorSymbol),
                        decimalSeparatorSymbol,
                        groupingSeparatorSymbol
                    )

                    // Remove zeros at the end of the results (after point)
                    if (!(calculationResult >= BigDecimal(
                            9999
                        ) || calculationResult <= BigDecimal(0.1))
                    ) {
                        val resultSplit = calculationResult.toString().split('.')
                        if (resultSplit.size > 1) {
                            val resultPartAfterDecimalSeparator = resultSplit[1].trimEnd('0')
                            var resultWithoutZeros = resultSplit[0]
                            if (resultPartAfterDecimalSeparator != "") {
                                resultWithoutZeros =
                                    resultSplit[0] + "." + resultPartAfterDecimalSeparator
                            }
                            formattedResult = NumberFormatter.format(
                                resultWithoutZeros.replace(
                                    ".",
                                    decimalSeparatorSymbol
                                ), decimalSeparatorSymbol, groupingSeparatorSymbol
                            )
                        }
                    }


                    withContext(Dispatchers.Main) {
                        if (formattedResult != calculation) {
                            binding.resultDisplay.text = formattedResult
                        } else {
                            binding.resultDisplay.text = ""
                        }
                    }

                } else withContext(Dispatchers.Main) {
                    if (is_infinity && !division_by_0 && !domain_error && !require_real_number) {
                        if (calculationResult < BigDecimal.ZERO) binding.resultDisplay.text = getString(
                                R.string.negative_infinity
                            )
                        else binding.resultDisplay.text = getString(R.string.value_too_large)
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.resultDisplay.text = ""
                        }
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.resultDisplay.text = ""
                }
            }
        }
    }

    fun keyDigitPadMappingToDisplay(view: View) {
        updateDisplay(view, (view as Button).text as String)
    }

    fun scientistModeSwitchButton(@Suppress("UNUSED_PARAMETER") view: View) {
        enableOrDisableScientistMode()
    }

    private fun enableOrDisableScientistMode() {
        if (binding.scientistModeRow2.visibility != View.VISIBLE) {
            binding.scientistModeRow2.visibility = View.VISIBLE
            binding.scientistModeRow3.visibility = View.VISIBLE
            binding.scientistModeSwitchButton?.setImageResource(R.drawable.ic_baseline_keyboard_arrow_up_24)
            binding.degreeTextView.visibility = View.VISIBLE
            binding.degreeTextView.text = binding.degreeButton.text.toString()
        } else {
            binding.scientistModeRow2.visibility = View.GONE
            binding.scientistModeRow3.visibility = View.GONE
            binding.scientistModeSwitchButton?.setImageResource(R.drawable.ic_baseline_keyboard_arrow_down_24)
            binding.degreeTextView.visibility = View.GONE
            binding.degreeTextView.text = binding.degreeButton.text.toString()
        }
    }

    private fun addSymbol(view: View, currentSymbol: String) {
        // Get input text length
        val textLength = binding.input.text.length

        // If the input is not empty
        if (textLength > 0) {
            // Get cursor's current position
            val cursorPosition = binding.input.selectionStart

            // Get next / previous characters relative to the cursor
            val nextChar =
                if (textLength - cursorPosition > 0) binding.input.text[cursorPosition].toString() else "0" // use "0" as default like it's not a symbol
            val previousChar =
                if (cursorPosition > 0) binding.input.text[cursorPosition - 1].toString() else "0"

            if (currentSymbol != previousChar // Ignore multiple presses of the same button
                && currentSymbol != nextChar
                && previousChar != "√" // No symbol can be added on an empty square root
                && previousChar != decimalSeparatorSymbol // Ensure that the previous character is not a comma
                && (previousChar != "(" // Ensure that we are not at the beginning of a parenthesis
                        || currentSymbol == "-")
            ) { // Minus symbol is an override
                // If previous character is a symbol, replace it
                if (previousChar.matches("[+\\-÷×^]".toRegex())) {
                    keyVibration(view)

                    val leftString =
                        binding.input.text.subSequence(0, cursorPosition - 1).toString()
                    val rightString =
                        binding.input.text.subSequence(cursorPosition, textLength).toString()

                    // Add a parenthesis if there is another symbol before minus
                    if (currentSymbol == "-") {
                        if (previousChar in "+-") {
                            binding.input.setText(getString(R.string.three_strings, leftString, currentSymbol, rightString))
                            binding.input.setSelection(cursorPosition)
                        } else {
                            binding.input.setText(getString(R.string.four_strings, leftString, previousChar, currentSymbol, rightString))
                            binding.input.setSelection(cursorPosition + 1)
                        }
                    } else if (cursorPosition > 1 && binding.input.text[cursorPosition - 2] != '(') {
                        binding.input.setText(getString(R.string.three_strings, leftString, currentSymbol, rightString))
                        binding.input.setSelection(cursorPosition)
                    } else if (currentSymbol == "+") {
                        binding.input.setText(getString(R.string.two_strings, leftString, rightString))
                        binding.input.setSelection(cursorPosition - 1)
                    }
                }
                // If next character is a symbol, replace it
                else if (nextChar.matches("[+\\-÷×^%!]".toRegex())
                    && currentSymbol != "%"
                ) { // Make sure that percent symbol doesn't replace succeeding symbols
                    keyVibration(view)

                    val leftString = binding.input.text.subSequence(0, cursorPosition).toString()
                    val rightString =
                        binding.input.text.subSequence(cursorPosition + 1, textLength).toString()

                    if (cursorPosition > 0 && previousChar != "(") {
                        binding.input.setText(getString(R.string.three_strings, leftString, currentSymbol, rightString))
                        binding.input.setSelection(cursorPosition + 1)
                    } else if (currentSymbol == "+") binding.input.setText(getString(R.string.two_strings, leftString, rightString))
                }
                // Otherwise just update the display
                else if (cursorPosition > 0 || nextChar != "0" && currentSymbol == "-") {
                    updateDisplay(view, currentSymbol)
                } else keyVibration(view)
            } else keyVibration(view)
        } else { // Allow minus symbol, even if the input is empty
            if (currentSymbol == "-") updateDisplay(view, currentSymbol)
            else keyVibration(view)
        }
    }

    fun addButton(view: View) {
        addSymbol(view, "+")
    }

    fun subtractButton(view: View) {
        addSymbol(view, "-")
    }

    fun divideButton(view: View) {
        addSymbol(view, "÷")
    }

    fun multiplyButton(view: View) {
        addSymbol(view, "×")
    }

    fun exponentButton(view: View) {
        addSymbol(view, "^")
    }

    fun pointButton(view: View) {
        updateDisplay(view, decimalSeparatorSymbol)
    }

    fun sineButton(view: View) {
        if (!isInvButtonClicked) {
            updateDisplay(view, "sin(")
        } else {
            updateDisplay(view, "sin⁻¹(")
        }
    }

    fun cosineButton(view: View) {
        if (!isInvButtonClicked) {
            updateDisplay(view, "cos(")
        } else {
            updateDisplay(view, "cos⁻¹(")
        }
    }

    fun tangentButton(view: View) {
        if (!isInvButtonClicked) {
            updateDisplay(view, "tan(")
        } else {
            updateDisplay(view, "tan⁻¹(")
        }
    }

    fun eButton(view: View) {
        updateDisplay(view, "e")
    }

    fun naturalLogarithmButton(view: View) {
        if (!isInvButtonClicked) {
            updateDisplay(view, "ln(")
        } else {
            updateDisplay(view, "exp(")
        }
    }

    fun logarithmButton(view: View) {
        if (!isInvButtonClicked) {
            updateDisplay(view, "log(")
        } else {
            updateDisplay(view, "10^")
        }
    }

    fun piButton(view: View) {
        updateDisplay(view, "π")
    }

    fun factorialButton(view: View) {
        addSymbol(view, "!")
    }

    fun squareButton(view: View) {
        if (!isInvButtonClicked) {
            updateDisplay(view, "√")
        } else {
            updateDisplay(view, "^2")
        }
    }

    fun percent(view: View) {
        addSymbol(view, "%")
    }

    @SuppressLint("SetTextI18n")
    fun degreeButton(view: View) {
        keyVibration(view)
        toggleDegreeMode()
        updateResultDisplay()
    }

    fun invButton(view: View) {
        keyVibration(view)

        if (!isInvButtonClicked) {
            isInvButtonClicked = true

            // change buttons
            binding.sineButton.setText(R.string.sineInv)
            binding.cosineButton.setText(R.string.cosineInv)
            binding.tangentButton.setText(R.string.tangentInv)
            binding.naturalLogarithmButton.setText(R.string.naturalLogarithmInv)
            binding.logarithmButton.setText(R.string.logarithmInv)
            binding.squareButton.setText(R.string.squareInv)

        } else {
            isInvButtonClicked = false

            // change buttons
            binding.sineButton.setText(R.string.sine)
            binding.cosineButton.setText(R.string.cosine)
            binding.tangentButton.setText(R.string.tangent)
            binding.naturalLogarithmButton.setText(R.string.naturalLogarithm)
            binding.logarithmButton.setText(R.string.logarithm)
            binding.squareButton.setText(R.string.square)
        }
    }

    fun clearButton(view: View) {
        keyVibration(view)
        binding.input.setText("")
        binding.resultDisplay.text = ""
    }

    @SuppressLint("SetTextI18n")
    fun equalsButton(view: View) {
        lifecycleScope.launch(Dispatchers.Default) {
            keyVibration(view)

            val calculation = binding.input.text.toString()

            if (calculation != "") {

                val resultString = calculationResult.toString()
                var formattedResult = NumberFormatter.format(
                    resultString.replace(".", decimalSeparatorSymbol),
                    decimalSeparatorSymbol,
                    groupingSeparatorSymbol
                )

                // If result is a number and it is finite
                if (!(division_by_0 || domain_error || syntax_error || is_infinity || require_real_number)) {

                    // Remove zeros at the end of the results (after point)
                    val resultSplit = resultString.split('.')
                    if (resultSplit.size > 1) {
                        val resultPartAfterDecimalSeparator = resultSplit[1].trimEnd('0')
                        var resultWithoutZeros = resultSplit[0]
                        if (resultPartAfterDecimalSeparator != "") {
                            resultWithoutZeros =
                                resultSplit[0] + "." + resultPartAfterDecimalSeparator
                        }
                        formattedResult = NumberFormatter.format(
                            resultWithoutZeros.replace(
                                ".",
                                decimalSeparatorSymbol
                            ), decimalSeparatorSymbol, groupingSeparatorSymbol
                        )
                    }

                    // Hide the cursor before updating binding.input to avoid weird cursor movement
                    withContext(Dispatchers.Main) {
                        binding.input.isCursorVisible = false
                    }

                    // Display result
                    withContext(Dispatchers.Main) { binding.input.setText(formattedResult) }

                    // Set cursor
                    withContext(Dispatchers.Main) {
                        // Scroll to the end
                        binding.input.setSelection(binding.input.length())

                        // Hide the cursor (do not remove this, it's not a duplicate)
                        binding.input.isCursorVisible = false

                        // Clear resultDisplay
                        binding.resultDisplay.text = ""
                    }

                    if (calculation != formattedResult) {
                        val history = getHistory()

                        // Do not save to history if the previous entry is the same as the current one
                        if (history.isEmpty() || history[history.size - 1].calculation != calculation) {
                            // Store time
                            val currentTime = System.currentTimeMillis().toString()

                            // Save to history
                            history.add(
                                History(
                                    calculation = calculation,
                                    result = formattedResult,
                                    time = currentTime,
                                )
                            )

                            saveHistory(history)

                            // Update history variables
                            withContext(Dispatchers.Main) {
                                historyAdapter.appendOneHistoryElement(
                                    History(
                                        calculation = calculation,
                                        result = formattedResult,
                                        time = currentTime,
                                    )
                                )

                                // Scroll to the bottom of the recycle view
                                binding.historyRecylcleView.scrollToPosition(historyAdapter.itemCount - 1)
                            }
                        }
                    }
                    isEqualLastAction = true
                } else {
                    withContext(Dispatchers.Main) {
                        if (syntax_error) {
                            setErrorColor(true)
                            binding.resultDisplay.text = getString(R.string.syntax_error)
                        } else if (domain_error) {
                            setErrorColor(true)
                            binding.resultDisplay.text = getString(R.string.domain_error)
                        } else if (require_real_number) {
                            setErrorColor(true)
                            binding.resultDisplay.text = getString(R.string.require_real_number)
                        } else if (division_by_0) {
                            setErrorColor(true)
                            binding.resultDisplay.text = getString(R.string.division_by_0)
                        } else if (is_infinity) {
                            if (calculationResult < BigDecimal.ZERO) binding.resultDisplay.text =
                                "-" + getString(
                                    R.string.infinity
                                )
                            else binding.resultDisplay.text = getString(R.string.value_too_large)
                            //} else if (result.isNaN()) {
                            //    setErrorColor(true)
                            //    binding.resultDisplay.setText(getString(R.string.math_error))
                        } else {
                            binding.resultDisplay.text = formattedResult
                            isEqualLastAction =
                                true // Do not clear the calculation (if you click into a number) if there is an error
                        }
                    }
                }

            } else {
                withContext(Dispatchers.Main) { binding.resultDisplay.text = "" }
            }
        }
    }

    fun parenthesesButton(view: View) {
        val cursorPosition = binding.input.selectionStart
        val textLength = binding.input.text.length

        var openParentheses = 0
        var closeParentheses = 0

        val text = binding.input.text.toString()

        for (i in 0 until cursorPosition) {
            if (text[i] == '(') {
                openParentheses += 1
            }
            if (text[i] == ')') {
                closeParentheses += 1
            }
        }

        if (
            !(textLength > cursorPosition && binding.input.text.toString()[cursorPosition] in "×÷+-^")
            && (
                    openParentheses == closeParentheses
                            || binding.input.text.toString()[cursorPosition - 1] == '('
                            || binding.input.text.toString()[cursorPosition - 1] in "×÷+-^"
                    )
        ) {
            updateDisplay(view, "(")
        } else {
            updateDisplay(view, ")")
        }
    }

    fun backspaceButton(view: View) {
        keyVibration(view)

        var cursorPosition = binding.input.selectionStart
        val textLength = binding.input.text.length
        var newValue = ""
        var isFunction = false
        var isDecimal = false
        var functionLength = 0

        if (isEqualLastAction) {
            cursorPosition = textLength
        }

        if (cursorPosition != 0 && textLength != 0) {
            // Check if it is a function to delete
            val functionsList =
                listOf("cos⁻¹(", "sin⁻¹(", "tan⁻¹(", "cos(", "sin(", "tan(", "ln(", "log(", "exp(")
            for (function in functionsList) {
                val leftPart = binding.input.text.subSequence(0, cursorPosition).toString()
                if (leftPart.endsWith(function)) {
                    newValue = binding.input.text.subSequence(0, cursorPosition - function.length)
                        .toString() +
                            binding.input.text.subSequence(cursorPosition, textLength).toString()
                    isFunction = true
                    functionLength = function.length - 1
                    break
                }
            }
            // Else
            if (!isFunction) {
                // remove the grouping separator
                val leftPart = binding.input.text.subSequence(0, cursorPosition).toString()
                val leftPartWithoutSpaces = leftPart.replace(groupingSeparatorSymbol, "")
                functionLength = leftPart.length - leftPartWithoutSpaces.length

                newValue = leftPartWithoutSpaces.subSequence(0, leftPartWithoutSpaces.length - 1)
                    .toString() +
                        binding.input.text.subSequence(cursorPosition, textLength).toString()

                isDecimal = binding.input.text[cursorPosition - 1] == decimalSeparatorSymbol[0]
            }

            // Handle decimal deletion as a special case when finding cursor position
            var rightSideCommas = 0
            if (isDecimal) {
                val oldString = binding.input.text
                var immediateRightDigits = 0
                var index = cursorPosition
                // Find number of digits that were previously to the right of the decimal
                while (index < textLength && oldString[index].isDigit()) {
                    index++
                    immediateRightDigits++
                }
                // Determine how many thousands separators that gives us to our right
                if (immediateRightDigits > 3)
                    rightSideCommas = immediateRightDigits / 3
            }

            val newValueFormatted =
                NumberFormatter.format(newValue, decimalSeparatorSymbol, groupingSeparatorSymbol)
            var cursorOffset = newValueFormatted.length - newValue.length - rightSideCommas
            if (cursorOffset < 0) cursorOffset = 0

            binding.input.setText(newValueFormatted)
            binding.input.setSelection((cursorPosition - 1 + cursorOffset - functionLength).takeIf { it > 0 }
                ?: 0)
        }
    }

    // Update settings
    override fun onResume() {
        sensorManager?.registerListener(
            sensorListener, sensorManager!!.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            ), SensorManager.SENSOR_DELAY_NORMAL
        )
        super.onResume()

        // Update the theme
        val themes = Themes()
        if (currentTheme != themes.getTheme()) {
            (this as Activity).finish()
            ContextCompat.startActivity(this, this.intent, null)
        }

        binding.clearButton.visibility = View.VISIBLE
        binding.parenthesesButton.visibility = View.VISIBLE


        binding.historyRecylcleView.visibility = View.VISIBLE
        binding.slidingLayoutButton.visibility = View.VISIBLE
        binding.slidingLayout.isEnabled = true

        // Disable the keyboard on display EditText
        binding.input.showSoftInputOnFocus = false
    }

    override fun onPause() {
        sensorManager!!.unregisterListener(sensorListener)
        super.onPause()
    }

    private fun getHistory(): MutableList<History> {
        val gson = Gson()
        return if (preferences?.getString(HistoryParams.KEY_HISTORY, null) != null) {
            gson.fromJson(history, Array<History>::class.java).asList().toMutableList()
        } else {
            mutableListOf()
        }
    }

    private fun saveHistory(history: List<History>) {
        val gson = Gson()
        val history2 = history.toMutableList()
        while (historySize!!.toInt() > 0 && history2.size > historySize!!.toInt()) {
            history2.removeAt(0)
        }
        val test: String = gson.toJson(history2)
        this.history = test // Convert to json
        if (auth.currentUser != null) {
            val data = hashMapOf(
                "history" to test,
            )
            db.collection("default").document(auth.currentUser!!.uid).set(data as Map<String, Any>)
                .addOnSuccessListener {
                    Log.e(
                        TAG,
                        "Saved history to firebase"
                    )
                }.addOnFailureListener { e -> Log.e(TAG, "Failure on saving: ${e.message}") }
        } else {
            Log.e(TAG, "User isn't signed in, can't save history")
        }
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED && !(shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS
                ))
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

}
