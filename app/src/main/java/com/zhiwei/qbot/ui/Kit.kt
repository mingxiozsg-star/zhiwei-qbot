package com.zhiwei.qbot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = true,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = if (hint.isBlank()) null else ({ Text(hint, fontSize = 13.sp, color = TextDim) }),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    )
}

@Composable
fun NumField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    hint: String = ""
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label, fontSize = 12.sp) },
        placeholder = if (hint.isBlank()) null else ({ Text(hint, fontSize = 13.sp, color = TextDim) }),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    )
}

@Composable
fun ActionRow(title: String, desc: String = "", trailing: String = "›", onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = TextMain)
            if (desc.isNotBlank()) {
                Text(desc, fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(trailing, fontSize = 18.sp, color = TextDim)
    }
}

@Composable
fun Primary(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color.Black),
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun Ghost(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Stroke),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Text(text, fontSize = 14.sp)
    }
}

@Composable
fun TinyBtn(text: String, color: Color = Cyan, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(CardBg2, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
fun Line() {
    HorizontalDivider(color = Stroke, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
}

@Composable
fun Chips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                fontSize = 12.sp,
                color = if (active) Color.Black else TextDim,
                modifier = Modifier
                    .background(if (active) Pink else CardBg2, RoundedCornerShape(12.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
fun SimpleDialog(
    title: String,
    text: String,
    confirm: String = "知道了",
    dismiss: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp, color = TextMain) },
        text = {
            Text(text, fontSize = 13.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = Pink) }
        },
        dismissButton = if (dismiss == null) null else ({
            TextButton(onClick = onDismiss) { Text(dismiss, color = TextDim) }
        })
    )
}