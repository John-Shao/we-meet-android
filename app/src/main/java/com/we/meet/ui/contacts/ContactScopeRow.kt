package com.we.meet.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.ui.components.WeMeetChipRow
import com.we.meet.ui.theme.Dimens

/**
 * 全局搜索页「联系人」分类的第二行:**搜索范围 chip**。
 *
 * 在这之前「在哪个范围里找人」是靠**入口**表达的 —— 通讯录 tab 的放大镜进全组织,
 * 部门页里的输入框进当前部门,而两者进去之后都改不了范围,也看不出自己当前在
 * 哪个范围(部门页那个 placeholder 一输入就被覆盖、面包屑也被一并藏了)。
 *
 * 这里把范围做成搜索页里一个**常驻可见、随时可改**的 chip。从部门页跳进来时由
 * 路由预选好那个部门,所以「在产品部里找人」这条最常用的路径仍然是一次点击。
 *
 * 用 [AssistChip] 而不是分类行那种 `FilterChip`,是让两行的**视觉层级**分开:
 * 分类行换的是「搜什么」,是重选择;这一行只收窄范围,而且**标签本身就是状态**
 * (「全部组织」/「产品部」),不需要再叠一层选中色。
 *
 * 部门清单在组件内部拉(`listAllDepartments`,小且不分页),宿主只持有选中的 id。
 */
@Composable
fun ContactScopeRow(
    repository: DirectoryRepository,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val departments by produceState(initialValue = emptyList<DepartmentDto>(), repository) {
        value = repository.listAllDepartments().getOrDefault(emptyList())
    }
    var pickerOpen by remember { mutableStateOf(false) }

    val allLabel = stringResource(R.string.contacts_scope_all)
    // 拿不到部门清单(还没加载完/请求失败)时退回「全部组织」:范围显示错了比
    // 显示成默认值危险得多 —— 用户会照着它去理解结果。
    val label = remember(departments, selectedId, allLabel) {
        selectedId
            ?.let { id -> departments.firstOrNull { it.id == id } }
            ?.let { departmentPathLabel(it, departments) }
            ?: allLabel
    }

    WeMeetChipRow(modifier = modifier.fillMaxWidth()) {
        item {
            AssistChip(
                onClick = { pickerOpen = true },
                label = {
                    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingIcon = {
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(Dimens.IconSmall))
                },
            )
        }
    }

    if (pickerOpen) {
        DepartmentPickerSheet(
            departments = departments,
            selectedId = selectedId,
            onPick = {
                onSelect(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * 「组织 › 产品部 › 前端组」。
 *
 * 显示**全路径**而不是光部门名:同名部门挂在不同父级下时,光看名字分不出是哪个,
 * 而选错范围的代价是"搜不到人"。chip 与列表行都单行省略,长了自然退化。
 */
private fun departmentPathLabel(dept: DepartmentDto, all: List<DepartmentDto>): String {
    val byId = all.associateBy { it.id }
    val names = ArrayDeque<String>()
    var node: DepartmentDto? = dept
    // 防脏数据成环:路径最多拼六级,再长就截断。
    var guard = 0
    while (node != null && guard++ < MAX_SCOPE_DEPTH) {
        names.addFirst(node.name.orEmpty())
        node = node.parent?.let { byId[it] }
    }
    return names.joinToString(SEPARATOR)
}

private const val MAX_SCOPE_DEPTH = 6
private const val SEPARATOR = " › "

/**
 * 部门选择器。
 *
 * 下钻模型和通讯录页一致(点目录进下一级),但**下钻与选择是两件事**:点名字 =
 * 选中这个部门并关闭,点右侧箭头 = 进去看它的下级。合成一个手势的话,「选中有
 * 下级的部门」就得多绕一步(先进去、再退出来)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartmentPickerSheet(
    departments: List<DepartmentDto>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // 下钻栈;空 = 组织根层级。
    var stack by remember { mutableStateOf(emptyList<DepartmentDto>()) }
    val current = stack.lastOrNull()
    val children = remember(departments, current) {
        departments.filter { it.parent == current?.id }
    }
    val drillLabel = stringResource(R.string.contacts_scope_open_children)

    ModalBottomSheet(
        sheetState = sheetState,
        // 返回键和点遮罩都走这里:下钻中先回上一层,而不是直接关掉整个面板 ——
        // 刚点进一个部门就误触返回,期望的是回退,不是放弃整个选择。
        onDismissRequest = {
            if (stack.isEmpty()) onDismiss() else stack = stack.dropLast(1)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            Text(
                text = stringResource(R.string.contacts_scope_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (stack.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = Dimens.SpaceS),
                ) {
                    Text(
                        text = stringResource(R.string.contacts_root_org),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { stack = emptyList() },
                    )
                    stack.forEachIndexed { index, dept ->
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Dimens.IconSmall),
                        )
                        val isLast = index == stack.lastIndex
                        Text(
                            text = dept.name.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isLast) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.clickable(enabled = !isLast) {
                                stack = stack.take(index + 1)
                            },
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.heightIn(max = Dimens.SheetContentMaxHeight)) {
                item(key = "scope-all") {
                    ScopeOptionRow(
                        label = stringResource(R.string.contacts_scope_all),
                        selected = selectedId == null,
                        onClick = { onPick(null) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = Dimens.DividerIndent),
                    )
                }
                items(children, key = { it.id }) { dept ->
                    val hasChildren = departments.any { it.parent == dept.id }
                    ScopeOptionRow(
                        label = dept.name.orEmpty(),
                        selected = dept.id == selectedId,
                        onClick = { onPick(dept.id) },
                        onDrillIn = if (hasChildren) {
                            { stack = stack + dept }
                        } else {
                            null
                        },
                        drillLabel = drillLabel,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = Dimens.DividerIndent),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDrillIn: (() -> Unit)? = null,
    drillLabel: String = "",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = Dimens.SpaceM),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    // 纯装饰:勾选位置已经表达了选中,重复朗读是噪音。
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.IconMedium),
                )
            }
        }
        if (onDrillIn != null) {
            IconButton(onClick = onDrillIn) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = drillLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
