package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.host.TerminalRow

@Composable
fun TerminalHostCard(state:DazeDevMobileUiState,row:TerminalRow,onChoose:()->Unit,workspace:String="") {
    val target=state.destination(row)
    val colors=MaterialTheme.colorScheme
    val badgeBackground=if(target.otherHost) colors.secondaryContainer else colors.primaryContainer
    val badgeForeground=if(target.otherHost) colors.onSecondaryContainer else colors.onPrimaryContainer
    OutlinedButton(onClick=onChoose,enabled=target.host?.phase!=ConnectionPhase.Connecting,
        modifier=Modifier.fillMaxWidth().heightIn(min=104.dp),contentPadding=PaddingValues(0.dp),shape=MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(color=badgeBackground,contentColor=badgeForeground,modifier=Modifier.weight(0.36f),shape=MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(if(target.otherHost)"Other host" else "This host",style=MaterialTheme.typography.labelMedium)
                    Text(target.label,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(target.reference.address.isNotBlank() && target.reference.address!=target.label) Text(target.reference.address,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.weight(0.64f).padding(top=12.dp,bottom=12.dp,end=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(row.name,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis,color=colors.onSurface)
                if(workspace.isNotBlank())Text(workspace,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                Text(target.status,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                Text(target.action,style=MaterialTheme.typography.labelLarge,color=colors.primary)
            }
        }
    }
}
