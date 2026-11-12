package com.example.vision

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val CameraPanel = Color(0xC01A2740)

@Composable
internal fun CameraHeader(
    is3d: Boolean,
    ready: Boolean,
    on2d: () -> Unit,
    on3d: () -> Unit,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GlassSurface(shape = CircleShape) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(false to "2D", true to "3D").forEach { (mode, label) ->
                    TextButton(
                        onClick = if (mode) on3d else on2d,
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor =
                                    if (is3d == mode) Color.White.copy(alpha = .16f)
                                    else Color.Transparent,
                                contentColor = Color.White,
                            ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(label)
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassSurface(shape = CircleShape) {
                Text(
                    if (ready) "●" else "◌",
                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    color = if (ready) Mint else Color(0xFFFFD271),
                    fontSize = 16.sp,
                )
            }
            Box {
                FilledIconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.semantics { contentDescription = "Menu" },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = CameraPanel,
                            contentColor = Color.White,
                        ),
                ) {
                    Text("⋯", fontSize = 26.sp)
                }
                if (expanded)
                    GlassSheet("Options", { expanded = false }) {
                        Column(
                            Modifier.fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 10.dp),
                        ) {
                            menu { expanded = false }
                        }
                    }
            }
        }
    }
}

@Composable
internal fun CameraComposer(
    vm: VisionModel,
    capturing: Boolean,
    enabled: Boolean,
    onQuery: () -> Unit,
    onResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var skills by remember { mutableStateOf(false) }
    val busy = vm.busy || capturing
    GlassSurface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (vm.skill != Skill.CAPTION)
                TextField(
                    vm.prompt,
                    { vm.prompt = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("Object or question") },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (enabled && !busy) onQuery() }),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    TextButton(onClick = { skills = true }, enabled = !busy) {
                        Text(vm.skill.title + " ⌄", color = Color.White)
                    }
                    if (skills)
                        GlassSheet("Query", { skills = false }) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 10.dp),
                            ) {
                                Skill.entries.forEach { skill ->
                                    GlassMenuItem(
                                        skill.title,
                                        skill.icon,
                                        {
                                            vm.skill = skill
                                            skills = false
                                        },
                                        selected = vm.skill == skill,
                                    )
                                }
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    color = Ice.copy(alpha = .15f),
                                )
                                GlassMenuItem(
                                    "Multiple objects",
                                    R.drawable.ic_segment,
                                    { vm.manyObjects = !vm.manyObjects },
                                    trailingIcon = {
                                        Switch(
                                            vm.manyObjects,
                                            { vm.manyObjects = it },
                                            colors =
                                                SwitchDefaults.colors(
                                                    checkedThumbColor = GlassNavy,
                                                    checkedTrackColor = Ice,
                                                ),
                                        )
                                    },
                                )
                            }
                        }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onQuery,
                    enabled = enabled && !busy,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Query")
                }
            }
        }
    }
}

@Composable
internal fun CameraAction(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    GlassSurface(
        shape = CircleShape,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            Modifier.defaultMinSize(minHeight = 46.dp)
                .background(if (selected) Ice.copy(alpha = .85f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color =
                    if (!enabled) Color.White.copy(alpha = .4f)
                    else if (selected) Color(0xFF17254B) else Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun CameraResultSheet(vm: VisionModel, onDismiss: () -> Unit) {
    GlassSheet("Results", onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            vm.image?.let { ResultPhoto(it, vm.marks.filterNot { mark -> mark.manual }) }
            if (vm.result.isNotBlank())
                SelectionContainer { Text(vm.result, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
internal fun ApiSettings(vm: VisionModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(vm.apiKey) }
    var model by remember { mutableStateOf(vm.model) }
    GlassSheet("Moondream API", onDismiss, closeEnabled = !vm.saving) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                key,
                { key = it },
                Modifier.fillMaxWidth(),
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !vm.saving,
                shape = RoundedCornerShape(18.dp),
            )
            listOf("moondream3.1-9B-A2B", "moondream3-preview").forEach { value ->
                GlassMenuItem(
                    value,
                    R.drawable.ic_tracking,
                    { model = value },
                    selected = model == value,
                    enabled = !vm.saving,
                )
            }
            vm.settingsMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.configure(key, model, onDismiss) },
                enabled = !vm.saving,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = CircleShape,
            ) {
                Text(if (vm.saving) "Saving…" else "Save")
            }
        }
    }
}

@Composable
internal fun CameraError(vm: VisionModel, host: SnackbarHostState) {
    val message = vm.error
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message, duration = SnackbarDuration.Long)
            if (vm.error == message) vm.error = null
        }
    }
}
