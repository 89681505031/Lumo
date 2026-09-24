package app.lumo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LumoOfflineCacheControls(userId:String){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var files by remember(userId){mutableIntStateOf(0)}
    var clearing by remember(userId){mutableStateOf(false)}
    var notice by remember(userId){mutableStateOf("")}

    fun refresh(){
        files=LumoOfflineStore.cacheFileCount(context,userId)
    }

    LaunchedEffect(userId){
        files=withContext(Dispatchers.IO){
            LumoOfflineStore.cacheFileCount(context,userId)
        }
    }

    Column(Modifier.fillMaxWidth().lumoGlass(26).padding(17.dp)){
        Text(
            "Офлайн-история",
            style=MaterialTheme.typography.titleLarge,
            fontWeight=FontWeight.Bold,
            color=Color.White
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "Lumo хранит на этом телефоне до 150 последних сообщений каждого открытого чата " +
                "и до 100 диалогов. Кэш зашифрован ключом Android Keystore.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if(files>0)"Зашифрованных файлов: $files" else "Сохранённой офлайн-истории пока нет",
            color=Color.White,
            style=MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Это защита данных на устройстве, а не сквозное шифрование переписки. " +
                "Медиафайлы и временные ссылки не сохраняются в этом кэше.",
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
        if(files>0){
            Spacer(Modifier.height(12.dp))
            TextButton(
                enabled=!clearing,
                onClick={
                    clearing=true
                    notice=""
                    scope.launch{
                        val removed=withContext(Dispatchers.IO){
                            LumoOfflineStore.clearAccount(context,userId)
                        }
                        notice="Удалено файлов: $removed"
                        clearing=false
                        refresh()
                    }
                },
                modifier=Modifier.align(Alignment.End)
            ){
                Text(
                    if(clearing)"Удаляем…" else "Удалить офлайн-историю",
                    color=Color.White
                )
            }
        }
        if(notice.isNotBlank()){
            Text(
                notice,
                style=MaterialTheme.typography.bodySmall,
                color=LumoCyan
            )
        }
    }
}
