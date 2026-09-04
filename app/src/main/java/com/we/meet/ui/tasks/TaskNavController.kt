package com.we.meet.ui.tasks

/**
 * Bridges the hoisted task navigation drawer (owned by [com.we.meet.ui.main.MainTabScreen],
 * so its scrim covers the whole screen including the bottom tab bar) to the
 * [TaskScreen] that owns the [TaskViewModel] and the navigation/dialog state.
 *
 * The controller is created and registered by TaskScreen — this keeps the
 * TaskViewModel lazily instantiated (only when the Tasks tab is active) while
 * letting MainTabScreen render the drawer sheet from `vm.ui`.
 *
 * The action lambdas close over TaskScreen's live mutable state (page, dialogs),
 * which is valid because the drawer can only be opened from the Tasks tab, i.e.
 * while TaskScreen is composed.
 */
class TaskNavController(
    val vm: TaskViewModel,
    val onOpenActivity: () -> Unit,
    val onNewGroup: () -> Unit,
    val onNewList: () -> Unit,
    val onGroupAction: (TaskListGroupItem) -> Unit,
    val onListAction: (TaskListItem) -> Unit,
)
