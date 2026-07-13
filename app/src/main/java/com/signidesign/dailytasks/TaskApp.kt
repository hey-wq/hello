package com.signidesign.dailytasks

import android.app.Application
import android.content.Context
import com.signidesign.dailytasks.data.AppDatabase
import com.signidesign.dailytasks.data.TaskRepository
import com.signidesign.dailytasks.data.ThemeRepository

/**
 * Manual DI container — deliberately no Hilt/Koin for an app this size.
 * Everything hangs off two repositories, so a framework would add build
 * complexity without buying anything. Swappable later if the graph grows.
 */
class AppContainer(context: Context) {
    private val database = AppDatabase.get(context)
    val taskRepository = TaskRepository(database.taskDao(), database.dayNoteDao())
    val themeRepository = ThemeRepository(context)
}

class TaskApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
