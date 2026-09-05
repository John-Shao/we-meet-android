package com.we.meet.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.R
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.ExternalContactDto
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/** External contacts live in the directory; calendar only selects accepted rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalContactsSheet(
    repository: DirectoryRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<ExternalContactDto>>(emptyList()) }
    var requests by remember { mutableStateOf<List<ExternalContactDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchCompleted by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<ExternalContactDto>>(emptyList()) }

    LaunchedEffect(repository, refresh) {
        loading = true
        loadFailed = false
        actionFailed = false
        val accepted = repository.listExternalContacts()
        val pending = repository.listExternalContactRequests()
        if (accepted.isSuccess && pending.isSuccess) {
            contacts = accepted.getOrDefault(emptyList())
            requests = pending.getOrDefault(emptyList())
        } else {
            loadFailed = true
        }
        loading = false
    }

    fun mutate(block: suspend () -> Result<*>) {
        scope.launch {
            if (block().isSuccess) refresh++ else actionFailed = true
        }
    }

    fun search() {
        val submittedQuery = query.trim()
        if (submittedQuery.isEmpty() || searching) return
        scope.launch {
            searching = true
            searchCompleted = false
            searchFailed = false
            repository.searchExternalAccounts(submittedQuery)
                .onSuccess { searchResults = it }
                .onFailure { searchFailed = true }
            searchCompleted = true
            searching = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.external_contacts_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.external_contacts_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { adding = !adding }) {
                    Text(
                        stringResource(
                            if (adding) R.string.common_cancel
                            else R.string.external_contacts_add,
                        ),
                    )
                }
            }

            if (adding) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.SpaceM),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            searchCompleted = false
                            searchFailed = false
                            searchResults = emptyList()
                        },
                        placeholder = { Text(stringResource(R.string.external_contacts_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = query.isNotBlank() && !searching,
                        onClick = ::search,
                    ) { Text(stringResource(R.string.external_contacts_search)) }
                }
                when {
                    searching -> WeMeetInlineLoading()
                    searchFailed -> WeMeetInlineErrorState(
                        onRetry = ::search,
                        message = stringResource(R.string.contacts_load_error),
                    )
                    searchCompleted && searchResults.isEmpty() -> WeMeetInlineEmptyState(
                        title = stringResource(R.string.contacts_empty_search),
                    )
                    else -> {
                        LazyColumn {
                            items(searchResults, key = { it.id }) { contact ->
                                ExternalContactRow(contact = contact) {
                                    when {
                                        contact.status == "accepted" -> Text(
                                            stringResource(R.string.external_contacts_already),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        contact.direction == "incoming" -> Button(
                                            onClick = {
                                                mutate {
                                                    repository.acceptExternalContactRequest(
                                                        contact.relationshipId!!,
                                                    )
                                                }
                                                adding = false
                                            },
                                        ) { Text(stringResource(R.string.external_contacts_accept)) }
                                        contact.direction == "outgoing" -> Text(
                                            stringResource(R.string.external_contacts_pending),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        else -> Button(
                                            onClick = {
                                                mutate {
                                                    repository.sendExternalContactRequest(contact.id)
                                                }
                                                adding = false
                                            },
                                        ) { Text(stringResource(R.string.external_contacts_send)) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else when {
                loading && contacts.isEmpty() && requests.isEmpty() -> WeMeetLoading()
                loadFailed && contacts.isEmpty() && requests.isEmpty() -> WeMeetErrorState(
                    onRetry = { refresh++ },
                    message = stringResource(R.string.contacts_load_error),
                )
                else -> {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (loadFailed || actionFailed) {
                        WeMeetInlineErrorState(
                            onRetry = { refresh++ },
                            message = stringResource(
                                if (actionFailed) R.string.external_contacts_action_failed
                                else R.string.contacts_load_error,
                            ),
                        )
                    }
                    LazyColumn {
                        if (requests.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.external_contacts_requests),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = Dimens.SpaceM),
                                )
                            }
                            items(requests, key = { it.relationshipId ?: it.id }) { contact ->
                                ExternalContactRow(contact = contact) {
                                    if (contact.direction == "incoming") {
                                        Button(onClick = {
                                            mutate {
                                                repository.acceptExternalContactRequest(
                                                    contact.relationshipId!!,
                                                )
                                            }
                                        }) { Text(stringResource(R.string.external_contacts_accept)) }
                                        TextButton(onClick = {
                                            mutate {
                                                repository.declineExternalContactRequest(
                                                    contact.relationshipId!!,
                                                )
                                            }
                                        }) { Text(stringResource(R.string.external_contacts_decline)) }
                                    } else {
                                        Text(stringResource(R.string.external_contacts_pending))
                                    }
                                }
                            }
                        }
                        if (contacts.isEmpty() && requests.isEmpty()) {
                            item {
                                WeMeetInlineEmptyState(
                                    title = stringResource(R.string.external_contacts_empty),
                                )
                            }
                        }
                        items(contacts, key = { it.relationshipId ?: it.id }) { contact ->
                            ExternalContactRow(contact = contact) {
                                Text(
                                    stringResource(R.string.external_contacts_tag),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(onClick = {
                                    mutate {
                                        repository.removeExternalContact(contact.relationshipId!!)
                                    }
                                }) { Text(stringResource(R.string.external_contacts_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalContactRow(
    contact: ExternalContactDto,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        MemberAvatar(
            name = contact.displayName,
            url = contact.avatarUrl,
            cacheKey = "external:${contact.id}",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.SpaceM),
        ) {
            Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                contact.organization?.name.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(content = actions)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
