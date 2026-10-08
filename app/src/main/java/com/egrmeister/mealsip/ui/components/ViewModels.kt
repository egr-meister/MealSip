package com.egrmeister.mealsip.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Manual DI for ViewModels: the factory receives the destination's SavedStateHandle. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (SavedStateHandle) -> VM,
): VM = viewModel(
    key = key,
    factory = viewModelFactory { initializer { create(createSavedStateHandle()) } },
)
