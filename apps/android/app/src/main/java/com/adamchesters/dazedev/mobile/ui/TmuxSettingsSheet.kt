package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable
fun TmuxSettingsSheet(settings:JSONObject,onApply:(String,String)->Unit,onDismiss:()->Unit) {
    val options=settings.getJSONArray("options")
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("tmux settings",style=MaterialTheme.typography.headlineSmall)
                Text("Socket: ${settings.getString("socket")} · ${settings.getString("version")}")
                Text("Changes affect every client of this session/window. No global defaults are changed.")
                Text("Revision: ${settings.getString("revision").take(12)}")
                for(i in 0 until options.length()) {
                    val option=options.getJSONObject(i)
                    if(option.optBoolean("unsupported"))continue
                    val name=option.getString("name");val allowed=option.getJSONArray("allowed")
                    var value by remember(settings.getString("revision"),name){mutableStateOf(option.getString("value"))}
                    Text("$name · ${option.getString("kind")}${if(option.optBoolean("inherited"))" · inherited" else ""}")
                    OutlinedTextField(value,{value=it},label={Text("Value")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(allowed.opt(0) is String) allowed.let {a->Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {for(j in 0 until a.length())TextButton(onClick={value=a.getString(j)}){Text(a.getString(j))}}}
                    else Text("Allowed range: ${allowed.getInt(0)}–${allowed.getInt(1)}")
                    Button(onClick={onApply(name,value)},enabled=value!=option.getString("value")){Text("Apply $name")}
                }
                TextButton(onClick=onDismiss){Text("Close")}
            }
        }
    }
}
