package com.we.meet.ui.profile

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.we.meet.BuildConfig
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.repository.ProfileRepository
import kotlinx.coroutines.launch

private const val INTRO_MAX_LENGTH = 100

/** A pending crop: which image kind, the picked source, and the fixed output
 *  size the cropper must produce. */
private data class CropRequest(
    val kind: ProfileRepository.Kind,
    val uri: android.net.Uri,
    val width: Int,
    val height: Int,
)

@Composable
fun ProfileScreen(
    onSettingsClick: () -> Unit,
    onOpenAiHub: () -> Unit,
    onOpenApproval: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val tokenStore = app.tokenStore
    val profileRepo = app.profileRepository
    val scope = rememberCoroutineScope()

    val phone = tokenStore.phone ?: ""
    var nickname by remember { mutableStateOf(tokenStore.nickname.orEmpty()) }
    var intro by remember { mutableStateOf(tokenStore.intro.orEmpty()) }
    var avatarUrl by remember { mutableStateOf(tokenStore.avatarUrl.orEmpty()) }
    var coverUrl by remember { mutableStateOf(tokenStore.coverUrl.orEmpty()) }

    var showNicknameDialog by remember { mutableStateOf(false) }
    var showIntroDialog by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var uploadingKind by remember { mutableStateOf<ProfileRepository.Kind?>(null) }
    var cropRequest by remember { mutableStateOf<CropRequest?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val mimeError = stringResource(R.string.profile_image_error_mime)
    val sizeError = stringResource(R.string.profile_image_error_size)
    val uploadError = stringResource(R.string.profile_image_error_upload)
    val introError = stringResource(R.string.profile_intro_error_save)
    val nicknameError = stringResource(R.string.profile_nickname_error_save)

    // Both avatar and cover route through the cropper before upload so the app
    // emits fixed sizes (avatar 600×600, cover 1200×900) regardless of source.
    fun handleCropped(kind: ProfileRepository.Kind, bytes: ByteArray) {
        cropRequest = null
        uploadingKind = kind
        errorMessage = null
        scope.launch {
            profileRepo.uploadProfileImageBytes(kind, bytes, "image/jpeg")
                .onSuccess { user ->
                    avatarUrl = user.avatar_url
                    coverUrl = user.cover_url
                }
                .onFailure { e ->
                    errorMessage = when (e) {
                        is ProfileRepository.UploadError.UnsupportedMime -> mimeError
                        is ProfileRepository.UploadError.TooLarge -> sizeError
                        else -> uploadError
                    }
                }
            uploadingKind = null
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) cropRequest = CropRequest(ProfileRepository.Kind.AVATAR, uri, 600, 600) }

    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) cropRequest = CropRequest(ProfileRepository.Kind.COVER, uri, 1200, 900) }

    LaunchedEffect(Unit) {
        profileRepo.refreshProfile()
            .onSuccess { user ->
                if (user.full_name?.isNotBlank() == true) nickname = user.full_name
                intro = user.intro
                avatarUrl = user.avatar_url
                coverUrl = user.cover_url
            }
        // Keep the legacy Keycloak nickname fallback for users that haven't
        // logged in since the meet-backend started syncing full_name.
        if (nickname.isBlank()) {
            app.authRepository.fetchNickname().onSuccess { name ->
                if (name.isNotBlank()) nickname = name
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Cover image banner with avatar overlapping at the bottom edge
        Box(modifier = Modifier.fillMaxWidth()) {
            CoverBanner(
                coverUrl = coverUrl,
                isUploading = uploadingKind == ProfileRepository.Kind.COVER,
                onClick = {
                    coverPicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
            )
            AvatarBubble(
                avatarUrl = avatarUrl,
                isUploading = uploadingKind == ProfileRepository.Kind.AVATAR,
                onClick = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 44.dp),
            )
        }

        // Reserve space for the avatar bubble overlapping the banner
        Spacer(Modifier.height(56.dp))

        Text(
            text = nickname.ifBlank { phone },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        if (intro.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        // Settings list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            SettingsRow(
                label = stringResource(R.string.profile_nickname),
                value = nickname.ifBlank { stringResource(R.string.profile_not_set) },
                onClick = { showNicknameDialog = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                label = stringResource(R.string.profile_intro),
                value = intro.ifBlank { stringResource(R.string.profile_not_set) },
                onClick = { showIntroDialog = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                label = stringResource(R.string.profile_phone),
                value = phone,
                onClick = null,
            )
        }

        Spacer(Modifier.height(20.dp))

        // App-level settings live in a separate group: language / theme /
        // video codec — all device-wide preferences, not per-meeting.
        // Routing through onSettingsClick keeps the existing SettingsScreen
        // as the single host for these knobs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // AI hub moved here when its bottom tab was replaced by 日历/通讯录.
            SettingsRow(
                label = stringResource(R.string.profile_ai_entry),
                value = null,
                onClick = onOpenAiHub,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // Approval lives here rather than a 6th bottom tab (Feishu-style workbench app).
            SettingsRow(
                label = stringResource(R.string.profile_approval_entry),
                value = null,
                onClick = onOpenApproval,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                label = stringResource(R.string.profile_settings),
                value = null,
                onClick = onSettingsClick,
            )
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { showSignOutConfirm = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(Dimens.ButtonHeight),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(
                text = stringResource(R.string.profile_sign_out),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Deregister moved into Settings → Account so it lives next to the
        // other account-wide knobs instead of cluttering the profile edit
        // surface. Phone number still drives the confirm-friction prompt;
        // SettingsScreen pulls it from TokenStore.

        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { tokenStore.accessToken = "invalid.debug.token" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("Debug: 弄坏 access token")
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    cropRequest?.let { req ->
        ImageCropDialog(
            uri = req.uri,
            outputWidth = req.width,
            outputHeight = req.height,
            onConfirm = { bytes -> handleCropped(req.kind, bytes) },
            onCancel = { cropRequest = null },
        )
    }

    if (showNicknameDialog) {
        val previousNickname = nickname
        NicknameDialog(
            currentNickname = nickname,
            onConfirm = { newName ->
                // Optimistic update so the dialog can close immediately; on
                // failure we revert and surface the error in the same banner
                // intro-save uses.
                nickname = newName
                showNicknameDialog = false
                scope.launch {
                    profileRepo.updateNickname(newName)
                        .onSuccess { user ->
                            user.full_name?.takeIf { it.isNotBlank() }?.let { nickname = it }
                        }
                        .onFailure {
                            nickname = previousNickname
                            errorMessage = nicknameError
                        }
                }
            },
            onDismiss = { showNicknameDialog = false },
        )
    }

    if (showIntroDialog) {
        IntroDialog(
            currentIntro = intro,
            onConfirm = { newIntro ->
                showIntroDialog = false
                scope.launch {
                    profileRepo.updateIntro(newIntro)
                        .onSuccess { user -> intro = user.intro }
                        .onFailure { errorMessage = introError }
                }
            },
            onDismiss = { showIntroDialog = false },
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.profile_sign_out)) },
            text = { Text(stringResource(R.string.profile_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    app.authRepository.signOut()
                    com.we.meet.analytics.Analytics.reset()
                    onSignedOut()
                }) {
                    Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

}

/**
 * Build a Coil request for a profile image (avatar / cover).
 *
 * Those buckets are private, so the backend returns presigned URLs whose
 * query-string signature changes on every `users/me/` fetch. Keying the cache
 * on the full URL would defeat caching, so the disk/memory cache keys are
 * pinned to the stable object path (everything before '?'). This also lets an
 * already-cached image still render when its signed URL has since expired.
 */
private fun profileImageRequest(context: Context, url: String): ImageRequest {
    val stableKey = url.substringBefore('?')
    return ImageRequest.Builder(context)
        .data(url)
        .diskCacheKey(stableKey)
        .memoryCacheKey(stableKey)
        .build()
}

@Composable
private fun CoverBanner(
    coverUrl: String,
    isUploading: Boolean,
    onClick: () -> Unit,
) {
    val placeholderColor = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(placeholderColor)
            .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = profileImageRequest(LocalContext.current, coverUrl),
                contentDescription = stringResource(R.string.profile_cover),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (isUploading) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.profile_change_cover),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AvatarBubble(
    avatarUrl: String,
    isUploading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isUploading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )

            avatarUrl.isNotBlank() -> AsyncImage(
                model = profileImageRequest(LocalContext.current, avatarUrl),
                contentDescription = stringResource(R.string.profile_avatar),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            else -> Icon(
                imageVector = Icons.Default.Person,
                contentDescription = stringResource(R.string.profile_avatar),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String?,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NicknameDialog(
    currentNickname: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(currentNickname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_set_nickname)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(20) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.profile_nickname_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input.trim()) },
                enabled = input.isNotBlank(),
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun IntroDialog(
    currentIntro: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(currentIntro) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_set_intro)) },
        text = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(INTRO_MAX_LENGTH) },
                    placeholder = { Text(stringResource(R.string.profile_intro_hint)) },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.profile_intro_counter,
                        input.length,
                        INTRO_MAX_LENGTH,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
