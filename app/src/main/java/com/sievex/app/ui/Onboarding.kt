package com.sievex.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sievex.app.R
import kotlinx.coroutines.launch

/** One page of the first-run flow: what it says, its artwork, and what the primary button offers. */
private data class OnboardingStep(
    val plateLabel: String,
    @DrawableRes val artRes: Int,
    @StringRes val heading: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
    @StringRes val footnote: Int? = null
)

private val onboardingSteps = listOf(
    OnboardingStep(
        plateLabel = "FOLIO I",
        artRes = R.drawable.art_onboard_1,
        heading = R.string.sys_onboarding_step1_title,
        body = R.string.sys_onboarding_step1_body,
        action = R.string.sys_onboarding_action_continue
    ),
    OnboardingStep(
        plateLabel = "FOLIO II",
        artRes = R.drawable.art_onboard_2,
        heading = R.string.sys_onboarding_step2_title,
        body = R.string.sys_onboarding_step2_body,
        action = R.string.sys_onboarding_action_continue
    ),
    OnboardingStep(
        plateLabel = "FOLIO III",
        artRes = R.drawable.art_onboard_3,
        heading = R.string.sys_onboarding_step3_title,
        body = R.string.sys_onboarding_step3_body,
        action = R.string.sys_onboarding_action_get_started,
        footnote = R.string.sys_onboarding_permissions_hint
    )
)

/**
 * First-run flow: three swipeable editorial plates with fixed masthead chrome and contextual permission priming.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingSteps.size })
    val scope = rememberCoroutineScope()
    val step = pagerState.currentPage
    var finished by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = step > 0) {
        scope.launch { pagerState.animateScrollToPage(step - 1) }
    }

    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 24.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkvoxMark(size = 22.dp)
            Spacer(Modifier.width(8.dp))
            InkvoxWordmark(height = 21.dp)
            Spacer(Modifier.weight(1f))
            if (step < onboardingSteps.lastIndex) {
                SkipButton(onClick = finish)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Line)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { index ->
            val page = onboardingSteps[index]
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingPlate(step = index, page = page)
                Spacer(Modifier.height(28.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        page.plateLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = EditorialMono,
                            letterSpacing = 1.4.sp
                        ),
                        color = SignalText
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(page.heading),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ink
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(page.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink2
                    )
                    if (page.footnote != null) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = Surface1,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Line),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(page.footnote),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink2,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }
        }

        StepIndicator(
            index = step,
            onSelect = { target ->
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        )
        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                BackButton {
                    scope.launch { pagerState.animateScrollToPage(step - 1) }
                }
                Spacer(Modifier.width(12.dp))
            }
            PrimaryAction(
                label = stringResource(onboardingSteps[step].action),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (step < onboardingSteps.lastIndex) {
                        scope.launch { pagerState.animateScrollToPage(step + 1) }
                    } else {
                        finish()
                    }
                }
            )
        }
    }
}

/** Framed editorial risograph plate rendering custom artwork adapted cleanly to light or dark mode. */
@Composable
private fun OnboardingPlate(step: Int, page: OnboardingStep) {
    val accent = when (step) {
        1 -> Blue
        2 -> Mint
        else -> Signal
    }
    val plateTint = when (step) {
        1 -> BlueSoft
        2 -> MintSoft
        else -> SignalSoft
    }
    val plateModifier = if (IsDarkTheme) {
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(plateTint.copy(alpha = 0.85f), Surface1)))
            .border(1.dp, accent.copy(alpha = 0.36f), RoundedCornerShape(6.dp))
            .padding(16.dp)
    } else {
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(plateTint.copy(alpha = 0.55f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
            .padding(16.dp)
    }

    Surface(
        color = Surface1,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (IsDarkTheme) LineStrong else Line),
        modifier = Modifier
            .fillMaxWidth()
            .height(228.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = plateModifier,
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = page.artRes,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
                    label = "onboarding artwork"
                ) { resId ->
                    EditorialArtwork(
                        resId = resId,
                        contentDescription = stringResource(page.heading),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** Three interactive capsules with a full 48dp hit height. */
@Composable
private fun StepIndicator(index: Int, onSelect: (Int) -> Unit) {
    val stepDescription =
        stringResource(R.string.sys_onboarding_step_indicator, index + 1, onboardingSteps.size)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = stepDescription
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        onboardingSteps.indices.forEach { i ->
            val current = i == index
            val capsuleWidth by animateDpAsState(
                targetValue = if (current) 24.dp else 8.dp,
                animationSpec = tween(200),
                label = "step capsule"
            )
            Box(
                Modifier
                    .height(48.dp)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = capsuleWidth, height = 6.dp)
                        .clip(CircleShape)
                        .background(if (current) Signal else Line)
                )
            }
        }
    }
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Text(
                stringResource(R.string.sys_onboarding_skip),
                style = MaterialTheme.typography.labelMedium,
                color = Ink2
            )
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Surface2,
        shape = CircleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.size(54.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.sys_onboarding_back_content_description),
                tint = Ink,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = SignalFill,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.heightIn(min = 54.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Persists first-run completion immediately in-memory via `.apply()` and flushes asynchronously
 * so closing the intro never blocks the UI thread.
 */
object OnboardingPrefs {
    private const val FILE = "sievex_onboarding"
    private const val KEY = "done_v1"

    fun done(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .apply()
    }
}
