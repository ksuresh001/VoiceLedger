package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.preferences.UserPrefsDataStore
import com.example.data.repository.NotesRepository
import com.example.data.repository.WorkspaceRepository
import com.example.firebase.FirebaseRuntimeConfig
import com.example.firebase.configureAppCheck
import com.example.service.AiOrganizerService
import com.example.service.DeadlineNotificationReceiver
import com.example.service.NotificationScheduler
import com.example.service.ReportExportService
import com.example.service.VoiceAudioPlayer
import com.example.service.VoiceCaptureEngine
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.findActivity
import com.example.ui.auth.signOut
import com.example.ui.screens.CaptureBottomSheet
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.NoteDetailScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.WorkspacesAndExportScreen
import com.example.ui.theme.VoiceLedgerTheme
import com.example.ui.viewmodel.VoiceLedgerViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_SEARCH = "search"
const val ROUTE_HUB = "hub"
const val ROUTE_DETAIL = "detail/{noteId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore runtime Firebase config if APK was built without bundled google-services.json
        FirebaseRuntimeConfig.ensureInitialized(this)

        // Step 1: Configure Firebase App Check with launch intent before Compose UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        val initialNoteId = intent?.getStringExtra(DeadlineNotificationReceiver.EXTRA_NOTE_ID)

        setContent {
            VoiceLedgerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(initialNoteId = initialNoteId)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(initialNoteId: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isFirebaseReady by remember {
        mutableStateOf(FirebaseRuntimeConfig.ensureInitialized(context))
    }
    if (!isFirebaseReady) {
        FirebaseConfigSetupScreen(
            onConfigured = {
                val activity = context.findActivity()
                configureAppCheck(context, activity?.intent)
                isFirebaseReady = FirebaseRuntimeConfig.ensureInitialized(context)
            }
        )
        return
    }

    val auth = remember { Firebase.auth }
    var currentUser by remember(auth) { mutableStateOf(auth.currentUser) }

    // Keep AuthStateListener active across ON_PAUSE/ON_STOP so returning from external
    // Google login/account redirects never misses the authenticated Firebase user.
    DisposableEffect(auth, lifecycleOwner) {
        currentUser = auth.currentUser
        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentUser = auth.currentUser
            }
        }
        auth.addAuthStateListener(authListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            auth.removeAuthStateListener(authListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = auth.currentUser
            }
        )
    } else {
        key(user.uid) {
            AuthenticatedVoiceLedgerApp(
                currentUserId = user.uid,
                currentUserName = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                currentUserEmail = user.email.orEmpty(),
                initialNoteId = initialNoteId,
                onSignedOut = {
                    currentUser = auth.currentUser
                }
            )
        }
    }
}

@Composable
private fun FirebaseConfigSetupScreen(onConfigured: () -> Unit) {
    val context = LocalContext.current
    var rawJson by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Connect Firebase Config",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "This APK was built without app/google-services.json (GitHub exports omit it by default). You can either:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "1. Paste your app/google-services.json below to activate this APK immediately without rebuilding.\n2. Or add GOOGLE_SERVICES_JSON to GitHub Settings → Secrets → Actions and re-run the Build Android APK workflow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = rawJson,
                    onValueChange = {
                        rawJson = it
                        errorText = null
                    },
                    label = { Text("Paste app/google-services.json contents") },
                    minLines = 5,
                    maxLines = 9,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("google_services_json_input")
                )

                if (!errorText.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorText!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        FirebaseRuntimeConfig.saveAndInitialize(context, rawJson)
                            .onSuccess { onConfigured() }
                            .onFailure { err ->
                                errorText = err.message ?: "Invalid google-services.json format."
                            }
                    },
                    enabled = rawJson.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apply_firebase_config_button")
                ) {
                    Text("Apply Firebase Config & Continue")
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedVoiceLedgerApp(
    currentUserId: String,
    currentUserName: String,
    currentUserEmail: String,
    initialNoteId: String?,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val credentialManager = remember(activity, context) {
        runCatching { CredentialManager.create(activity ?: context) }.getOrNull()
    }

    val viewModel: VoiceLedgerViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                VoiceLedgerViewModel(
                    notesRepository = NotesRepository(db),
                    workspaceRepository = WorkspaceRepository(db),
                    aiOrganizerService = AiOrganizerService(),
                    prefsDataStore = UserPrefsDataStore(app),
                    currentUserId = currentUserId,
                    currentUserName = currentUserName
                )
            }
        }
    )

    val captureEngine = remember(currentUserId) { VoiceCaptureEngine(context.applicationContext, scope) }
    val audioPlayer = remember(currentUserId) { VoiceAudioPlayer(scope) }
    val exportService = remember(context.applicationContext) { ReportExportService(context.applicationContext) }
    val notificationScheduler = remember(context.applicationContext) {
        NotificationScheduler(context.applicationContext).apply { ensureNotificationChannel() }
    }

    DisposableEffect(Unit) {
        onDispose {
            captureEngine.discardCapture()
            audioPlayer.release()
        }
    }

    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val userPrefs by viewModel.userPrefs.collectAsStateWithLifecycle()
    val aiOrganizeState by viewModel.aiOrganizeState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val liveCaptureState by captureEngine.state.collectAsStateWithLifecycle()
    val playbackState by audioPlayer.state.collectAsStateWithLifecycle()

    var showCaptureSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scheduleNoteReminder.collect { note ->
            notificationScheduler.scheduleDeadlineReminder(note)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.cancelNoteReminder.collect { noteId ->
            notificationScheduler.cancelReminder(noteId)
        }
    }

    LaunchedEffect(initialNoteId) {
        if (!initialNoteId.isNullOrBlank() && initialNoteId != "aging_digest") {
            navController.navigate("detail/$initialNoteId")
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isDetailRoute = currentRoute?.startsWith("detail/") == true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                if (!isDetailRoute) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.resetAiOrganizeState()
                            showCaptureSheet = true
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("capture_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Record Voice Note"
                        )
                    }
                }
            },
            bottomBar = {
                if (!useRail && !isDetailRoute) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_DASHBOARD,
                            onClick = {
                                navController.navigate(ROUTE_DASHBOARD) {
                                    launchSingleTop = true
                                    popUpTo(ROUTE_DASHBOARD)
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_DASHBOARD) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                                    contentDescription = "Dashboard"
                                )
                            },
                            label = { Text("Dashboard") },
                            modifier = Modifier.testTag("nav_dashboard")
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_SEARCH,
                            onClick = {
                                navController.navigate(ROUTE_SEARCH) {
                                    launchSingleTop = true
                                    popUpTo(ROUTE_DASHBOARD)
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_SEARCH) Icons.Filled.Search else Icons.Outlined.Search,
                                    contentDescription = "Search"
                                )
                            },
                            label = { Text("Search") },
                            modifier = Modifier.testTag("nav_search")
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_HUB,
                            onClick = {
                                navController.navigate(ROUTE_HUB) {
                                    launchSingleTop = true
                                    popUpTo(ROUTE_DASHBOARD)
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_HUB) Icons.Filled.Groups else Icons.Outlined.Groups,
                                    contentDescription = "Team & Export"
                                )
                            },
                            label = { Text("Team & Export") },
                            modifier = Modifier.testTag("nav_hub")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && !isDetailRoute) {
                    NavigationRail(modifier = Modifier.statusBarsPadding()) {
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_DASHBOARD,
                            onClick = {
                                navController.navigate(ROUTE_DASHBOARD) {
                                    launchSingleTop = true
                                    popUpTo(ROUTE_DASHBOARD)
                                }
                            },
                            icon = { Icon(Icons.Filled.Dashboard, contentDescription = "Dashboard") },
                            label = { Text("Dashboard") }
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_SEARCH,
                            onClick = {
                                navController.navigate(ROUTE_SEARCH) {
                                    launchSingleTop = true
                                    popUpTo(ROUTE_DASHBOARD)
                                }
                            },
                            icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                            label = { Text("Search") }
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_HUB,
                            onClick = {
                                navController.navigate(ROUTE_HUB) {
                                    launchSingleTop = true
                                    popUpTo(ROUTE_DASHBOARD)
                                }
                            },
                            icon = { Icon(Icons.Filled.Groups, contentDescription = "Team & Export") },
                            label = { Text("Hub") }
                        )
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_DASHBOARD,
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxSize()
                    ) {
                        composable(ROUTE_DASHBOARD) {
                            DashboardScreen(
                                state = dashboardState,
                                userName = currentUserName,
                                userEmail = currentUserEmail,
                                onSelectWorkspaceFilter = viewModel::setWorkspaceFilter,
                                onSelectTimeWindow = viewModel::setTimeWindow,
                                onSelectCategory = viewModel::setCategoryFilter,
                                onSelectStatus = viewModel::setStatusFilter,
                                onToggleAgingOnly = viewModel::toggleAgingOver3DaysOnly,
                                onOpenNote = { note -> navController.navigate("detail/${note.id}") },
                                onResolveNote = { id, res -> viewModel.resolveNote(id, res) },
                                onOpenCapture = {
                                    viewModel.resetAiOrganizeState()
                                    showCaptureSheet = true
                                },
                                onOpenExportHub = {
                                    navController.navigate(ROUTE_HUB) {
                                        launchSingleTop = true
                                        popUpTo(ROUTE_DASHBOARD)
                                    }
                                },
                                onSignOut = {
                                    signOut(credentialManager, scope, onSignOutComplete = onSignedOut)
                                },
                                modifier = Modifier.statusBarsPadding()
                            )
                        }

                        composable(ROUTE_SEARCH) {
                            SearchScreen(
                                state = dashboardState,
                                query = searchQuery,
                                onQueryChange = viewModel::setSearchQuery,
                                searchResultsProvider = viewModel::searchNotes,
                                onOpenNote = { note -> navController.navigate("detail/${note.id}") },
                                modifier = Modifier.statusBarsPadding()
                            )
                        }

                        composable(ROUTE_HUB) {
                            WorkspacesAndExportScreen(
                                state = dashboardState,
                                userPrefs = userPrefs,
                                currentUserId = currentUserId,
                                isBusy = isBusy,
                                onCreateWorkspace = viewModel::createWorkspace,
                                onJoinWorkspace = viewModel::joinWorkspaceByCode,
                                onDeleteWorkspace = viewModel::deleteWorkspace,
                                onExportPdf = { notes, title ->
                                    exportService.exportPdfReport(notes, title)
                                        .onSuccess { file ->
                                            exportService.shareFile(file, "application/pdf", "Share PDF Report")
                                        }
                                        .onFailure { err ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    err.localizedMessage ?: "PDF export failed."
                                                )
                                            }
                                        }
                                },
                                onExportExcel = { notes, title ->
                                    exportService.exportExcelReport(notes, title)
                                        .onSuccess { file ->
                                            exportService.shareFile(
                                                file,
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                "Share Excel Workbook"
                                            )
                                        }
                                        .onFailure { err ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    err.localizedMessage ?: "Excel export failed."
                                                )
                                            }
                                        }
                                },
                                onRemindersToggle = viewModel::setRemindersEnabled,
                                onLeadMinutesChange = viewModel::setDefaultLeadMinutes,
                                onDailyDigestToggle = viewModel::setDailyDigestEnabled,
                                onSendTestDigest = notificationScheduler::sendAgingDigestNotification,
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                                modifier = Modifier.statusBarsPadding()
                            )
                        }

                        composable(
                            route = ROUTE_DETAIL,
                            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
                        ) { entry ->
                            val noteId = entry.arguments?.getString("noteId").orEmpty()
                            val note = dashboardState.allNotes.firstOrNull { it.id == noteId }
                            val audioFile = note?.localAudioFileName?.let { captureEngine.getAudioFile(it) }

                            NoteDetailScreen(
                                note = note,
                                currentUserId = currentUserId,
                                nowMs = dashboardState.nowMs,
                                audioFile = audioFile,
                                playbackState = playbackState,
                                isBusy = isBusy,
                                onToggleAudio = audioPlayer::togglePlayback,
                                onSeekAudio = audioPlayer::seekTo,
                                onStopAudio = audioPlayer::release,
                                onReOrganizeWithAi = viewModel::reOrganizeExistingNote,
                                onSaveChanges = { updated -> viewModel.updateExistingNote(updated) },
                                onResolveNote = { id, res -> viewModel.resolveNote(id, res) },
                                onCreateFollowUp = { closedNote ->
                                    viewModel.createFollowUpFromClosed(closedNote) { newId ->
                                        navController.navigate("detail/$newId") {
                                            popUpTo(ROUTE_DASHBOARD)
                                        }
                                    }
                                },
                                onDeleteNote = { id ->
                                    viewModel.deleteNote(id) {
                                        navController.popBackStack()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCaptureSheet) {
        val defaultWs = if (dashboardState.selectedWorkspaceId != VoiceLedgerViewModel.PERSONAL_FILTER) {
            dashboardState.selectedWorkspaceId
        } else ""

        CaptureBottomSheet(
            liveState = liveCaptureState,
            aiOrganizeState = aiOrganizeState,
            workspaces = dashboardState.workspaces,
            defaultWorkspaceId = defaultWs,
            isSaving = isBusy,
            onStartCapture = {
                viewModel.resetAiOrganizeState()
                captureEngine.startCapture()
            },
            onStopAndOrganize = { preferredCat ->
                scope.launch {
                    val finalLive = captureEngine.stopCapture()
                    val audioFile = captureEngine.getAudioFile(finalLive.recordedFileName)
                    viewModel.organizeCapturedNote(
                        transcript = finalLive.fullTranscript,
                        audioFile = audioFile,
                        audioDurationSec = finalLive.elapsedSec,
                        preferredCategory = preferredCat,
                        defaultWorkspaceId = defaultWs
                    )
                }
            },
            onOrganizeTypedDraft = { text, preferredCat ->
                viewModel.organizeCapturedNote(
                    transcript = text,
                    audioFile = null,
                    audioDurationSec = 0,
                    preferredCategory = preferredCat,
                    defaultWorkspaceId = defaultWs
                )
            },
            onRetryTranscribe = { draft ->
                val audioFile = captureEngine.getAudioFile(draft.localAudioFileName)
                viewModel.retryTranscribeDraft(draft, audioFile)
            },
            onSaveDraft = { draft ->
                viewModel.saveCapturedDraft(draft) {
                    showCaptureSheet = false
                }
            },
            onDismiss = {
                captureEngine.discardCapture()
                viewModel.resetAiOrganizeState()
                showCaptureSheet = false
            }
        )
    }
}
