package com.rdp.client.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rdp.client.model.AppDatabase
import com.rdp.client.repository.IProfileRepository
import com.rdp.client.repository.ProfileRepository

/**
 * ViewModelProvider.Factory for instantiating ProfileViewModel with its repository dependency.
 */
class ProfileViewModelFactory(
    private val repository: IProfileRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            return ProfileViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }

    companion object {
        @androidx.annotation.VisibleForTesting
        var testRepository: IProfileRepository? = null

        fun create(context: Context): ProfileViewModelFactory {
            val repository = testRepository ?: ProfileRepository(AppDatabase.getInstance(context).serverProfileDao())
            return ProfileViewModelFactory(repository)
        }
    }
}
