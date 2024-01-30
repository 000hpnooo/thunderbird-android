package app.k9mail.feature.account.server.validation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Suppress("ViewModelForwarding")
@Composable
fun SetupServerValidationScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: ServerValidationContract.ViewModel,
    modifier: Modifier = Modifier,
) {
    ServerValidationContainerScreen(
        onNext = onNext,
        onBack = onBack,
        viewModel = viewModel,
        modifier = modifier,
    ) { contentModifier ->
        ServerValidationAppTitleScreen(
            viewModel = viewModel,
            modifier = contentModifier,
        )
    }
}
