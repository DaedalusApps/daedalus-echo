package com.daedalusapps.echo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.content.Context
import com.daedalusapps.echo.data.model.TodoItem
import com.daedalusapps.echo.ui.components.SwipeToDeleteCard
import com.daedalusapps.echo.viewmodel.TodoViewModel

/** Sentinel hours value marking the "Custom" radio option (not a real lookback duration). */
private const val CUSTOM_HOURS_SENTINEL = -2L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    todoViewModel: TodoViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val todos by todoViewModel.todos.collectAsState()
    val isExtracting by todoViewModel.isExtracting.collectAsState()
    val extractError by todoViewModel.extractError.collectAsState()
    val lastExtractCount by todoViewModel.lastExtractCount.collectAsState()

    var showLookbackDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    // TodoViewModel is Activity-scoped, so a completion/error from a previous visit to this
    // screen can still be sitting in state. Clear it once on entry, before the snackbar
    // effects below observe it, so only fresh completions from this visit show a snackbar.
    LaunchedEffect(Unit) {
        todoViewModel.clearLastExtractCount()
        todoViewModel.clearError()
    }

    LaunchedEffect(lastExtractCount) {
        if (lastExtractCount != null) {
            val count = lastExtractCount!!
            snackbar.showSnackbar(if (count > 0) "Added $count new todos" else "No new todos found")
            todoViewModel.clearLastExtractCount()
        }
    }

    LaunchedEffect(extractError) {
        extractError?.let {
            snackbar.showSnackbar(it)
            todoViewModel.clearError()
        }
    }

    if (showLookbackDialog) {
        LookbackDialog(
            context = context,
            onDismiss = { showLookbackDialog = false },
            onConfirm = { hours ->
                showLookbackDialog = false
                todoViewModel.updateFromRecordings(hours)
            }
        )
    }

    if (showAddDialog) {
        AddTodoDialog(
            onDismiss = { showAddDialog = false },
            onSave = { text ->
                todoViewModel.addTodo(text)
                showAddDialog = false
            }
        )
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Todos") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showLookbackDialog = true },
                            enabled = !isExtracting
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Update from recordings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                if (isExtracting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add todo")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { innerPadding ->
        if (todos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "No todos yet.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the sparkle icon above to extract action items from your recordings, or add one manually with the + button.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(todos, key = { it.id }) { todo ->
                    TodoSwipeToDeleteCard(
                        todo = todo,
                        onToggleDone = { todoViewModel.toggleDone(todo) },
                        onDelete = { todoViewModel.deleteTodo(todo) },
                        onEditSave = { newText -> todoViewModel.editTodo(todo, newText) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoSwipeToDeleteCard(
    todo: TodoItem,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    onEditSave: (String) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember(todo.id) { mutableStateOf(todo.text) }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit todo") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEditSave(editText)
                        showEditDialog = false
                    },
                    enabled = editText.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    SwipeToDeleteCard(
        confirmTitle = "Delete todo?",
        confirmText = "This will permanently remove this todo.",
        onDelete = onDelete
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = todo.isDone, onCheckedChange = { onToggleDone() })
                Text(
                    text = todo.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (todo.isDone) TextDecoration.LineThrough else null,
                    color = if (todo.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .clickable { showEditDialog = true }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTodoDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add todo") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("What needs to be done?") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(text) },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookbackDialog(
    context: Context,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val prefs = remember { context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE) }
    val storedHours = remember { prefs.getLong(TODO_LOOKBACK_HOURS_KEY, TODO_LOOKBACK_HOURS_DEFAULT) }
    val initialSelection = remember { lookbackOptionFor(storedHours) }

    var selectedHours by remember {
        mutableStateOf(if (initialSelection is LookbackSelection.Standard) initialSelection.hours else CUSTOM_HOURS_SENTINEL)
    }
    var customText by remember {
        mutableStateOf(if (initialSelection is LookbackSelection.Custom) initialSelection.hours.toString() else "")
    }
    val isCustomSelected = selectedHours == CUSTOM_HOURS_SENTINEL

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update from recordings") },
        text = {
            Column(Modifier.selectableGroup()) {
                LOOKBACK_OPTIONS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedHours == option.hours,
                                onClick = { selectedHours = option.hours }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedHours == option.hours, onClick = { selectedHours = option.hours })
                        Spacer(Modifier.width(8.dp))
                        Text(option.label)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isCustomSelected,
                            onClick = { selectedHours = CUSTOM_HOURS_SENTINEL }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isCustomSelected, onClick = { selectedHours = CUSTOM_HOURS_SENTINEL })
                    Spacer(Modifier.width(8.dp))
                    Text("Custom")
                }
                if (isCustomSelected) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text("Hours") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp)
                    )
                }
            }
        },
        confirmButton = {
            val customHours = customText.toLongOrNull()
            val confirmHours = if (isCustomSelected) customHours else selectedHours
            Button(
                onClick = { if (confirmHours != null) onConfirm(confirmHours) },
                enabled = confirmHours != null && (!isCustomSelected || confirmHours > 0)
            ) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
