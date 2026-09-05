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
    // When hosted in a drawer this is false while closed: the composable must stay
    // in the tree (gating its composition breaks the drawer's drag anchors), but
    // its /users/me/ fetch should follow the user actually opening the page.
    active: Boolean = true,
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
    var nicknameSaving by remember { mutableStateOf(false) }
    var introSaving by remember { mutableStateOf(false) }
    var nicknameDialogError by remember { mutableStateOf<String?>(null) }
    var introDialogError by remember { mutableStateOf<String?>(null) }
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

    // Keyed on `active` so re-opening the drawer re-fetches (signed avatar/cover
    // URLs expire); a cold start with the drawer closed does nothing.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
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
                    .offset(y = Dimens.Profile.AvatarOverlap),
            )
        }

        // Reserve space for the avatar bubble overlapping the banner
        Spacer(Modifier.height(Dimens.Profile.AvatarReserve))

        Text(
            text = nickname.ifBlank { phone },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        if (intro.isNotBlank()) {
            Spacer(Modifier.height(Dimens.SpaceXs))
            Text(
                text = intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
            )
        }

        Spacer(Modifier.height(Dimens.SpaceXl))

        // Settings list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding)
                .clip(RoundedCornerShape(Dimens.CornerM))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            SettingsRow(
                label = stringResource(R.string.profile_nickname),
                value = nickname.ifBlank { stringResource(R.string.profile_not_set) },
                onClick = {
                    nicknameDialogError = null
                    showNicknameDialog = true
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding))
            SettingsRow(
                label = stringResource(R.string.profile_intro),
                value = intro.ifBlank { stringResource(R.string.profile_not_set) },
                onClick = {
                    introDialogError = null
                    showIntroDialog = true
                },
            )
            // Phone moved to Settings → 账号与安全 alongside the deregister flow.
        }

        Spacer(Modifier.height(Dimens.SpaceXl))

        // App-level settings live in a separate group: language / theme /
        // video codec — all device-wide preferences, not per-meeting.
        // Routing through onSettingsClick keeps the existing SettingsScreen
        // as the single host for these knobs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding)
                .clip(RoundedCornerShape(Dimens.CornerM))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // AI hub moved here when its bottom tab was replaced by 日历/通讯录.
            SettingsRow(
                label = stringResource(R.string.profile_ai_entry),
                value = null,
                onClick = onOpenAiHub,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding))
            // Approval lives here rather than a 6th bottom tab (Feishu-style workbench app).
            SettingsRow(
                label = stringResource(R.string.profile_approval_entry),
                value = null,
                onClick = onOpenApproval,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding))
            SettingsRow(
                label = stringResource(R.string.profile_settings),
                value = null,
                onClick = onSettingsClick,
            )
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(Dimens.SpaceM))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
        }

        // Sign-out and deregister both live in Settings → Account now, next to
        // the other account-wide knobs, instead of cluttering the profile edit
        // surface. Phone number still drives the deregister confirm-friction
        // prompt; SettingsScreen pulls it from TokenStore.

        Spacer(Modifier.height(Dimens.SpaceXl))
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
        NicknameDialog(
            currentNickname = nickname,
            saving = nicknameSaving,
            errorMessage = nicknameDialogError,
            onConfirm = { newName ->
                if (nicknameSaving) return@NicknameDialog
                nicknameSaving = true
                nicknameDialogError = null
                scope.launch {
                    profileRepo.updateNickname(newName)
                        .onSuccess { user ->
                            nickname = user.full_name?.takeIf { it.isNotBlank() } ?: newName
                            nicknameSaving = false
                            showNicknameDialog = false
                        }
                        .onFailure {
                            nicknameSaving = false
                            nicknameDialogError = nicknameError
                        }
                }
            },
            onDismiss = { if (!nicknameSaving) showNicknameDialog = false },
        )
    }

    if (showIntroDialog) {
        IntroDialog(
            currentIntro = intro,
            saving = introSaving,
            errorMessage = introDialogError,
            onConfirm = { newIntro ->
                if (introSaving) return@IntroDialog
                introSaving = true
                introDialogError = null
                scope.launch {
                    profileRepo.updateIntro(newIntro)
                        .onSuccess { user ->
                            intro = user.intro
                            introSaving = false
                            showIntroDialog = false
                        }
                        .onFailure {
                            introSaving = false
                            introDialogError = introError
                        }
                }
            },
            onDismiss = { if (!introSaving) showIntroDialog = false },
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
            .height(Dimens.Profile.HeaderHeight)
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
                    .padding(Dimens.SpaceM)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(Dimens.SpaceXs),
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.profile_change_cover),
                    tint = Color.White,
                    modifier = Modifier.size(Dimens.IconSmall),
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
            .size(Dimens.AvatarXxl)
            .clip(CircleShape)
            // 这圈是「把头像从抽屉面板里抠出来」的留白,所以必须取面板自己的
            // 底色 surface,不是页面底色 background。浅色下两者只差 3 个色阶
            // (FCFCFD / FFFFFF)看不出来,深色下差得出来(111418 / 1A1C1E)——
            // 用 background 会在封面上描出一圈比周围都黑的硬边。
            .border(Dimens.SpaceXxs, MaterialTheme.colorScheme.surface, CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isUploading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(Dimens.AvatarM),
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
                modifier = Modifier.size(Dimens.IconIllustration),
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
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
                modifier = Modifier.padding(end = Dimens.SpaceXs),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(Dimens.SpaceXs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
            )
        }
    }
}

@Composable
private fun NicknameDialog(
    currentNickname: String,
    saving: Boolean,
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(currentNickname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_set_nickname)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(20) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.profile_nickname_hint)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            )
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input.trim()) },
                enabled = input.isNotBlank() && !saving,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                    )
                } else {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun IntroDialog(
    currentIntro: String,
    saving: Boolean,
    errorMessage: String?,
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
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(INTRO_MAX_LENGTH) },
                    placeholder = { Text(stringResource(R.string.profile_intro_hint)) },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
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
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                    )
                } else {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
