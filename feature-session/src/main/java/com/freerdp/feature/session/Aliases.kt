package com.freerdp.feature.session

// Re-export core session components for convenience
typealias RdpProfile = com.freerdp.feature.session.model.RdpProfile
typealias CredentialStorageType = com.freerdp.feature.session.model.CredentialStorageType
typealias ResolutionMode = com.freerdp.feature.session.model.ResolutionMode
typealias ColorDepth = com.freerdp.feature.session.model.ColorDepth
typealias ScalingMode = com.freerdp.feature.session.model.ScalingMode
typealias CertValidationMode = com.freerdp.feature.session.model.CertValidationMode
typealias DisplayConfig = com.freerdp.feature.session.model.DisplayConfig
typealias RedirectionConfig = com.freerdp.feature.session.model.RedirectionConfig
typealias NetworkConfig = com.freerdp.feature.session.model.NetworkConfig
typealias SecurityConfig = com.freerdp.feature.session.model.SecurityConfig

typealias ProfileRepository = com.freerdp.feature.session.repository.ProfileRepository
typealias AtomicFileProfileRepository = com.freerdp.feature.session.data.AtomicFileProfileRepository

typealias CredentialStore = com.freerdp.feature.session.security.CredentialStore
typealias KeystoreCredentialStore = com.freerdp.feature.session.security.KeystoreCredentialStore

typealias QuickActionToolbarFSM = com.freerdp.feature.session.toolbar.QuickActionToolbarFSM
typealias ToolbarState = com.freerdp.feature.session.toolbar.ToolbarState
typealias ToolbarAction = com.freerdp.feature.session.toolbar.ToolbarAction

typealias ModifierStateMachine = com.freerdp.feature.session.modifier.ModifierStateMachine
typealias ModifierKey = com.freerdp.feature.session.modifier.ModifierKey
typealias LatchState = com.freerdp.feature.session.modifier.LatchState
typealias MacroAction = com.freerdp.feature.session.modifier.MacroAction

typealias ScancodeTranslator = com.freerdp.feature.session.keyboard.ScancodeTranslator
typealias ScancodeResult = com.freerdp.feature.session.keyboard.ScancodeResult
typealias MacroStep = com.freerdp.feature.session.keyboard.MacroStep
